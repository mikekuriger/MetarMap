package com.airportweather.map

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Owns Special Use Airspace download, parse, and cache. Mirrors [TfrRepository]
 * but with a much longer cache TTL — SUA changes on the FAA's ~28-day NASR cycle,
 * not in real time.
 */
class SuaRepository(
    private val filesDir: File,
    private val maxAgeMillis: Long = TimeUnit.DAYS.toMillis(7),
    private val sourceUrl: String = Endpoints.SUA_GEOJSON,
) {

    private val _sua = MutableStateFlow<List<SuaFeature>>(emptyList())
    val sua: StateFlow<List<SuaFeature>> = _sua.asStateFlow()

    /**
     * Loads cached SUA into state if a fresh-enough cache exists, otherwise
     * downloads from the FAA and caches the result. Returns true if any data
     * was published to [sua].
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val file = getOrDownload() ?: return@withContext false
        _sua.value = parseGeoJson(file)
        true
    }

    private suspend fun getOrDownload(): File? {
        val cacheDir = File(filesDir, "geojson").apply { mkdirs() }
        val suaFile = File(cacheDir, "sua.geojson")

        if (suaFile.exists() && suaFile.length() > 0 &&
            System.currentTimeMillis() - suaFile.lastModified() < maxAgeMillis
        ) {
            Log.d("SUA", "Using cached SUA GeoJSON: ${suaFile.length()} bytes")
            return suaFile
        }

        return try {
            Log.d("SUA", "Downloading new SUA GeoJSON")
            download(cacheDir)
        } catch (e: IOException) {
            Log.w("SUA", "Failed to download SUA GeoJSON: ${e.message}")
            if (suaFile.exists() && suaFile.length() > 0) {
                Log.d("SUA", "Falling back to last cached version")
                suaFile
            } else {
                null
            }
        }
    }

    private suspend fun download(targetDir: File): File = withContext(Dispatchers.IO) {
        try {
            val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: ${connection.responseCode}")
            }

            val inputStream: InputStream = connection.inputStream
            val outputFile = File(targetDir, "sua.geojson")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            Log.d("SUA", "Downloaded ${outputFile.length()} bytes")
            outputFile
        } catch (e: Exception) {
            throw IOException("Error downloading SUA GeoJSON: ${e.message}", e)
        }
    }

    private fun parseGeoJson(file: File): List<SuaFeature> {
        val out = mutableListOf<SuaFeature>()
        try {
            val text = file.bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val features = root.getJSONArray("features")

            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val type = geometry.getString("type")
                if (type != "Polygon") continue  // FAA SUA is currently Polygon-only

                val rings = geometry.getJSONArray("coordinates")
                val parsedRings = mutableListOf<List<List<Double>>>()
                for (j in 0 until rings.length()) {
                    val ring = rings.getJSONArray(j)
                    val parsedRing = mutableListOf<List<Double>>()
                    for (k in 0 until ring.length()) {
                        val point = ring.getJSONArray(k)
                        parsedRing += listOf(point.getDouble(0), point.getDouble(1))
                    }
                    parsedRings += parsedRing
                }

                val props = feature.optJSONObject("properties") ?: JSONObject()
                out += SuaFeature(
                    properties = SuaProperties(
                        name = props.optString("NAME", "Unknown"),
                        typeCode = props.optString("TYPE_CODE", "?"),
                        upperDesc = props.optStringOrNull("UPPER_DESC"),
                        lowerDesc = props.optStringOrNull("LOWER_DESC"),
                        timesOfUse = props.optStringOrNull("TIMESOFUSE"),
                        controllingAgent = props.optStringOrNull("CONT_AGENT"),
                    ),
                    coordinates = parsedRings,
                )
            }
        } catch (e: Exception) {
            Log.e("SUA", "Error parsing SUA GeoJSON: ${e.message}", e)
        }
        return out
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null
}
