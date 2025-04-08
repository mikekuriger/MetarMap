package com.airportweather.map

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

fun saveMetarDataToCache(metars: List<METAR>, filesDir: File) {
    val file = File(filesDir, "metars_cache.json")
    try {
        val json = Gson().toJson(metars)
        file.writeText(json)
        Log.d("METAR_CACHE", "Saved ${metars.size} METARs to cache.")
    } catch (e: Exception) {
        Log.e("METAR_CACHE", "Failed to save METAR cache.", e)
    }
}

fun loadMetarDataFromCache(filesDir: File): List<METAR> {
    val file = File(filesDir, "metars_cache.json")
    if (!file.exists()) return emptyList()

    return try {
        val json = file.readText()
        val type = object : TypeToken<List<METAR>>() {}.type
        val cachedMetars: List<METAR> = Gson().fromJson(json, type)
        Log.d("METAR_CACHE", "Loaded ${cachedMetars.size} METARs from cache.")
        cachedMetars
    } catch (e: Exception) {
        Log.e("METAR_CACHE", "Failed to load METAR cache.", e)
        emptyList()
    }
}

fun saveTafDataToCache(tafs: List<TAF>, filesDir: File) {
    val file = File(filesDir, "tafs_cache.json")
    try {
        val json = Gson().toJson(tafs)
        file.writeText(json)
        Log.d("TAF_CACHE", "Saved ${tafs.size} TAFs to cache.")
    } catch (e: Exception) {
        Log.e("TAF_CACHE", "Failed to save TAF cache.", e)
    }
}

fun loadTafDataFromCache(filesDir: File): List<TAF> {
    val file = File(filesDir, "tafs_cache.json")
    if (!file.exists()) return emptyList()

    return try {
        val json = file.readText()
        val type = object : TypeToken<List<TAF>>() {}.type
        val cachedTafs: List<TAF> = Gson().fromJson(json, type)
        Log.d("TAF_CACHE", "Loaded ${cachedTafs.size} TAFs from cache.")
        cachedTafs
    } catch (e: Exception) {
        Log.e("TAF_CACHE", "Failed to load TAF cache.", e)
        emptyList()
    }
}

