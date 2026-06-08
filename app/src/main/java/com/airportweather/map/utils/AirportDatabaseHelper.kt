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
     * Search for waypoints by code, name, or city. Each type (airport,
     * navaid, fix) gets its own quota so a query like "UMBER" — which
     * matches 30+ "Cumberland" airports by city — doesn't starve out
     * the UMBER fix the user is actually looking for. Within a type
     * the ranking is the same as before (exact ID → ID prefix → name
     * prefix → substring). Returns empty list for queries < 2 chars.
     *
     * If an exact ID match exists in any type, it's promoted to the
     * top of the result list so the most specific hit is visible
     * without scrolling.
     */
    fun searchWaypoints(query: String, limit: Int = 30): List<WaypointSearchResult> {
        val trimmed = query.trim()
        if (trimmed.length < 2) return emptyList()

        // Split the budget so each waypoint type always has room to
        // appear. Hard limits keep the total result list scannable.
        val perTypeLimit = (limit / 3).coerceAtLeast(8)
        val airports = searchAirports(trimmed, perTypeLimit)
        val navaids = searchNavaids(trimmed, perTypeLimit)
        val fixes = searchFixes(trimmed, perTypeLimit)

        val combined = airports + navaids + fixes
        // Float exact-code matches to the top across all types so a
        // query for "UMBER" surfaces the UMBER fix above any partial
        // airport-name matches.
        val (exact, rest) = combined.partition { it.code.equals(trimmed, ignoreCase = true) }
        return exact + rest
    }

    private fun searchAirports(query: String, limit: Int): List<WaypointSearchResult> {
        val prefix = "$query%"
        val contains = "%$query%"
        // Ranking is tuned so a query that is a city name surfaces airports
        // IN that city first (e.g. "mesa" → KFFZ Falcon Field), instead of
        // unrelated airports that happen to have the word in their name
        // (Mesa Verde, Mesa Police Heliport, etc).
        //
        // Within a priority group, airports with an ICAO ID — typically
        // public-use towered fields — appear before FAA-only IDs (small
        // private strips, heliports), which is almost always what the user
        // wants when scanning a list of "airports near here".
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
                WHEN CITY = ? COLLATE NOCASE THEN 2
                WHEN ICAO_ID LIKE ? COLLATE NOCASE THEN 3
                WHEN ARPT_ID LIKE ? COLLATE NOCASE THEN 4
                WHEN ARPT_NAME LIKE ? COLLATE NOCASE THEN 5
                WHEN CITY LIKE ? COLLATE NOCASE THEN 6
                ELSE 7
              END,
              CASE WHEN ICAO_ID IS NOT NULL AND ICAO_ID != '' THEN 0 ELSE 1 END,
              ARPT_NAME
            LIMIT $limit
        """.trimIndent()
        val args = arrayOf(
            prefix, prefix, contains, contains,
            query, query, query, prefix, prefix, prefix, prefix,
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

    // NAV_BASE column names vary by FAA NASR build (NAV_NAME vs NAME vs others).
    // Detect once on first use; cache the answer.
    private var navColumns: Set<String>? = null
    private fun navBaseColumns(): Set<String> {
        navColumns?.let { return it }
        val cols = mutableSetOf<String>()
        try {
            readableDatabase.rawQuery("PRAGMA table_info(NAV_BASE)", null).use { cursor ->
                while (cursor.moveToNext()) {
                    cols += cursor.getString(1).uppercase()
                }
            }
        } catch (e: Exception) {
            Log.w("WaypointSearch", "Could not introspect NAV_BASE: ${e.message}")
        }
        Log.d("WaypointSearch", "NAV_BASE columns: $cols")
        navColumns = cols
        return cols
    }

    private fun searchNavaids(query: String, limit: Int): List<WaypointSearchResult> {
        val prefix = "$query%"
        val contains = "%$query%"
        val cols = navBaseColumns()

        // Pick the first column from each candidate set that actually exists in
        // this build of the DB. NASR sometimes ships "NAV_NAME", sometimes "NAME";
        // city/state column names also vary.
        val nameCol = listOf("NAV_NAME", "NAME", "NAVAID_NAME").firstOrNull { it in cols }
        val cityCol = listOf("CITY", "ASSOC_CITY").firstOrNull { it in cols }
        val stateCol = listOf("STATE_CODE", "STATE", "STATE_NAME").firstOrNull { it in cols }

        // Build the WHERE clause dynamically based on which columns we have. NAV_ID
        // is always present (we use it elsewhere in this class for lookupNavaid).
        val whereParts = mutableListOf("NAV_ID LIKE ? COLLATE NOCASE")
        val whereArgs = mutableListOf<String>(prefix)
        if (nameCol != null) {
            whereParts += "$nameCol LIKE ? COLLATE NOCASE"
            whereArgs += contains
        }
        if (cityCol != null) {
            whereParts += "$cityCol LIKE ? COLLATE NOCASE"
            whereArgs += contains
        }
        val whereClause = whereParts.joinToString(" OR ")

        // SELECT list always includes NAV_ID; name and city/state are NULL if absent.
        val selectName = nameCol ?: "NULL"
        val selectCity = cityCol ?: "NULL"
        val selectState = stateCol ?: "NULL"

        // Ranking: exact ID → ID prefix → name prefix → city prefix → contains.
        val orderParts = mutableListOf<String>(
            "WHEN NAV_ID = ? COLLATE NOCASE THEN 0",
            "WHEN NAV_ID LIKE ? COLLATE NOCASE THEN 1",
        )
        val orderArgs = mutableListOf<String>(query, prefix)
        if (nameCol != null) {
            orderParts += "WHEN $nameCol LIKE ? COLLATE NOCASE THEN 2"
            orderArgs += prefix
        }
        if (cityCol != null) {
            orderParts += "WHEN $cityCol LIKE ? COLLATE NOCASE THEN 3"
            orderArgs += prefix
        }
        val orderBy = "CASE ${orderParts.joinToString(" ")} ELSE 4 END, ${nameCol ?: "NAV_ID"}"

        val sql = """
            SELECT NAV_ID, $selectName, $selectCity, $selectState
            FROM NAV_BASE
            WHERE $whereClause
            ORDER BY $orderBy
            LIMIT $limit
        """.trimIndent()

        val results = mutableListOf<WaypointSearchResult>()
        try {
            readableDatabase.rawQuery(sql, (whereArgs + orderArgs).toTypedArray()).use { cursor ->
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
            Log.w("WaypointSearch", "Navaid search failed: ${e.message}")
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

    /**
     * Returns a map of airport identifier (both ICAO and FAA forms) to a
     * size/airspace tier:
     *  - 1 = mega-hub (FAR 139 ARFF Index D or E — handles wide-body jets,
     *        almost always Class B airspace: KLAX, KORD, KATL, etc.)
     *  - 2 = regional hub (ARFF Index C — handles narrow-body jets, usually
     *        Class C: KOMA, KAUS, KSAT, etc.)
     *  - 3 = smaller commercial or Class D towered field (ARFF Index A/B,
     *        or any airport with a control tower but no FAR 139 cert)
     *  - 4 is the implicit default for airports NOT in this map — small
     *    GA fields, private strips, heliports.
     *
     * Used by the map's zoom-tier visibility filter so Class B/C hubs stay
     * visible at continental zoom, Class D appears at regional zoom, etc.
     *
     * The result set is small (~1500 rows for US data) so caching the
     * whole thing once at startup is cheaper than per-airport lookups.
     */
    fun getAirportTiers(): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        val sql = """
            SELECT ICAO_ID, ARPT_ID, FAR_139_TYPE_CODE, TWR_TYPE_CODE
            FROM APT_BASE
            WHERE (FAR_139_TYPE_CODE IS NOT NULL AND FAR_139_TYPE_CODE != '')
               OR (TWR_TYPE_CODE     IS NOT NULL AND TWR_TYPE_CODE     != '')
        """.trimIndent()
        try {
            readableDatabase.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val icao = cursor.getStringOrNull(0)
                    val arptId = cursor.getStringOrNull(1)
                    val far139 = cursor.getStringOrNull(2)
                    val twr = cursor.getStringOrNull(3)
                    val tier = parseAirportTier(far139, twr)
                    if (!icao.isNullOrBlank()) out[icao.uppercase()] = tier
                    if (!arptId.isNullOrBlank()) out[arptId.uppercase()] = tier
                }
            }
        } catch (e: Exception) {
            Log.w("AirportTier", "tier lookup failed: ${e.message}")
        }
        return out
    }

    /** ARFF Index char from FAR_139_TYPE_CODE ("I E" → 'E'); empty → null. */
    private fun parseAirportTier(far139: String?, twr: String?): Int {
        val arffIdx = far139
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.getOrNull(1)
            ?.firstOrNull()
            ?.uppercaseChar()
        return when {
            arffIdx == 'D' || arffIdx == 'E' -> 1
            arffIdx == 'C' -> 2
            arffIdx == 'A' || arffIdx == 'B' -> 3
            !twr.isNullOrBlank() -> 3   // towered but no FAR 139 → Class D
            else -> 4
        }
    }

    /**
     * Find the nearest airport to ([lat], [lon]) within [maxNm] nautical
     * miles. Used by the map's long-press flow to let pilots add airports
     * to the flight plan even when there's no METAR marker there.
     *
     * Filters by a rough lat/lon bounding box first (cheap, uses an index
     * if one exists on LAT_DECIMAL), then sorts the candidates by true
     * great-circle distance and returns the closest match.
     */
    fun findNearestAirport(lat: Double, lon: Double, maxNm: Double = 2.0): WaypointSearchResult? {
        // 1° of latitude ≈ 60 nm. 1° of longitude varies with cosine of
        // latitude but at typical CONUS lats it's ~50 nm — use a slightly
        // generous box and re-rank by exact distance below.
        val latDelta = maxNm / 60.0
        val lonDelta = maxNm / (60.0 * Math.cos(Math.toRadians(lat))).coerceAtLeast(1e-6)
        val sql = """
            SELECT ARPT_ID, ICAO_ID, ARPT_NAME, CITY, STATE_NAME, LAT_DECIMAL, LONG_DECIMAL
            FROM APT_BASE
            WHERE LAT_DECIMAL BETWEEN ? AND ?
              AND LONG_DECIMAL BETWEEN ? AND ?
        """.trimIndent()
        val args = arrayOf(
            (lat - latDelta).toString(),
            (lat + latDelta).toString(),
            (lon - lonDelta).toString(),
            (lon + lonDelta).toString(),
        )

        var best: WaypointSearchResult? = null
        var bestDistSq = Double.MAX_VALUE
        try {
            readableDatabase.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    val rowLat = cursor.getDouble(5)
                    val rowLon = cursor.getDouble(6)
                    // Pythagorean approximation in degrees is fine for
                    // ranking inside a small box — no need for haversine.
                    val dLat = rowLat - lat
                    val dLon = (rowLon - lon) * Math.cos(Math.toRadians(lat))
                    val distSq = dLat * dLat + dLon * dLon
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq
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
                        best = WaypointSearchResult(code, "AIRPORT", name, location)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("WaypointSearch", "Nearest airport lookup failed: ${e.message}")
        }
        return best
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