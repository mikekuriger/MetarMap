package com.airportweather.map

import com.airportweather.map.utils.FlightPlanUtils
import com.google.android.gms.maps.model.LatLng
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

/**
 * Lightweight metadata about a saved track KML, computed once when the
 * Tracks screen lists files. Holds enough to render a useful list row
 * (title, date, distance, point count) without requiring a re-parse on
 * every adapter bind.
 */
data class TrackSummary(
    val file: File,
    /** Short title for the row: "KFFZ → KSAN" if the filename embeds a
     *  flight label, otherwise just "Track". */
    val displayName: String,
    /** Human-readable timestamp parsed from the filename, e.g.
     *  "Jun 7, 2026 · 14:32". Falls back to the file's last-modified time. */
    val dateText: String,
    val pointCount: Int,
    val distanceNm: Double,
    val firstPoint: LatLng?,
    val lastPoint: LatLng?,
) {
    val isEmpty: Boolean get() = pointCount == 0
}

object TrackSummaryParser {

    // 2026-06-07_11-55-59 — KMLRecorder.startInternal timestamp prefix
    private val TIMESTAMP_PREFIX = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})_(\\d{2})-(\\d{2})-\\d{2}")
    // 2026-06-07_11-55-59_KFFZ-KSAN — optional flight label suffix
    private val FILENAME_WITH_LABEL = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}_(.+)\\.kml$"
    )

    private val outDateFmt = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.US)
    private val inDateFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    /**
     * Build a [TrackSummary] for [file]. Reads the whole file once to
     * count points and compute distance, so this should be called off
     * the main thread.
     */
    fun summarize(file: File): TrackSummary {
        val points = parsePoints(file)
        var distanceNm = 0.0
        for (i in 1 until points.size) {
            distanceNm += FlightPlanUtils.calculateDistance(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude,
            )
        }

        val labelMatcher = FILENAME_WITH_LABEL.matcher(file.name)
        val displayName = if (labelMatcher.matches()) {
            // "KFFZ-KSAN" → "KFFZ → KSAN" for readability
            labelMatcher.group(1)?.replace("-", " → ") ?: "Track"
        } else "Track"

        val timestampMatcher = TIMESTAMP_PREFIX.matcher(file.name)
        val dateText = if (timestampMatcher.find()) {
            try {
                val parsed = inDateFmt.parse(file.name.substringBefore('.').substring(0, 19))
                if (parsed != null) outDateFmt.format(parsed) else fallbackDate(file)
            } catch (_: Exception) {
                fallbackDate(file)
            }
        } else fallbackDate(file)

        return TrackSummary(
            file = file,
            displayName = displayName,
            dateText = dateText,
            pointCount = points.size,
            distanceNm = distanceNm,
            firstPoint = points.firstOrNull(),
            lastPoint = points.lastOrNull(),
        )
    }

    /**
     * Robust KML coordinate extractor — handles both the current format
     * (clean LineString) and the old buggy format where per-point
     * Placemarks were accidentally nested inside the coordinates block.
     * Returns all valid lon,lat[,alt] triples found anywhere in the file.
     */
    fun parsePoints(file: File): List<LatLng> {
        val text = try {
            file.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return emptyList()
        }
        val out = mutableListOf<LatLng>()
        // Pull out every <coordinates>...</coordinates> block. RegexOption.DOT_MATCHES_ALL
        // makes `.` span newlines so a multi-line coordinates body is captured.
        val coordBlockRegex = Regex("<coordinates>(.+?)</coordinates>", RegexOption.DOT_MATCHES_ALL)
        for (match in coordBlockRegex.findAll(text)) {
            val body = match.groupValues[1]
            // Each whitespace-separated token is one "lon,lat[,alt]" point.
            for (token in body.split(Regex("\\s+"))) {
                val parts = token.trim().split(",")
                if (parts.size < 2) continue
                val lon = parts[0].toDoubleOrNull() ?: continue
                val lat = parts[1].toDoubleOrNull() ?: continue
                out += LatLng(lat, lon)
            }
        }
        return out
    }

    private fun fallbackDate(file: File): String =
        outDateFmt.format(Date(file.lastModified()))
}
