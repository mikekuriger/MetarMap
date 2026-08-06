package com.airportweather.map

/**
 * Regional groupings for "download everything in this area" and the
 * sectional-to-terminal overlap map used to attach the *right* TAC charts to
 * each sectional (a sectional area can genuinely overlap more than one TAC,
 * e.g. Los Angeles + San Diego, and a TAC can span multiple sectionals, e.g.
 * Minneapolis-St Paul under both Twin Cities and Green Bay).
 *
 * Both were computed offline from chartmaker's/avare's per-area clip-shape
 * bounds (bounding-box intersection against real chart geometry), not
 * hand-guessed. Region boundaries: 40N latitude for north/south, then 93W
 * (north half) / 97W (south half) for east/west, approximated to the nearest
 * whole area. Alaska/Pacific/Caribbean are pulled out as their own regions
 * rather than folded into a geometrically "correct" but practically useless
 * quadrant (nobody downloading "Northwest" for a Seattle trip wants every
 * Alaska chart bundled in).
 */
enum class ChartRegion(val label: String) {
    NW("Northwest"),
    NE("Northeast"),
    SW("Southwest"),
    SE("Southeast"),
    ALASKA("Alaska"),
    PACIFIC("Pacific"),
    CARIBBEAN("Caribbean"),
}

/**
 * Uppercase, alphanumeric-only key so naming variants that all refer to the
 * same area ("Dallas_Ft_Worth", "Dallas-Ft Worth", "Dallas Ft Worth") match
 * regardless of which punctuation convention the catalog happens to use.
 */
fun normalizeAreaName(name: String): String = name.uppercase().filter { it.isLetterOrDigit() }

object ChartRegions {

    private val rawRegionAreas: Map<ChartRegion, Set<String>> = mapOf(
        ChartRegion.NW to setOf(
            "Billings", "Cheyenne", "Great Falls", "Klamath Falls", "Omaha",
            "Salt Lake City", "Seattle", "Twin Cities",
        ),
        ChartRegion.NE to setOf(
            "Chicago", "Detroit", "Green Bay", "Halifax", "Lake Huron", "Montreal", "New York",
        ),
        ChartRegion.SW to setOf(
            "Albuquerque", "Brownsville", "Dallas-Ft Worth", "Denver", "El Paso",
            "Las Vegas", "Los Angeles", "Phoenix", "San Antonio", "San Francisco", "Wichita",
        ),
        ChartRegion.SE to setOf(
            "Atlanta", "Charlotte", "Cincinnati", "Houston", "Jacksonville", "Kansas City",
            "Memphis", "Miami", "New Orleans", "St Louis", "Washington",
        ),
        ChartRegion.ALASKA to setOf(
            "Anchorage", "Bethel", "Cape Lisburne", "Cold Bay", "Dawson", "Dutch Harbor",
            "Fairbanks", "Juneau", "Ketchikan", "Kodiak", "McGrath", "Nome", "Point Barrow",
            "Seward", "Western Aleutian Islands East", "Western Aleutian Islands West",
        ),
        ChartRegion.PACIFIC to setOf(
            "Hawaiian Islands", "Mariana Islands Inset", "Samoan Islands Inset",
        ),
        // Chartmaker builds Caribbean as one monolithic chart, not per-area zips
        // like Sectional, so there's nothing to group yet. Kept for when/if that
        // changes rather than leaving the region undefined.
        ChartRegion.CARIBBEAN to emptySet(),
    )

    private val regionAreas: Map<ChartRegion, Set<String>> =
        rawRegionAreas.mapValues { (_, names) -> names.map(::normalizeAreaName).toSet() }

    // Recomputed 2026-08-06: the original pass had a units bug -- 3 of the 34
    // TAC geojson files (Chicago, Las Vegas, New Orleans) are published in
    // CRS84 (plain lon/lat degrees) while the other 31 use EPSG:3857 (Web
    // Mercator meters); blindly applying the meters conversion to the CRS84
    // ones collapsed their bounds to ~0,0, so they never overlapped anything
    // and showed up as orphan "terminal-only" rows -- e.g. Las Vegas appeared
    // 3 times (sectional-only, orphan TAC, IFR) instead of the normal 2. Fixed
    // by branching on each file's own crs field; all 34 TAC areas now claimed.
    private val rawSectionalToTerminal: Map<String, List<String>> = mapOf(
        "Anchorage" to listOf("Anchorage"),
        "Atlanta" to listOf("Atlanta", "Charlotte"),
        "Charlotte" to listOf("Charlotte"),
        "Cheyenne" to listOf("Denver"),
        "Chicago" to listOf("Chicago", "Cincinnati", "Detroit"),
        "Cincinnati" to listOf("Baltimore-Washington", "Cincinnati", "Pittsburgh"),
        "Dallas-Ft Worth" to listOf("Dallas-Ft Worth"),
        "Denver" to listOf("Colorado Springs", "Denver"),
        "Detroit" to listOf("Cincinnati", "Cleveland", "Detroit", "Pittsburgh"),
        "Fairbanks" to listOf("Fairbanks"),
        "Green Bay" to listOf("Minneapolis-St Paul"),
        "Houston" to listOf("Houston", "New Orleans"),
        "Jacksonville" to listOf("Orlando", "Tampa"),
        "Kansas City" to listOf("Kansas City", "St Louis"),
        "Las Vegas" to listOf("Las Vegas"),
        "Los Angeles" to listOf("Los Angeles", "San Diego"),
        "McGrath" to listOf("Anchorage"),
        "Memphis" to listOf("Memphis"),
        "Miami" to listOf("Miami", "Orlando", "Tampa"),
        "New Orleans" to listOf("New Orleans"),
        "New York" to listOf("Boston", "New York", "Philadelphia"),
        "Omaha" to listOf("Kansas City", "Minneapolis-St Paul"),
        "Phoenix" to listOf("Las Vegas", "Phoenix"),
        "Salt Lake City" to listOf("Salt Lake City"),
        "San Francisco" to listOf("San Francisco"),
        "Seattle" to listOf("Portland", "Seattle"),
        "St Louis" to listOf("Cincinnati", "St Louis"),
        "Twin Cities" to listOf("Minneapolis-St Paul"),
        "Washington" to listOf("Baltimore-Washington", "Philadelphia"),
        "Wichita" to listOf("Colorado Springs", "Denver"),
    )

    private val sectionalToTerminal: Map<String, List<String>> =
        rawSectionalToTerminal
            .mapKeys { (k, _) -> normalizeAreaName(k) }
            .mapValues { (_, v) -> v.map(::normalizeAreaName) }

    fun regionOf(sectionalAreaName: String): ChartRegion? {
        val key = normalizeAreaName(sectionalAreaName)
        return regionAreas.entries.firstOrNull { key in it.value }?.key
    }

    /**
     * Normalized terminal-area name keys overlapping [sectionalAreaName], per
     * the pre-computed clip-shape bounding-box intersection. Empty if this
     * sectional has no overlapping TAC coverage (most areas don't).
     */
    fun overlappingTerminalKeys(sectionalAreaName: String): List<String> =
        sectionalToTerminal[normalizeAreaName(sectionalAreaName)].orEmpty()
}
