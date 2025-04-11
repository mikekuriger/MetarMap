package com.airportweather.map.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.airportweather.map.Waypoint

class AirportDatabaseHelper(context: Context) :
//    SQLiteOpenHelper(context, "faa_airports.db", null, 1) {
    SQLiteOpenHelper(context, "faa_navigation.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase?) {} // Not needed if you're using a prebuilt DB
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}

    // New waypoint lookup
    fun lookupWaypoint(code: String): Waypoint? {
        val upperCode = code.uppercase()

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
            "SELECT LAT_DECIMAL, LONG_DECIMAL, ELEV FROM APT_BASE WHERE ICAO_ID = ? COLLATE NOCASE OR ARPT_ID = ? COLLATE NOCASE",
            arrayOf(code, code)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val lat = cursor.getFloat(0)
                val lon = cursor.getFloat(1)
                val elev = cursor.getFloat(2)
                return Waypoint(code, "AIRPORT", lat, lon, elev)
            }
        }
        return null
    }

    private fun lookupNavaid(code: String): Waypoint? {
        val db = readableDatabase
        db.rawQuery(
            "SELECT LAT_DECIMAL, LONG_DECIMAL FROM NAV_BASE WHERE NAV_ID = ? COLLATE NOCASE",
            arrayOf(code)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val lat = cursor.getFloat(0)
                val lon = cursor.getFloat(1)
                return Waypoint(code, "NAVAID", lat, lon)
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
                val lat = cursor.getFloat(0)
                val lon = cursor.getFloat(1)
                return Waypoint(code, "FIX", lat, lon)
            }
        }
        return null
    }

//old
//    fun lookupWaypoint(code: String): Waypoint? {
//        val db = readableDatabase
//
//        // Airport: ICAO_ID or ARPT_ID
//        db.rawQuery(
//            "SELECT LAT_DECIMAL, LONG_DECIMAL, ELEV FROM APT_BASE WHERE ICAO_ID = ? COLLATE NOCASE OR ARPT_ID = ? COLLATE NOCASE",
//            arrayOf(code, code)
//        ).use { cursor ->
//            if (cursor.moveToFirst()) {
//                val lat = cursor.getFloat(0)
//                val lon = cursor.getFloat(1)
//                val elev = cursor.getFloat(2)
//                return Waypoint(code, "AIRPORT", lat, lon, elev)
//            }
//        }
//
//        // Navaid: NAV_ID
//        db.rawQuery(
//            "SELECT LAT_DECIMAL, LONG_DECIMAL FROM NAV_BASE WHERE NAV_ID = ? COLLATE NOCASE",
//            arrayOf(code)
//        ).use { cursor ->
//            if (cursor.moveToFirst()) {
//                val lat = cursor.getFloat(0)
//                val lon = cursor.getFloat(1)
//                return Waypoint(code, "NAVAID", lat, lon)
//            }
//        }
//
//        // Fix: FIX_ID
//        db.rawQuery(
//            "SELECT LAT_DECIMAL, LONG_DECIMAL FROM FIX_BASE WHERE FIX_ID = ? COLLATE NOCASE",
//            arrayOf(code)
//        ).use { cursor ->
//            if (cursor.moveToFirst()) {
//                val lat = cursor.getFloat(0)
//                val lon = cursor.getFloat(1)
//                return Waypoint(code, "FIX", lat, lon)
//            }
//        }
//
//        return null
//    }

//    fun lookupWaypoint(code: String): Waypoint? {
//        val db = readableDatabase
//        Triple("APT_BASE", "AIRPORT", "SELECT LAT_DECIMAL, LONG_DECIMAL, ELEV FROM APT_BASE WHERE ICAO_ID = ? OR ARPT_ID = ? COLLATE NOCASE"),
//        Triple("NAV_BASE", "NAVAID", "SELECT LAT_DECIMAL, LONG_DECIMAL FROM NAV_BASE WHERE NAV_ID = ? COLLATE NOCASE"),
//        Triple("FIX_BASE", "FIX", "SELECT LAT_DECIMAL, LONG_DECIMAL FROM FIX_BASE WHERE FIX_ID = ? COLLATE NOCASE")
//        val queries = listOf(
//
//        )
//
//        for ((_, type, sql) in queries) {
//            val cursor = db.rawQuery(sql, arrayOf(code, code))
//            if (cursor.moveToFirst()) {
//                val lat = cursor.getFloat(0)
//                val lon = cursor.getFloat(1)
//                val elev = if (cursor.columnCount > 2) cursor.getFloat(2) else 0f
//                cursor.close()
//                return Waypoint(code, type, lat, lon, elev)
//            }
//            cursor.close()
//        }
//        return null
//    }

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
        val cursor = db.rawQuery(
            """
            SELECT RWY_ID, RWY_END_ID, TRUE_ALIGNMENT, LAT_DECIMAL, LONG_DECIMAL, RIGHT_HAND_TRAFFIC_PAT_FLAG
            FROM APT_RWY_END
            WHERE ARPT_ID = ? COLLATE NOCASE
        """.trimIndent(), arrayOf(arptId)
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