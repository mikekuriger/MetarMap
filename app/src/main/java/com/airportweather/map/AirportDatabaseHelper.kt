package com.airportweather.map

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File


//class AirportDatabaseHelper(context: Context) : SQLiteOpenHelper(context, "faa_airports.db", null, 1) {
class AirportDatabaseHelper(private val context: Context) :
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

/*    fun airportExists(code: String): Boolean {
        val db = readableDatabase
        val variants = if (code.length == 4 && code.startsWith("K")) {
            listOf(code, code.drop(1)) // e.g., KBUR, BUR
        } else {
            listOf(code)
        }

        for (variant in variants) {
            val cursor = db.rawQuery(
                "SELECT 1 FROM APT_BASE WHERE ARPT_ID = ? COLLATE NOCASE",
                arrayOf(variant)
            )
            if (cursor.moveToFirst()) {
                cursor.close()
                return true
            }
            cursor.close()
        }

        return false
    }*/

/*    fun airportPrefixExists(prefix: String): Boolean {
        val db = readableDatabase
        val variants = if (prefix.length >= 2 && prefix.startsWith("K")) {
            listOf(prefix, prefix.drop(1)) // e.g., KB, B
        } else {
            listOf(prefix)
        }

        for (variant in variants) {
            val cursor = db.rawQuery(
                "SELECT 1 FROM APT_BASE WHERE ARPT_ID LIKE ? COLLATE NOCASE LIMIT 1",
                arrayOf("$variant%")
            )
            if (cursor.moveToFirst()) {
                cursor.close()
                return true
            }
            cursor.close()
        }

        return false
    }*/


    fun isExactAirport(code: String): Boolean {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT 1 FROM APT_BASE WHERE ARPT_ID = ? COLLATE NOCASE",
            arrayOf(code)
        )
        val exists = cursor.moveToFirst()
        cursor.close()
        return exists
    }

    fun isPrefixValid(prefix: String): Boolean {
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