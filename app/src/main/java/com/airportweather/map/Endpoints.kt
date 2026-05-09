package com.airportweather.map

/**
 * Centralized list of every external URL the app talks to. When a third-party
 * feed moves or changes shape (as aviationweather did when it retired the
 * tafs.cache.csv.gz endpoint), this is the one file to update.
 *
 * Deliberately *not* here:
 *   - Stratux WebSocket addresses (ws://192.168.10.1/...) — those are hardcoded
 *     local-network hardware addresses, not a server we'd ever swap.
 *   - Google Maps API key — lives in AndroidManifest.xml because the Maps SDK
 *     reads it from manifest metadata.
 *   - AviationStack API key — injected at build time from local.properties.
 */
object Endpoints {

    // ---- Weather (aviationweather.gov) ----
    const val METAR_CSV_GZ = "https://aviationweather.gov/data/cache/metars.cache.csv.gz"

    /** Default CONUS bbox: lat 24-50 N, lon 125-66 W. Returns ~380 stations. */
    const val TAF_BBOX_CONUS = "24,-125,50,-66"
    private const val TAF_JSON_BASE = "https://aviationweather.gov/api/data/taf"
    fun tafJson(bbox: String = TAF_BBOX_CONUS): String =
        "$TAF_JSON_BASE?format=json&bbox=$bbox"

    // ---- TFRs (project-maintained GitHub mirror of FAA data) ----
    const val TFR_GEOJSON =
        "https://raw.githubusercontent.com/mikekuriger/MetarMap/refs/heads/main/scripts/tfrs.geojson"

    // ---- Charts & airport DB (regiruk.netlify.app) ----
    private const val CHARTS_HOST = "https://regiruk.netlify.app"
    const val SECTIONAL_TILES = "$CHARTS_HOST/Sectional/30"
    const val TERMINAL_TILES = "$CHARTS_HOST/Terminal"
    const val CHARTS_CATALOG = "$CHARTS_HOST/zips/all_charts.json"
    const val DB_MANIFEST = "$CHARTS_HOST/sqlite/db_manifest.json"
}
