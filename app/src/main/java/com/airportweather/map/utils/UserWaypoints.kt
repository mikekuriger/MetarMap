package com.airportweather.map.utils

import android.content.Context
import android.util.Log

/**
 * In-memory overlay of user-defined waypoints loaded from
 * `app/src/main/assets/fake_waypoints.txt`. Lookups in
 * [AirportDatabaseHelper.lookupWaypoint] check this overlay before
 * falling through to the bundled FAA database.
 *
 * File format — one waypoint per line, comma-separated:
 *
 *     CODE,LAT,LON
 *     # lines starting with # are comments
 *     # blank lines are ignored
 *
 * Removal:
 *  - to disable individual entries: delete the line from the file
 *  - to disable the whole feature: delete the assets file (the loader
 *    silently skips when the file isn't present)
 *
 * Waypoints loaded here use type = "USER" so the map renderer can show
 * them in a distinct color (magenta) — visually obvious that they aren't
 * real FAA fixes/navaids/airports.
 */
object UserWaypoints {
    private const val ASSET_NAME = "fake_waypoints.txt"
    private val byCode = mutableMapOf<String, Waypoint>()
    private var loaded = false

    /** Idempotent. Safe to call from any AirportDatabaseHelper entry point. */
    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            context.applicationContext.assets.open(ASSET_NAME)
                .bufferedReader()
                .useLines { lines ->
                    for (raw in lines) {
                        val line = raw.trim()
                        if (line.isEmpty() || line.startsWith("#")) continue
                        val parts = line.split(",")
                        if (parts.size < 3) continue
                        val code = parts[0].trim().uppercase()
                        val lat = parts[1].trim().toDoubleOrNull() ?: continue
                        val lon = parts[2].trim().toDoubleOrNull() ?: continue
                        byCode[code] = Waypoint(
                            name = code,
                            type = "USER",
                            lat = lat,
                            lon = lon,
                        )
                    }
                }
            Log.d("UserWaypoints", "Loaded ${byCode.size} test waypoints from $ASSET_NAME")
        } catch (e: Exception) {
            // File absent or unreadable — that's fine, just no overlay.
            Log.d("UserWaypoints", "$ASSET_NAME not present (${e.message})")
        }
    }

    fun lookup(code: String): Waypoint? = byCode[code.uppercase()]
    fun exists(code: String): Boolean = byCode.containsKey(code.uppercase())
    fun prefixExists(prefix: String): Boolean {
        if (prefix.isEmpty()) return false
        val upper = prefix.uppercase()
        return byCode.keys.any { it.startsWith(upper) }
    }
}
