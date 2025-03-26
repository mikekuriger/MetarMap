package com.airportweather.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File


class AirportDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, File(context.filesDir, "faa_airports.db").absolutePath, null, 1) {

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
}