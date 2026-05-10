package com.airportweather.map.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import androidx.core.database.getDoubleOrNull
import androidx.core.database.getStringOrNull

/** One waypoint match from [AirportDatabaseHelper.searchWaypoints]. */
data class WaypointSearchResult(
    /** Identifier to insert into the flight plan (ICAO if available, else FAA ID). */
    val code: String,
    /** "AIRPORT", "NAVAID", or "FIX". */
    val type: String,
    /** Display name (e.g., "Van Nuys Airport") or null for fixes. */
    val name: String?,
    /** "City, State" if known, else null. */
    val location: String?,
)

class AirportDatabaseHelper(private val context: Context) :
    SQLiteOpenHelper(context, "faa_navigation.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase?) {} // Not needed if you're using a prebuilt DB
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}

    // ---------------------------------------------------------------------------
    // Free-text search across airports / navaids / fixes for the flight-plan UI.
    // Used by the search box below the waypoints field in FlightPlanActivity.
    // ---------------------------------------------------------------------------

    /**
     * Search for waypoints by code, name, or city. Returns up to [limit] results
     * ranked: exact ID match → ID prefix → name/city prefix → substring contains.
     * Returns empty list for queries shorter than 2 chars.
     */
    fun searchWaypoints(query: String, limit: Int = 30): List<WaypointSearchResult> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        val airports = searchAirports(trimmed, limit)
        val remaining1 = (limit - airports.size).coerceAtLeast(0)
        val navaids = if (remaining1 > 0) searchNavaids(trimmed, remaining1) else emptyList()
        val remaining2 = (limit - airports.size - navaids.size).coerceAtLeast(0)
        val fixes = if (remaining2 > 0) searchFixes(trimmed, remaining2) else emptyList()
        return airports + navaids + fixes
    }

    private fun searchAirports(query: String, limit: Int): List<WaypointSearchResult> {
        val prefix = "$query%"
        val contains = "%$query%"
        val sql = """
            SELECT ARPT_ID, ICAO_ID, ARPT_NAME, CITY, STATE_NAME
            FROM APT_BASE
            WHERE ICAO_ID LIKE ? COLLATE NOCASE
               OR ARPT_ID LIKE ? COLLATE NOCASE
               OR ARPT_NAME LIKE ? COLLATE NOCASE
               OR CITY LIKE ? COLLATE NOCASE
            ORDER BY
              CASE
                WHEN ICAO_ID = ? COLLATE NOCASE THEN 0
                WHEN ARPT_ID = ? COLLATE NOCASE THEN 1
                WHEN ICAO_ID LIKE ? COLLATE NOCASE THEN 2
                WHEN ARPT_ID LIKE ? COLLATE NOCASE THEN 3
                WHEN ARPT_NAME LIKE ? COLLATE NOCASE THEN 4
                WHEN CITY LIKE ? COLLATE NOCASE THEN 5
                ELSE 6
              END,
              ARPT_NAME
            LIMIT $limit
        """.trimIndent()
        val args = arrayOf(
            prefix, prefix, contains, contains,
            query, query, prefix, prefix, prefix, prefix,
        )
        val results = mutableListOf<WaypointSearchResult>()
        try {
            readableDatabase.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val arptId = cursor.getString(0)
                    val icaoId = cursor.getStringOrNull(1)
                    val name = cursor.getStringOrNull(2)
                    val city = cursor.getStringOrNull(3)
                    val state = cursor.getStringOrNull(4)
                    val code = icaoId?.takeIf { it.isNotBlank() } ?: arptId
                    val location = listOfNotNull(
                        city?.takeIf { it.isNotBlank() },
                        state?.takeIf { it.isNotBlank() },
                    ).joinToString(", ").takeIf { it.isNotBlank() }
                    results += WaypointSearchResult(code, "AIRPORT", name, location)
                }
            }
        } catch (e: Exception) {
            Log.w("WaypointSearch", "Airport search failed: ${e.message}")
        }
        return results
    }

    private fun searchNavaids(query: String, limit: Int): List<WaypointSearchResult> {
        val prefix = "$query%"
        val contains = "%$query%"
        // NAV_BASE schema varies by build of the DB. Try with NAV_NAME + CITY first;
        // if those columns aren't present, the SQL throws and we fall back to code-only.
        val richSql = """
            SELECT NAV_ID, NAV_NAME, CITY, STATE_CODE
            FROM NAV_BASE
            WHERE NAV_ID LIKE ? COLLATE NOCASE
               OR NAV_NAME LIKE ? COLLATE NOCASE
               OR CITY LIKE ? COLLATE NOCASE
            ORDER BY
              CASE
                WHEN NAV_ID = ? COLLATE NOCASE THEN 0
                WHEN NAV_ID LIKE ? COLLATE NOCASE THEN 1
                WHEN NAV_NAME LIKE ? COLLATE NOCASE THEN 2
                WHEN CITY LIKE ? COLLATE NOCASE THEN 3
                ELSE 4
              END,
              NAV_NAME
            LIMIT $limit
        """.trimIndent()
        val results = mutableListOf<WaypointSearchResult>()
        try {
            readableDatabase.rawQuery(
                richSql,
                arrayOf(prefix, contains, contains, query, prefix, prefix, prefix),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getStringOrNull(1)
                    val city = cursor.getStringOrNull(2)
                    val state = cursor.getStringOrNull(3)
                    val location = listOfNotNull(
                        city?.takeIf { it.isNotBlank() },
                        state?.takeIf { it.isNotBlank() },
                    ).joinToString(", ").takeIf { it.isNotBlank() }
                    results += WaypointSearchResult(id, "NAVAID", name, location)
                }
            }
        } catch (e: Exception) {
            // Fallback: NAV_NAME / CITY not in schema. Code-only prefix search.
            Log.d("WaypointSearch", "Falling back to NAV_ID-only navaid search: ${e.message}")
            try {
                readableDatabase.rawQuery(
                    "SELECT NAV_ID FROM NAV_BASE WHERE NAV_ID LIKE ? COLLATE NOCASE ORDER BY NAV_ID LIMIT $limit",
                    arrayOf(prefix),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        results += WaypointSearchResult(cursor.getString(0), "NAVAID", null, null)
                    }
                }
            } catch (e2: Exception) {
                Log.w("WaypointSearch", "Navaid search failed entirely: ${e2.message}")
            }
        }
        return results
    }

    private fun searchFixes(query: String, limit: Int): List<WaypointSearchResult> {
        // Fixes have no names — just 5-letter codes. Prefix match only.
        val prefix = "$query%"
        val results = mutableListOf<WaypointSearchResult>()
        try {
            readableDatabase.rawQuery(
                "SELECT FIX_ID FROM FIX_BASE WHERE FIX_ID LIKE ? COLLATE NOCASE ORDER BY FIX_ID LIMIT $limit",
                arrayOf(prefix),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    results += WaypointSearchResult(cursor.getString(0), "FIX", null, null)
                }
            }
        } catch (e: Exception) {
            Log.w("WaypointSearch", "Fix search failed: ${e.message}")
        }
        return results
    }

    // New waypoint lookup
    fun lookupWaypoint(code: String): Waypoint? {
        val upperCode = code.uppercase()

        // User-defined test waypoints (assets/fake_waypoints.txt) take priority
        // so they can shadow real codes if needed for testing.
        UserWaypoints.ensureLoaded(context)
        UserWaypoints.lookup(upperCode)?.let { return it }

        return when {
            upperCode.startsWith("K") && upperCode.length == 4 -> {
                // Prefer AIRPORT (e.g., "KVNY")
                lookupInOrder(upperCode, listOf("AIRPORT", "NAVAID", "FIX"))
            }
            upperCode.length == 3 -> {
                // Prefer NAVAID (e.g., "VNY")
                lookupInOrder(upperCode, listOf("NAVAID", "AIRPORT", "FIX"))
            }
            upperCode.length == 5 -> {
                // Prefer FIX (e.g., "ELKEY")
                lookupInOrder(upperCode, listOf("FIX", "AIRPORT", "NAVAID"))
            }
            else -> {
                // Default fallback
                lookupInOrder(upperCode, listOf("AIRPORT", "NAVAID", "FIX"))
            }
        }
    }

    private fun lookupInOrder(code: String, order: List<String>): Waypoint? {
        for (type in order) {
            val wp = when (type) {
                "NAVAID" -> lookupNavaid(code)
                "AIRPORT" -> lookupAirport(code)
                "FIX" -> lookupFix(code)
                else -> null
            }
            if (wp != null) return wp
        }
        return null
    }

    private fun lookupAirport(code: String): Waypoint? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT LAT_DECIMAL, LONG_DECIMAL, ELEV, MAG_VARN, MAG_HEMIS FROM APT_BASE WHERE ICAO_ID = ? COLLATE NOCASE OR ARPT_ID = ? COLLATE NOCASE",
            arrayOf(code, code)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val lat = cursor.getDouble(0)
                val lon = cursor.getDouble(1)
                val elev = cursor.getFloat(2)
                val magVarRaw = cursor.getDouble(3)
                val magHemis = cursor.getString(4)

                val magVar = when (magHemis.uppercase()) {
                    "E" -> -magVarRaw
                    "W" -> magVarRaw
                    else -> 0.0
                }
                return Waypoint(code, "AIRPORT", lat, lon, elev, magVar = magVar)
            }
        }
        return null
    }

    private fun lookupNavaid(code: String): Waypoint? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT LAT_DECIMAL, LONG_DECIMAL, MAG_VARN, MAG_VARN_HEMIS FROM NAV_BASE WHERE NAV_ID = ? COLLATE NOCASE",
            arrayOf(code)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val lat = cursor.getDouble(0)
                val lon = cursor.getDouble(1)
                val magVarRaw = cursor.getDoubleOrNull(2) ?: 0.0
                val magHemis = cursor.getStringOrNull(3) ?: ""

                val magVar = when (magHemis.uppercase()) {
                    "E" -> -magVarRaw
                    "W" -> magVarRaw
                    else -> 0.0
                }

                return Waypoint(code, "NAVAID", lat, lon, magVar = magVar)
            }
        }
        return null
    }

    private fun lookupFix(code: String): Waypoint? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT LAT_DECIMAL, LONG_DECIMAL FROM FIX_BASE WHERE FIX_ID = ? COLLATE NOCASE",
            arrayOf(code)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val lat = cursor.getDouble(0)
                val lon = cursor.getDouble(1)
                return Waypoint(code, "FIX", lat, lon, magVar = 0.0)
            }
        }
        return null
    }

    // Airport stuff for flight plan search
    fun airportExists(code: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            //"SELECT 1 FROM APT_BASE WHERE ICAO_ID = ? COLLATE NOCASE",
            "SELECT 1 FROM APT_BASE WHERE ARPT_ID = ? COLLATE NOCASE OR ICAO_ID = ? COLLATE NOCASE",
            arrayOf(code, code)
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }
    fun airportPrefixExists(prefix: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM APT_BASE WHERE ARPT_ID LIKE ? COLLATE NOCASE OR ICAO_ID LIKE ? COLLATE NOCASE LIMIT 1",
            arrayOf("$prefix%", "$prefix%")
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }
    // Nav aid stuff for flight plan search
    fun navExists(code: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM NAV_BASE WHERE NAV_ID = ? COLLATE NOCASE",
            arrayOf(code)
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }
    fun navPrefixExists(prefix: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM NAV_BASE WHERE NAV_ID LIKE ? COLLATE NOCASE LIMIT 1",
            arrayOf("$prefix%")
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }
    // Fix stuff for flight plan search
    fun fixExists(code: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM FIX_BASE WHERE FIX_ID = ? COLLATE NOCASE",
            arrayOf(code)
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }
    fun fixPrefixExists(prefix: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM FIX_BASE WHERE FIX_ID LIKE ? COLLATE NOCASE LIMIT 1",
            arrayOf("$prefix%")
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    // Airport stuff for drawing metars and general info
    fun getAirportName(code: String): String? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT ARPT_NAME FROM APT_BASE WHERE ARPT_ID = ? COLLATE NOCASE",
            arrayOf(code)
        )
        val name = if (cursor.moveToFirst()) cursor.getString(0) else null
        cursor.close()
        return name
    }
    fun getAirportInfo(code: String): AirportInfo? {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT ARPT_NAME, CITY, STATE_NAME, LAT_DECIMAL, LONG_DECIMAL, ELEV, MAG_VARN, MAG_HEMIS, ICAO_ID, ARPT_ID FROM APT_BASE WHERE ARPT_ID = ? OR ICAO_ID = ? COLLATE NOCASE",
            arrayOf(code, code)
        )

        val airportInfo = if (cursor.moveToFirst()) {
            val name = cursor.getString(0)
            val city = cursor.getString(1)
            val state = cursor.getString(2)
            val lat = cursor.getDouble(3)
            val lon = cursor.getDouble(4)
            val elev = cursor.getDouble(5)
            val magVarRaw = cursor.getDouble(6)
            val magHemis = cursor.getString(7)
            val icaoId = cursor.getString(8)
            val arptId = cursor.getString(9)
            // east is least, west is best
            val magVar = when (magHemis.uppercase()) {
                "E" -> -magVarRaw
                "W" -> magVarRaw
                else -> 0.0 // Fallback if missing or unknown
            }

            AirportInfo(
                airportId = arptId,
                icaoId = icaoId,
                name = name,
                city = city,
                state = state,
                lat = lat,
                lon = lon,
                elev = elev,
                magVar = magVar
            )
        } else {
            null
        }

        cursor.close()
        return airportInfo
    }

    // Runway stuff
    fun getRunwaysForAirport(arptId: String): List<Runway> {
        val db = readableDatabase

        // ✅ Remove leading 'K' if present
        val searchId = if (arptId.startsWith("K", ignoreCase = true) && arptId.length == 4) {
            arptId.substring(1)
        } else {
            arptId
        }

        val cursor = db.rawQuery(
            """
            SELECT RWY_ID, RWY_END_ID, TRUE_ALIGNMENT, LAT_DECIMAL, LONG_DECIMAL, RIGHT_HAND_TRAFFIC_PAT_FLAG
            FROM APT_RWY_END
            WHERE ARPT_ID = ? COLLATE NOCASE
        """.trimIndent(), arrayOf(searchId)
        )

        val runwayMap = mutableMapOf<String, MutableList<RunwayEnd>>()

        while (cursor.moveToNext()) {
            val rwyId = cursor.getString(0)
            val endId = cursor.getString(1)
            val heading = cursor.getDouble(2)
            val lat = cursor.getDouble(3)
            val lon = cursor.getDouble(4)
            val rhtp = cursor.getString(5)

            val end = RunwayEnd(endId, heading, lat, lon, rhtp)
            if (!runwayMap.containsKey(rwyId)) {
                runwayMap[rwyId] = mutableListOf()
            }
            runwayMap[rwyId]?.add(end)
        }

        cursor.close()

        val runways = mutableListOf<Runway>()
        for ((rwyId, ends) in runwayMap) {
            if (ends.size == 2) {
                runways.add(Runway(rwyId, ends[0], ends[1]))
            } else {
                // Log or skip if we don't have both ends
                Log.w("RunwayParse", "Skipping runway $rwyId for $arptId — only ${ends.size} end(s) found")
            }
        }

        return runways
    }

}