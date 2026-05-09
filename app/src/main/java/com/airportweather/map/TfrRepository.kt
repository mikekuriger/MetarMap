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
 * Owns TFR GeoJSON download, parse, and cache. The cache is honored for [maxAgeMillis]
 * so map navigation doesn't trigger a network call every time.
 */
class TfrRepository(
    private val filesDir: File,
    private val maxAgeMillis: Long = TimeUnit.MINUTES.toMillis(30),
    private val sourceUrl: String = Endpoints.TFR_GEOJSON,
) {

    private val _tfrs = MutableStateFlow<List<TFRFeature>>(emptyList())
    val tfrs: StateFlow<List<TFRFeature>> = _tfrs.asStateFlow()

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val file = getOrDownload() ?: return@withContext false
        _tfrs.value = parseGeoJson(file)
        true
    }

    private suspend fun getOrDownload(): File? {
        val cacheDir = File(filesDir, "geojson").apply { mkdirs() }
        val tfrFile = File(cacheDir, "tfrs.geojson")

        if (tfrFile.exists() && tfrFile.length() > 0 &&
            System.currentTimeMillis() - tfrFile.lastModified() < maxAgeMillis
        ) {
            Log.d("TFR", "Using cached TFR GeoJSON: ${tfrFile.length()} bytes")
            return tfrFile
        }

        return try {
            Log.d("TFR", "Downloading new TFR GeoJSON")
            download(cacheDir)
        } catch (e: IOException) {
            Log.w("TFR", "Failed to download TFR GeoJSON: ${e.message}")
            if (tfrFile.exists() && tfrFile.length() > 0) {
                Log.d("TFR", "Falling back to last cached version")
                tfrFile
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
                readTimeout = 30_000
            }
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: ${connection.responseCode}")
            }

            val inputStream: InputStream = connection.inputStream
            val outputFile = File(targetDir, "tfrs.geojson")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            Log.d("TFR", "Downloaded ${outputFile.length()} bytes")
            outputFile
        } catch (e: Exception) {
            throw IOException("Error downloading TFR GeoJSON: ${e.message}", e)
        }
    }

    private fun parseGeoJson(file: File): List<TFRFeature> {
        val features = mutableListOf<TFRFeature>()
        try {
            val geoJsonString = file.bufferedReader().use { it.readText() }
            val root = JSONObject(geoJsonString)
            val featuresArray = root.getJSONArray("features")

            for (i in 0 until featuresArray.length()) {
                val feature = featuresArray.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val properties = feature.getJSONObject("properties")
                val type = geometry.getString("type")
                if (type != "Polygon") continue

                val coordinatesJson = geometry.getJSONArray("coordinates")
                val parsedCoordinates = mutableListOf<List<List<Double>>>()
                for (j in 0 until coordinatesJson.length()) {
                    val ring = coordinatesJson.getJSONArray(j)
                    val parsedRing = mutableListOf<List<Double>>()
                    for (k in 0 until ring.length()) {
                        val point = ring.getJSONArray(k)
                        parsedRing.add(listOf(point.getDouble(0), point.getDouble(1)))
                    }
                    parsedCoordinates.add(parsedRing)
                }

                val geometryObj = TFRGeometry(type, parsedCoordinates)
                val tfrProperties = TFRProperties(
                    description = properties.optString("description", "No description available"),
                    notam = properties.optString("notam", "Unknown"),
                    dateIssued = properties.optString("dateIssued", "Unknown"),
                    dateEffective = properties.optString("dateEffective", "Unknown"),
                    dateExpire = properties.optString("dateExpire", "Ongoing"),
                    type = properties.optString("type", "Unknown"),
                    altitudeMin = properties.optString("lowerVal", "Surface"),
                    altitudeMax = properties.optString("upperVal", "Unlimited"),
                    facility = properties.optString("facility", "Unknown"),
                )
                features.add(TFRFeature(tfrProperties, geometryObj))
            }
        } catch (e: Exception) {
            Log.e("TFR", "Error parsing TFR GeoJSON: ${e.message}", e)
        }
        return features
    }
}
