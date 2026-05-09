package com.airportweather.map

import android.util.Log
import com.airportweather.map.utils.loadMetarDataFromCache
import com.airportweather.map.utils.loadTafDataFromCache
import com.airportweather.map.utils.saveMetarDataToCache
import com.airportweather.map.utils.saveTafDataToCache
import com.opencsv.CSVReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.util.zip.GZIPInputStream

data class WeatherSnapshot(val metars: List<METAR>, val tafs: List<TAF>) {
    companion object {
        val EMPTY = WeatherSnapshot(emptyList(), emptyList())
    }
}

/**
 * Owns METAR + TAF download, parse, merge, and cache. Exposes the latest known data
 * via a single [snapshot] StateFlow so subscribers see one emission per refresh
 * (avoids the "flicker" of two rapid emissions when both lists update together).
 *
 * Both [loadCached] and [refresh] are safe to call from any coroutine; they marshal
 * their work to the IO dispatcher.
 */
class WeatherRepository(private val filesDir: File) {

    private val _snapshot = MutableStateFlow(WeatherSnapshot.EMPTY)
    val snapshot: StateFlow<WeatherSnapshot> = _snapshot.asStateFlow()

    val metars: List<METAR> get() = _snapshot.value.metars
    val tafs: List<TAF> get() = _snapshot.value.tafs

    suspend fun loadCached() = withContext(Dispatchers.IO) {
        _snapshot.value = WeatherSnapshot(
            metars = loadMetarDataFromCache(filesDir),
            tafs = loadTafDataFromCache(filesDir),
        )
    }

    /**
     * Downloads fresh METAR + TAF data, merges with whatever is already in state
     * (or cache, if state is empty), saves the merged result back to disk, and
     * publishes both lists in a single atomic snapshot update.
     */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val current = _snapshot.value
        val baseMetars = current.metars.ifEmpty { loadMetarDataFromCache(filesDir) }
        val baseTafs = current.tafs.ifEmpty { loadTafDataFromCache(filesDir) }

        val freshMetars = downloadMetars()
        val freshTafs = downloadTafs()

        val mergedMetars = merge(baseMetars, freshMetars) { it.stationId }
        val mergedTafs = merge(baseTafs, freshTafs) { it.stationId }

        saveMetarDataToCache(mergedMetars, filesDir)
        saveTafDataToCache(mergedTafs, filesDir)

        _snapshot.value = WeatherSnapshot(mergedMetars, mergedTafs)
    }

    private fun downloadMetars(): List<METAR> {
        return try {
            val url = URL("https://aviationweather.gov/data/cache/metars.cache.csv.gz")
            val connection = url.openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            val inputStream: InputStream = GZIPInputStream(connection.getInputStream())
            val outputFile = File(filesDir, "metars.cache.csv")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw Exception("Downloaded METAR file is empty or missing")
            }
            Log.d("METAR_DOWNLOAD", "Downloaded ${outputFile.length()} bytes")
            parseMetarCsv(outputFile)
        } catch (e: Exception) {
            Log.e("METAR_DOWNLOAD", "Error downloading METAR data: ${e.message}", e)
            emptyList()
        }
    }

    private fun downloadTafs(): List<TAF> {
        return try {
            val url = URL("https://aviationweather.gov/data/cache/tafs.cache.csv.gz")
            val connection = url.openConnection().apply {
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            val inputStream: InputStream = GZIPInputStream(connection.getInputStream())
            val outputFile = File(filesDir, "tafs.cache.csv")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw Exception("Downloaded TAF file is empty or missing")
            }
            Log.d("TAF_DOWNLOAD", "Downloaded ${outputFile.length()} bytes")
            parseTafCsv(outputFile)
        } catch (e: Exception) {
            Log.e("TAF_DOWNLOAD", "Error downloading TAF data: ${e.message}", e)
            emptyList()
        }
    }

    // RFC-4180 CSV parsing via opencsv: handles commas inside quoted raw_text
    // (METAR remarks like "CB N,W TEMPO ..." used to silently drop those rows
    // because line.split(",") shifted every subsequent column).
    private fun parseMetarCsv(file: File): List<METAR> {
        val out = mutableListOf<METAR>()
        file.bufferedReader().use { reader ->
            CSVReader(reader).use { csv ->
                csv.readNext() // discard header row
                while (true) {
                    val fields = try {
                        csv.readNext() ?: break
                    } catch (e: Exception) {
                        Log.w("METAR_PARSE", "CSV read error, stopping", e)
                        break
                    }
                    parseMetarRow(fields)?.let { out += it }
                }
            }
        }
        return out
    }

    private fun parseMetarRow(fields: Array<String>): METAR? {
        // Need 44 columns to safely access fields[43] (elevation_m). Was < 43,
        // which let rows with exactly 43 fields throw IndexOutOfBoundsException
        // and get silently dropped by the catch below.
        if (fields.size < 44) {
            Log.w("METAR_PARSE", "Skipping row, only ${fields.size} fields")
            return null
        }
        return try {
            METAR(
                stationId = fields[1],
                observationTime = fields[2],
                latitude = fields[3].toDouble(),
                longitude = fields[4].toDouble(),
                tempC = fields[5].toDoubleOrNull(),
                dewpointC = fields[6].toDoubleOrNull(),
                windDirDegrees = fields[7].toIntOrNull(),
                windSpeedKt = fields[8].toIntOrNull(),
                windGustKt = fields[9].toIntOrNull(),
                visibility = fields[10],
                altimeterInHg = fields[11].toDoubleOrNull(),
                wxString = fields[21],
                skyCover1 = fields.getOrNull(22),
                cloudBase1 = fields.getOrNull(23)?.toIntOrNull(),
                skyCover2 = fields.getOrNull(24),
                cloudBase2 = fields.getOrNull(25)?.toIntOrNull(),
                skyCover3 = fields.getOrNull(26),
                cloudBase3 = fields.getOrNull(27)?.toIntOrNull(),
                skyCover4 = fields.getOrNull(28),
                cloudBase4 = fields.getOrNull(29)?.toIntOrNull(),
                flightCategory = fields[30],
                metarType = fields[42],
                elevationM = fields[43].toIntOrNull()
            )
        } catch (e: Exception) {
            Log.w("METAR_PARSE", "Failed to parse row for ${fields.getOrNull(1)}", e)
            null
        }
    }

    private fun parseTafCsv(file: File): List<TAF> {
        val out = mutableListOf<TAF>()
        file.bufferedReader().use { reader ->
            CSVReader(reader).use { csv ->
                csv.readNext() // discard header row
                while (true) {
                    val fields = try {
                        csv.readNext() ?: break
                    } catch (e: Exception) {
                        Log.w("TAF_PARSE", "CSV read error, stopping", e)
                        break
                    }
                    parseTafRow(fields)?.let { out += it }
                }
            }
        }
        return out
    }

    private fun parseTafRow(fields: Array<String>): TAF? {
        if (fields.size < 34) {
            Log.w("TAF_PARSE", "Skipping row, only ${fields.size} fields")
            return null
        }
        return try {
            val taf = TAF(
                stationId = fields[1],
                visibility = fields.getOrNull(21),
                skyCover = listOf(
                    fields.getOrNull(26),
                    fields.getOrNull(29),
                    fields.getOrNull(32),
                ),
                cloudBase = listOf(
                    fields.getOrNull(27)?.toIntOrNull(),
                    fields.getOrNull(30)?.toIntOrNull(),
                    fields.getOrNull(33)?.toIntOrNull(),
                ),
            )
            taf.flightCategory = determineTafConditions(taf)
            taf
        } catch (e: Exception) {
            Log.w("TAF_PARSE", "Failed to parse row for ${fields.getOrNull(1)}", e)
            null
        }
    }

    private fun determineTafConditions(forecast: TAF): String {
        val visibilityMiles = forecast.visibility?.replace("+", "")?.toDoubleOrNull() ?: 0.0
        val ceilings = forecast.cloudBase.filterNotNull()
        val ceiling = if (ceilings.isNotEmpty()) ceilings.minOrNull() ?: Int.MAX_VALUE else Int.MAX_VALUE

        return when {
            ceiling > 3000 && visibilityMiles > 5 -> "VFR"
            ceiling in 1000..3000 || (visibilityMiles in 3.0..5.0) -> "MVFR"
            ceiling in 500..999 || (visibilityMiles in 1.0..2.9) -> "IFR"
            ceiling < 500 || visibilityMiles < 1.0 -> "LIFR"
            else -> "Unknown"
        }
    }

    private inline fun <T> merge(cached: List<T>, fresh: List<T>, keyOf: (T) -> String): List<T> {
        if (fresh.isEmpty()) return cached
        val map = cached.associateBy(keyOf).toMutableMap()
        fresh.forEach { map[keyOf(it)] = it }
        return map.values.toList()
    }
}
