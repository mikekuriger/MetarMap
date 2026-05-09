package com.airportweather.map

/**
 * Special Use Airspace feature — MOA, Restricted, Prohibited, etc.
 *
 * [typeCode] uses the FAA's single-letter / short-form codes:
 *   MOA = Military Operations Area
 *   R   = Restricted
 *   P   = Prohibited
 *   W   = Warning
 *   A   = Alert
 */
data class SuaProperties(
    val name: String,
    val typeCode: String,
    val upperDesc: String?,       // e.g. "9000 FT MSL", "FL250"
    val lowerDesc: String?,
    val timesOfUse: String?,
    val controllingAgent: String?,
)

data class SuaFeature(
    val properties: SuaProperties,
    /** Outer ring + optional inner rings of a single Polygon, [lon, lat] order. */
    val coordinates: List<List<List<Double>>>,
)
