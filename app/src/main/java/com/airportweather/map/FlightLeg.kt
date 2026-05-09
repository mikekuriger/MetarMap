package com.airportweather.map

import com.airportweather.map.utils.FlightPlanUtils
import com.airportweather.map.utils.Waypoint
import com.google.android.gms.maps.model.LatLng
import kotlin.math.abs

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

    /**
     * Advance the active leg if the aircraft has reached or passed the `to` waypoint.
     *
     * Two triggers, either of which fires the advance:
     *   1. Proximity: distance to the waypoint is within [proximityNm] (you're "at" it).
     *   2. Bearing-flip: bearing from current position to the waypoint differs from
     *      the leg's true course by more than 90° (the waypoint is now behind you).
     *      This catches the common case of flying past a waypoint with crosswind drift
     *      or a wide turn — proximity alone never triggered, leaving the leg stuck.
     */
    fun advanceLegIfPast(currentLocation: LatLng, proximityNm: Double = 0.5): Boolean {
        val current = legs.firstOrNull { it.active && !it.completed } ?: return false

        val distanceNm = FlightPlanUtils.calculateDistance(
            currentLocation.latitude, currentLocation.longitude,
            current.to.lat, current.to.lon
        )

        if (distanceNm <= proximityNm) {
            return advanceCurrent()
        }

        val bearingToWaypoint = FlightPlanUtils.calculateTrueCourse(
            currentLocation.latitude, currentLocation.longitude,
            current.to.lat, current.to.lon
        )
        val courseDelta = angularDelta(bearingToWaypoint, current.trueCourse.toDouble())
        if (courseDelta > 90.0) {
            return advanceCurrent()
        }

        return false
    }

    /** Manually advance the active leg (for the tap-on-line UI). */
    fun forceAdvance(): Boolean = advanceCurrent()

    private fun advanceCurrent(): Boolean {
        val current = legs.firstOrNull { it.active && !it.completed } ?: return false
        current.active = false
        current.completed = true
        val next = legs.dropWhile { it != current }.drop(1).firstOrNull()
        next?.active = true
        return true
    }

    /** Smallest absolute difference between two compass bearings, in degrees [0, 180]. */
    private fun angularDelta(a: Double, b: Double): Double {
        var diff = (a - b) % 360.0
        if (diff < -180.0) diff += 360.0
        if (diff > 180.0) diff -= 360.0
        return abs(diff)
    }
}
