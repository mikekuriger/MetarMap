package com.airportweather.map

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