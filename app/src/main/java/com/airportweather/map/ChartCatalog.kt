package com.airportweather.map

/**
 * Parsed view of all_charts.json. Each section (sectional/terminal/enroute) carries
 * the FAA chart cycle date in [ChartSection.series]. That string is what the app
 * stores per installed chart so it can decide whether a downloaded zip is stale.
 */
data class ChartCatalog(
    val sectional: ChartSection,
    val terminal: ChartSection,
    val enroute: ChartSection,
)

data class ChartSection(
    /**
     * FAA cycle start (the "valid" date). Sourced from the per-section
     * metadata.json — that file is updated reliably; the same field on
     * all_charts.json drifts.
     */
    val series: String,
    /** End of validity (e.g. "01-22-2026"). Null if metadata.json was unreachable. */
    val expires: String?,
    val charts: List<ChartEntry>,
)

data class ChartEntry(
    val name: String,
    val fileName: String,
    val url: String,
    /** Size string from the catalog (e.g. "143.67 MB"). */
    val size: String,
)
