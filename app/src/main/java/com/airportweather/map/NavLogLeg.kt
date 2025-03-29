package com.airportweather.map

data class NavLogLeg(
    val from: String,
    val to: String,
    val distanceNM: Double,
    val trueCourse: Int,
    val variation: Double,
    val magneticCourse: Int,
    val cruisingAltitude: Int,
    val tas: Int,
    val groundspeed: Int,
    val ete: String,
    val fuelUsed: Double,
    val windDirection: Int,
    val windSpeed: Int,
    //val wca: Int,
    val temp: Int
)
