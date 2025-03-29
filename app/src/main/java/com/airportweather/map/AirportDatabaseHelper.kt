package com.airportweather.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

class AirportDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "faa_airports.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase?) {} // Not needed if you're using a prebuilt DB
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}

    // Airport stuff
    fun airportExists(code: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM APT_BASE WHERE ARPT_ID = ? COLLATE NOCASE",
            arrayOf(code)
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }
    fun airportPrefixExists(prefix: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM APT_BASE WHERE ARPT_ID LIKE ? COLLATE NOCASE LIMIT 1",
            arrayOf("$prefix%")
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }
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