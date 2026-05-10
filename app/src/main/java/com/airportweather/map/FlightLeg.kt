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
     * Advance the active leg when the aircraft has crossed the bisector of the
     * turn at the next waypoint — the geometrically correct definition of
     * "passing" a waypoint in a flight plan.
     *
     * At waypoint B with neighbors A (previous) and C (next), the "passing
     * direction" is the average of the incoming course (A→B) and the outgoing
     * course (B→C). The line perpendicular to that direction through B is the
     * bisector of the turn. We've "passed" B when the bearing FROM B TO the
     * aircraft is within 90° of the passing direction — i.e., the waypoint is
     * now behind us along the bisector.
     *
     * For the final waypoint (no next leg), passing direction = incoming course,
     * so the check reduces to crossing the perpendicular line at the waypoint.
     *
     * Gated by [advanceWindowNm]: outside the window we don't auto-advance even
     * if the bisector geometry says we did. This avoids accidental advance when
     * the user has intentionally flown wide of the plan (sightseeing, weather
     * diversion, etc.); they can tap a leg line to advance manually.
     */
    fun advanceLegIfPast(currentLocation: LatLng, advanceWindowNm: Double = 0.5): Boolean {
        val current = legs.firstOrNull { it.active && !it.completed } ?: return false

        val distanceNm = FlightPlanUtils.calculateDistance(
            currentLocation.latitude, currentLocation.longitude,
            current.to.lat, current.to.lon
        )
        if (distanceNm > advanceWindowNm) return false

        // Passing direction = bisector of incoming and outgoing legs at the waypoint.
        // For the final waypoint, no outgoing leg — fall back to incoming course alone.
        val nextLeg = legs.dropWhile { it != current }.drop(1).firstOrNull()
        val passingDirection = if (nextLeg != null) {
            averageBearing(current.trueCourse.toDouble(), nextLeg.trueCourse.toDouble())
        } else {
            current.trueCourse.toDouble()
        }

        val bearingFromWp = FlightPlanUtils.calculateTrueCourse(
            current.to.lat, current.to.lon,
            currentLocation.latitude, currentLocation.longitude
        )
        val delta = angularDelta(bearingFromWp, passingDirection)
        if (delta < 90.0) {
            return advanceCurrent()
        }
        return false
    }

    /** Average of two compass bearings, handling 0/360 wraparound. */
    private fun averageBearing(b1: Double, b2: Double): Double {
        val diff = ((b2 - b1 + 540.0) % 360.0) - 180.0  // signed delta in (-180, 180]
        return (b1 + diff / 2.0 + 360.0) % 360.0
    }

    private fun advanceCurrent(): Boolean {
        val current = legs.firstOrNull { it.active && !it.completed } ?: return false
        current.active = false
        current.completed = true
        val next = legs.dropWhile { it != current }.drop(1).firstOrNull()
        next?.active = true
        return true
    }

    /**
     * Manually activate a specific leg by index. Earlier legs are marked completed,
     * the chosen leg becomes active, later legs are reset (not active, not completed).
     * No-ops if [index] is out of range or the leg is already the active one.
     *
     * Returns true if state changed.
     */
    fun activateLeg(index: Int): Boolean {
        if (index !in legs.indices) return false
        val target = legs[index]
        if (target.active && !target.completed) return false

        legs.forEachIndexed { i, leg ->
            when {
                i < index -> { leg.active = false; leg.completed = true }
                i == index -> { leg.active = true; leg.completed = false }
                else -> { leg.active = false; leg.completed = false }
            }
        }
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
