package com.airportweather.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

//class AirportDatabaseHelper(context: Context) :
//    SQLiteOpenHelper(context, File(context.filesDir, "faa_airports.db").absolutePath, null, 1) {

class AirportDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, "faa_airports.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase?) {} // Not needed if you're using a prebuilt DB
    override fun onUpgrade(db: SQLiteDatabase?, oldVersion: Int, newVersion: Int) {}

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
            "SELECT ARPT_NAME, CITY, STATE_NAME, LAT_DECIMAL, LONG_DECIMAL, ELEV, MAG_VARN, ICAO_ID, ARPT_ID FROM APT_BASE WHERE ARPT_ID = ? OR ICAO_ID = ? COLLATE NOCASE",
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
            val icaoId = cursor.getString(7)
            val arptId = cursor.getString(8)

            val magVar = magVarRaw  // assume already signed (East = +, West = -) in DB

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

}