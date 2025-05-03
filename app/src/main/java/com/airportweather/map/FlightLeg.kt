package com.airportweather.map

import com.airportweather.map.utils.FlightPlanUtils
import com.airportweather.map.utils.Waypoint
import com.google.android.gms.maps.model.LatLng

data class FlightLeg(
    val from: Waypoint,
    val to: Waypoint,
    val distanceNM: Double,
    val trueCourse: Int,
    val magneticCourse: Int,
    val cruisingAltitude: Int,
    val tas: Int,
    val groundspeed: Int,
    val ete: String,
    val fuelUsed: Double,
    var active: Boolean = false,
    var completed: Boolean = false
)

data class FlightPlan(
    val legs: MutableList<FlightLeg> = mutableListOf()
) {
    val totalDistanceNM: Double
        get() = legs.sumOf { it.distanceNM }

    val totalETE: String
        get() {
            val totalMinutes = legs.sumOf {
                val parts = it.ete.split(":")
                if (parts.size == 2) parts[0].toInt() * 60 + parts[1].toInt()
                else it.ete.toIntOrNull() ?: 0
            }
            return String.format("%d:%02d", totalMinutes / 60, totalMinutes % 60)
        }

    fun advanceLegIfPast(currentLocation: LatLng, thresholdNm: Double = 1.0): Boolean {
        val current = legs.firstOrNull { it.active && !it.completed } ?: return false

        val to = LatLng(current.to.lat, current.to.lon)
        val distanceNm = FlightPlanUtils.calculateDistance(
            currentLocation.latitude,
            currentLocation.longitude,
            to.latitude,
            to.longitude
        )

        if (distanceNm <= thresholdNm) {
            current.active = false
            current.completed = true

            val next = legs.dropWhile { it != current }.drop(1).firstOrNull()
            next?.active = true
            return true
        }

        return false
    }
}


