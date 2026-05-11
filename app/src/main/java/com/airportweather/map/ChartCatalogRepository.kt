package com.airportweather.map

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Owns download + parse of all_charts.json. Cache TTL is 24h — the FAA cycle is
 * 28 days, so checking once a day is plenty and keeps mobile data low. Falls back
 * to the last cached copy on network failure.
 */
class ChartCatalogRepository(
    private val filesDir: File,
    private val maxAgeMillis: Long = TimeUnit.HOURS.toMillis(24),
    private val sourceUrl: String = Endpoints.CHARTS_CATALOG,
) {

    private val _catalog = MutableStateFlow<ChartCatalog?>(null)
    val catalog: StateFlow<ChartCatalog?> = _catalog.asStateFlow()

    /**
     * Loads the cached catalog if fresh, otherwise fetches it. Returns true if
     * [catalog] was populated.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val file = getOrDownload() ?: return@withContext false
        parseCatalog(file)?.also { _catalog.value = it } != null
    }

    /** Force a fresh download regardless of cache age. */
    suspend fun forceRefresh(): Boolean = withContext(Dispatchers.IO) {
        val cacheDir = File(filesDir, "charts").apply { mkdirs() }
        val file = try {
            download(cacheDir)
        } catch (e: IOException) {
            Log.w("ChartCatalog", "Force refresh failed: ${e.message}")
            return@withContext false
        }
        parseCatalog(file)?.also { _catalog.value = it } != null
    }

    private fun getOrDownload(): File? {
        val cacheDir = File(filesDir, "charts").apply { mkdirs() }
        val catFile = File(cacheDir, "all_charts.json")

        if (catFile.exists() && catFile.length() > 0 &&
            System.currentTimeMillis() - catFile.lastModified() < maxAgeMillis
        ) {
            Log.d("ChartCatalog", "Using cached catalog: ${catFile.length()} bytes")
            return catFile
        }

        return try {
            Log.d("ChartCatalog", "Downloading chart catalog")
            download(cacheDir)
        } catch (e: IOException) {
            Log.w("ChartCatalog", "Catalog download failed: ${e.message}")
            if (catFile.exists() && catFile.length() > 0) catFile else null
        }
    }

    private fun download(targetDir: File): File {
        val conn = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        conn.connect()
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("HTTP ${conn.responseCode}")
        }
        val out = File(targetDir, "all_charts.json")
        conn.inputStream.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        Log.d("ChartCatalog", "Downloaded ${out.length()} bytes")
        return out
    }

    private fun parseCatalog(file: File): ChartCatalog? {
        return try {
            val root = JSONObject(file.bufferedReader().use { it.readText() })
            // all_charts.json gives us zip URLs/sizes (authoritative). The per-section
            // metadata.json on the tile server carries the up-to-date cycle dates
            // — overlay those on top because all_charts.json's "series" drifts.
            val sectionalMeta = fetchMetadata(Endpoints.SECTIONAL_METADATA)
            val terminalMeta = fetchMetadata(Endpoints.TERMINAL_METADATA)
            val enrouteMeta = fetchMetadata(Endpoints.ENROUTE_LOW_METADATA)

            ChartCatalog(
                sectional = parseSection(root.getJSONObject("Sectional"), sectionalMeta),
                terminal = parseSection(root.getJSONObject("Terminal"), terminalMeta),
                enroute = parseSection(root.getJSONObject("Enroute_Low"), enrouteMeta),
            )
        } catch (e: Exception) {
            Log.e("ChartCatalog", "Error parsing catalog: ${e.message}", e)
            null
        }
    }

    /**
     * Fetches a per-section metadata.json — small (~300 bytes) and cached on
     * disk alongside all_charts.json with the same TTL. Returns null on failure;
     * callers fall back to the (possibly stale) series field in all_charts.json.
     */
    private fun fetchMetadata(url: String): JSONObject? {
        val cacheDir = File(filesDir, "charts").apply { mkdirs() }
        // Derive a cache filename from the URL's last two path segments
        // (e.g. "Sectional_metadata.json"). Avoids URL escaping in the name.
        val key = url.substringAfterLast("//").substringAfter("/")
            .replace('/', '_').take(80)
        val cacheFile = File(cacheDir, key)

        val cacheFresh = cacheFile.exists() && cacheFile.length() > 0 &&
            System.currentTimeMillis() - cacheFile.lastModified() < maxAgeMillis

        if (!cacheFresh) {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                }
                conn.connect()
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { input ->
                        cacheFile.outputStream().use { input.copyTo(it) }
                    }
                } else {
                    Log.w("ChartCatalog", "metadata HTTP ${conn.responseCode} for $url")
                }
            } catch (e: IOException) {
                Log.w("ChartCatalog", "metadata fetch failed: ${e.message}")
            }
        }

        if (!cacheFile.exists() || cacheFile.length() == 0L) return null
        return try {
            JSONObject(cacheFile.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            Log.w("ChartCatalog", "metadata parse failed: ${e.message}")
            null
        }
    }

    private fun parseSection(obj: JSONObject, metadata: JSONObject?): ChartSection {
        // Prefer metadata.json's "valid" — that file is updated; all_charts.json
        // sometimes carries a stale series date.
        val fallbackSeries = obj.optString("series", "unknown")
        val series = metadata?.optString("valid")?.takeIf { it.isNotBlank() }
            ?: fallbackSeries
        val expires = metadata?.optString("expires")?.takeIf { it.isNotBlank() }
        val arr = obj.getJSONArray("charts")
        val charts = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ChartEntry(
                name = o.getString("name"),
                fileName = o.getString("fileName"),
                url = o.getString("url"),
                size = o.optString("size", "0 MB"),
            )
        }
        return ChartSection(series, expires, charts)
    }
}
