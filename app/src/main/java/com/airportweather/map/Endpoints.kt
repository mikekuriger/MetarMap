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

    /** FB textual product: winds and temperatures aloft for CONUS. */
    const val WINDS_ALOFT = "https://aviationweather.gov/api/data/windtemp?region=us"

    // ---- TFRs (project-maintained GitHub mirror of FAA data) ----
    // Lives on the `data` branch so daily auto-commits don't pollute `main`'s history.
    const val TFR_GEOJSON =
        "https://raw.githubusercontent.com/mikekuriger/MetarMap/refs/heads/data/scripts/tfrs.geojson"

    // ---- Special Use Airspace (FAA ArcGIS REST → GeoJSON) ----
    // Filtered server-side to MOA, Restricted, Prohibited. Reduced coordinate
    // precision (~10m) keeps the response ~1.4 MB gzipped.
    // Add other TYPE_CODE values (W, A, CFA, NSA) by extending the IN(...) clause.
    const val SUA_GEOJSON =
        "https://services6.arcgis.com/ssFJjBXIUyZDrSYZ/ArcGIS/rest/services/Special_Use_Airspace/FeatureServer/0/query" +
            "?where=TYPE_CODE+IN+%28%27MOA%27%2C%27R%27%2C%27P%27%29" +
            "&outFields=NAME%2CTYPE_CODE%2CUPPER_DESC%2CLOWER_DESC%2CTIMESOFUSE%2CCONT_AGENT" +
            "&f=geojson&geometryPrecision=4&outSR=4326&resultRecordCount=2000"

    // ---- Charts & airport DB (regiruk.netlify.app) ----
    private const val CHARTS_HOST = "https://regiruk.netlify.app"
    // The /30 segment that used to follow Sectional was a stale chart-cycle
    // marker; the published structure is /Sectional/{z}/{x}/{y}.png today.
    const val SECTIONAL_TILES = "$CHARTS_HOST/Sectional"
    const val TERMINAL_TILES = "$CHARTS_HOST/Terminal"
    // Enroute Low IFR tiles. The directory on disk is "IFR" (matches the
    // DownloadActivity unzip target), but the netlify path is /Enroute_Low.
    const val IFR_TILES = "$CHARTS_HOST/Enroute_Low"
    const val CHARTS_CATALOG = "$CHARTS_HOST/zips/all_charts.json"
    const val SECTIONAL_METADATA = "$CHARTS_HOST/Sectional/metadata.json"
    const val TERMINAL_METADATA = "$CHARTS_HOST/Terminal/metadata.json"
    const val ENROUTE_LOW_METADATA = "$CHARTS_HOST/Enroute_Low/metadata.json"
    const val DB_MANIFEST = "$CHARTS_HOST/sqlite/db_manifest.json"
}
