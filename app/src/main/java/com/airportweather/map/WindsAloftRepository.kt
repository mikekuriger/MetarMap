package com.airportweather.map

import android.content.Context
import android.util.Log
import com.airportweather.map.utils.AirportDatabaseHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fetches and parses the FAA's FB (winds and temperatures aloft) textual
 * product so the flight planner can pre-fill wind values.
 *
 * The FB lists ~150 reporting stations across CONUS, each with up to 9 fixed
 * forecast altitudes (3k, 6k, 9k, 12k, 18k, 24k, 30k, 34k, 39k). We parse the
 * whole grid into memory, then on lookup resolve each station's 3-letter code
 * to lat/lon via the airport DB, pick the station nearest the route midpoint,
 * and linearly interpolate to the requested cruise altitude.
 */
class WindsAloftRepository(
    private val filesDir: File,
    private val maxAgeMillis: Long = TimeUnit.HOURS.toMillis(6),
) {

    /** One forecast row for a station at one of the FB's standard altitudes. */
    data class Entry(
        /** Wind direction °TRUE. -1 means "light and variable". */
        val direction: Int,
        /** Wind speed in knots. */
        val speed: Int,
        /** Temperature °C, or null if missing. */
        val tempC: Int?,
    )

    /**
     * Result of an interpolated lookup: the wind to use, plus the station and
     * altitudes the values came from so the planner can show provenance.
     */
    data class Resolved(
        val direction: Int,
        val speed: Int,
        val sourceStation: String,
        val sourceLowAlt: Int,
        val sourceHighAlt: Int,
    )

    private val cacheFile: File = File(filesDir, "winds_aloft.txt").apply { parentFile?.mkdirs() }

    /**
     * Picks the wind value to apply to a flight at [cruiseAltFt] near
     * ([lat], [lon]). Resolves FB station codes through the bundled airport DB,
     * skips any whose 3-letter ID we can't locate. Returns null when fetching
     * or parsing fails, when no station is reachable, or when no FB station has
     * data at the requested altitude.
     */
    suspend fun forecastFor(
        context: Context,
        lat: Double,
        lon: Double,
        cruiseAltFt: Int,
    ): Resolved? = withContext(Dispatchers.IO) {
        val station = nearestStation(context, lat, lon) ?: return@withContext null
        val interp = interpolate(station.entries, cruiseAltFt) ?: return@withContext null
        Resolved(
            direction = interp.entry.direction,
            speed = interp.entry.speed,
            sourceStation = station.code,
            sourceLowAlt = interp.lowAlt,
            sourceHighAlt = interp.highAlt,
        )
    }

    /**
     * Convenience: returns the wind at each requested [altitudes] using a single
     * fetch + nearest-station lookup. Used by the cruise-altitude picker to
     * annotate every spinner item without making N separate requests.
     * Returns null if the FB can't be fetched or no nearby station resolves.
     */
    suspend fun forecastFor(
        context: Context,
        lat: Double,
        lon: Double,
        altitudes: List<Int>,
    ): Map<Int, Resolved>? = withContext(Dispatchers.IO) {
        val station = nearestStation(context, lat, lon) ?: return@withContext null
        altitudes.mapNotNull { alt ->
            val interp = interpolate(station.entries, alt) ?: return@mapNotNull null
            alt to Resolved(
                direction = interp.entry.direction,
                speed = interp.entry.speed,
                sourceStation = station.code,
                sourceLowAlt = interp.lowAlt,
                sourceHighAlt = interp.highAlt,
            )
        }.toMap()
    }

    private data class StationLookup(val code: String, val entries: Map<Int, Entry>)

    /**
     * Resolves the FB station nearest ([lat], [lon]) whose 3-letter code
     * matches an entry in the airport DB. Returns null if the FB can't be
     * fetched or no station has a usable airport-DB match nearby.
     */
    private fun nearestStation(context: Context, lat: Double, lon: Double): StationLookup? {
        val text = getOrDownload() ?: return null
        val byStation = parse(text)
        if (byStation.isEmpty()) return null

        // Resolve station coordinates lazily — only for stations actually
        // present in the FB. Skips IDs that aren't in the airport DB.
        val dbHelper = AirportDatabaseHelper(context)
        val stationLocations = byStation.keys.mapNotNull { code ->
            val wp = dbHelper.lookupWaypoint("K$code") ?: dbHelper.lookupWaypoint(code)
            wp?.let { code to (it.lat to it.lon) }
        }.toMap()
        if (stationLocations.isEmpty()) return null

        val nearest = stationLocations.minByOrNull { (_, ll) ->
            haversineNm(lat, lon, ll.first, ll.second)
        }?.key ?: return null

        return StationLookup(nearest, byStation[nearest] ?: emptyMap())
    }

    private fun getOrDownload(): String? {
        if (cacheFile.exists() && cacheFile.length() > 0 &&
            System.currentTimeMillis() - cacheFile.lastModified() < maxAgeMillis
        ) {
            return runCatching { cacheFile.readText() }.getOrNull()
        }
        return try {
            val conn = (URL(Endpoints.WINDS_ALOFT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 30_000
            }
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP ${conn.responseCode}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            cacheFile.writeText(body)
            body
        } catch (e: IOException) {
            Log.w("WindsAloft", "Fetch failed: ${e.message}")
            // Fall back to stale cache if the live fetch failed.
            if (cacheFile.exists()) runCatching { cacheFile.readText() }.getOrNull() else null
        }
    }

    // ---- parsing ----

    /**
     * Parses an FB document. Returns station -> (altitude -> entry). Empty
     * cells are omitted; "9900" is recorded as direction=-1 ("light & variable").
     *
     * The FB's data rows are right-aligned under each altitude header label,
     * not left-aligned where the label begins. So a row like
     *   `ABI      0706+12 3413+10 …`
     * has the 3000 ft cell at columns 4-7 (empty for ABI), the 6000 ft cell
     * "0706+12" at columns 9-15, etc. We derive each cell's right edge from
     * the matching altitude label's right edge, then walk back by the known
     * cell width per altitude band: 4 chars for 3000 (no temp), 7 chars for
     * 6000-24000 (signed temp), 6 chars for 30000+ (implied-negative temp).
     */
    internal fun parse(text: String): Map<String, Map<Int, Entry>> {
        val lines = text.lines()
        val ftLine = lines.firstOrNull { it.trim().startsWith("FT") } ?: return emptyMap()

        // Locate each altitude label and its ending column inside the header.
        val matches = Regex("\\d+").findAll(ftLine).toList()
        if (matches.isEmpty()) return emptyMap()
        val altitudes = matches.map { it.value.toInt() }
        val altitudeEndCols = matches.map { it.range.last }

        val byStation = mutableMapOf<String, MutableMap<Int, Entry>>()
        val stationLineRegex = Regex("^[A-Z0-9]{3}\\s")
        for (raw in lines) {
            if (!stationLineRegex.containsMatchIn(raw)) continue
            val code = raw.substring(0, 3)
            val padded = raw.padEnd(altitudeEndCols.last() + 1)
            for ((altIdx, alt) in altitudes.withIndex()) {
                val endCol = altitudeEndCols[altIdx]
                val width = cellWidthFor(alt)
                val startCol = endCol - width + 1
                if (startCol < 0 || endCol + 1 > padded.length) continue
                val cell = padded.substring(startCol, endCol + 1).trim()
                if (cell.isEmpty()) continue
                val entry = parseCell(cell, includesTemp = alt >= 6000)
                if (entry != null) {
                    byStation.getOrPut(code) { mutableMapOf() }[alt] = entry
                }
            }
        }
        return byStation
    }

    /** Width of a single cell at the given altitude, per FAA FB format. */
    private fun cellWidthFor(altitudeFt: Int): Int = when {
        altitudeFt < 6000 -> 4   // no temperature reported
        altitudeFt >= 30000 -> 6 // negative-temperature sign is implied
        else -> 7                // signed temperature suffix included
    }

    /**
     * Parses a single cell. The FB encodes:
     *   - 4 digits: ddss → wind direction (dd × 10°) and speed (ss kt)
     *   - When dd ≥ 51: the wind has been "shifted" — true direction is
     *     (dd − 50) × 10° and true speed is 100 + ss kt
     *   - Below 24000 ft: optional temperature suffix like "+12" / "-08"
     *   - At 24000+ ft: 6 digits ddssTT (TT is always negative, sign implied)
     *   - "9900" = light and variable, < 5 kt
     */
    internal fun parseCell(rawCell: String, includesTemp: Boolean): Entry? {
        val cell = rawCell.trim()
        if (cell.length < 4) return null

        val highAlt = cell.length == 6 && cell.all { it.isDigit() }
        val ddss = cell.take(4)
        if (!ddss.all { it.isDigit() }) return null

        val dirRaw = ddss.substring(0, 2).toInt()
        val spdRaw = ddss.substring(2, 4).toInt()

        val (direction, speed) = when {
            dirRaw == 99 && spdRaw == 0 -> -1 to 0  // light & variable
            dirRaw >= 51 -> ((dirRaw - 50) * 10) to (spdRaw + 100)
            else -> (dirRaw * 10) to spdRaw
        }

        val temp: Int? = when {
            highAlt -> -cell.substring(4, 6).toInt()  // sign implied negative
            includesTemp && cell.length >= 7 -> {
                val sign = cell[4]
                val mag = cell.substring(5, 7).toIntOrNull() ?: return null
                if (sign == '-') -mag else if (sign == '+') mag else null
            }
            else -> null
        }
        return Entry(direction = direction, speed = speed, tempC = temp)
    }

    // ---- altitude interpolation ----

    private data class InterpResult(val entry: Entry, val lowAlt: Int, val highAlt: Int)

    private fun interpolate(forecasts: Map<Int, Entry>, targetAlt: Int): InterpResult? {
        if (forecasts.isEmpty()) return null
        val sorted = forecasts.toSortedMap()
        // Below the lowest forecast altitude: use the lowest available.
        sorted[targetAlt]?.let { return InterpResult(it, targetAlt, targetAlt) }
        val low = sorted.entries.lastOrNull { it.key <= targetAlt }
        val high = sorted.entries.firstOrNull { it.key >= targetAlt }
        if (low == null && high == null) return null
        if (low == null) return InterpResult(high!!.value, high.key, high.key)
        if (high == null) return InterpResult(low.value, low.key, low.key)
        // Linear interpolation; direction handled circularly via vector blend.
        val frac = (targetAlt - low.key).toDouble() / (high.key - low.key).toDouble()
        val blendedSpeed = (low.value.speed + frac * (high.value.speed - low.value.speed))
            .roundToInt().coerceAtLeast(0)
        val blendedDir = if (low.value.direction < 0 || high.value.direction < 0) {
            // If either side is "variable", inherit the defined side.
            (if (low.value.direction >= 0) low.value.direction else high.value.direction)
        } else {
            blendBearings(low.value.direction.toDouble(), high.value.direction.toDouble(), frac).roundToInt()
        }
        return InterpResult(
            Entry(direction = blendedDir, speed = blendedSpeed, tempC = null),
            lowAlt = low.key,
            highAlt = high.key,
        )
    }

    /** Vector blend of two compass bearings — handles the 0/360 wraparound. */
    private fun blendBearings(a: Double, b: Double, frac: Double): Double {
        val ax = sin(Math.toRadians(a)); val ay = cos(Math.toRadians(a))
        val bx = sin(Math.toRadians(b)); val by = cos(Math.toRadians(b))
        val x = ax + frac * (bx - ax)
        val y = ay + frac * (by - ay)
        val deg = Math.toDegrees(atan2(x, y))
        return (deg + 360) % 360
    }

    // ---- distance ----

    private fun haversineNm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3440.065  // nautical miles
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
