package com.airportweather.map.aircraft

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches and parses a single aircraft's record from the public FAA Aircraft
 * Registry. The FAA doesn't publish a JSON API, so we scrape the public
 * "N-Number Inquiry Results" HTML page. The fields we extract come from
 * predictable `data-label="..."` table cells; if the FAA reworks their page
 * the parser fails gracefully (returns null).
 */
object FaaRegistryLookup {

    /**
     * Fields we lift from the registry. Everything optional — we surface
     * whatever the FAA gives us.
     */
    data class Result(
        val tailNumber: String,
        val serialNumber: String?,
        val manufacturer: String?,
        val model: String?,
        val typeAircraft: String?,
        val mfrYear: String?,
    ) {
        /**
         * Human-readable type string suitable for the aircraft editor's
         * "Aircraft type" text field — e.g. "CESSNA 150K (1969)".
         */
        fun displayType(): String = buildString {
            manufacturer?.let { append(it) }
            model?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }

        /** Best-guess Category mapping from the FAA "Type Aircraft" descriptor. */
        fun guessCategory(): Category? {
            val t = typeAircraft?.lowercase() ?: return null
            return when {
                "fixed wing" in t -> Category.AIRPLANE
                "rotorcraft" in t || "helicopter" in t -> Category.HELICOPTER
                "glider" in t -> Category.GLIDER
                "gyroplane" in t -> Category.GYROPLANE
                "powered-lift" in t || "powered lift" in t -> Category.POWERED_LIFT
                "balloon" in t || "lighter than air" in t || "lighter-than-air" in t ->
                    Category.LIGHTER_THAN_AIR
                "weight-shift" in t || "weight shift" in t || "powered parachute" in t ->
                    Category.ULTRALIGHT
                else -> Category.OTHER
            }
        }
    }

    private const val BASE_URL =
        "https://registry.faa.gov/AircraftInquiry/Search/NNumberResult"

    /**
     * Returns a [Result] for [rawNumber] (with or without the leading "N"),
     * or null if the registry has no record or the network/parse failed.
     */
    suspend fun lookup(rawNumber: String): Result? = withContext(Dispatchers.IO) {
        val tail = normalize(rawNumber) ?: return@withContext null
        val html = fetch(tail) ?: return@withContext null
        parse(html, tail)
    }

    /**
     * Normalises user input to the format the FAA expects. Strips whitespace,
     * uppercases, prepends "N" if missing. Returns null for input that doesn't
     * look like an N-number at all.
     */
    private fun normalize(raw: String): String? {
        val cleaned = raw.trim().uppercase().filter { it.isLetterOrDigit() }
        if (cleaned.isEmpty()) return null
        val withPrefix = if (cleaned.startsWith("N")) cleaned else "N$cleaned"
        // FAA N-numbers are 1-5 chars after the N. Anything wildly outside
        // that is almost certainly a typo or not US-registered.
        if (withPrefix.length !in 2..6) return null
        return withPrefix
    }

    private fun fetch(tail: String): String? {
        return try {
            val url = "$BASE_URL?nNumberTxt=${URLEncoder.encode(tail, "UTF-8")}"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                // The FAA page returns the generic site shell to user agents
                // it doesn't recognise; setting a real-browser UA reliably
                // surfaces the actual results table.
                setRequestProperty("User-Agent", "Mozilla/5.0 (MetarMap)")
                connectTimeout = 10_000
                readTimeout = 30_000
            }
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w("FaaRegistry", "HTTP ${conn.responseCode} for $tail")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            Log.w("FaaRegistry", "Fetch failed for $tail: ${e.message}")
            null
        }
    }

    /**
     * Parses the registry HTML. Returns null when the "Aircraft Description"
     * table isn't present — that's the marker for "no record found" (either
     * unassigned or deregistered).
     */
    internal fun parse(html: String, tail: String): Result? {
        if (!html.contains("Aircraft Description")) return null
        return Result(
            tailNumber = tail,
            serialNumber = extractField(html, "Serial Number"),
            manufacturer = extractField(html, "Manufacturer Name"),
            model = extractField(html, "Model"),
            typeAircraft = extractField(html, "Aircraft Type"),
            mfrYear = extractField(html, "Mfr Year"),
        )
    }

    /**
     * Pulls a single value from a `<td data-label="...">` cell. The FAA pads
     * values with trailing spaces in the HTML — we trim them. Returns null
     * when the label is absent (the page doesn't always include every field).
     */
    private fun extractField(html: String, label: String): String? {
        // Match the value cell, not the label cell — the label cell uses an
        // empty data-label attribute; only the value cell carries the field
        // name. Pattern: data-label="Serial Number">VALUE</td>
        val pattern = Regex(
            "data-label=\"${Regex.escape(label)}\"[^>]*>([^<]*?)</td>",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
}
