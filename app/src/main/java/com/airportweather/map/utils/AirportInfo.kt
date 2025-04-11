package com.airportweather.map.utils

data class AirportInfo(
    val airportId: String,
    val icaoId: String,
    val name: String,
    val city: String,
    val state: String,
    val lat: Double,
    val lon: Double,
    val elev: Double,
    val magVar: Double
)

data class RunwayEnd(
    val endId: String,
    val heading: Double,
    val lat: Double,
    val lon: Double,
    val rhtp: String
)

data class Runway(
    val runwayId: String,
    val end1: RunwayEnd,
    val end2: RunwayEnd
)