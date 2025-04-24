// File: FlightPlanUtils.kt
package com.airportweather.map.utils

import android.content.Context
import android.location.Location
import com.airportweather.map.FlightLeg
import com.airportweather.map.FlightPlan
import com.airportweather.map.Waypoint
import kotlin.math.*

object FlightPlanHolder {
    var currentPlan: FlightPlan? = null
}

// ✅ Utility to build a FlightPlan from raw text
fun buildFlightPlanFromText(
    context: Context,
    rawText: String,
    tas: Int = 95,
    fuelBurn: Double = 6.0,
    cruiseAltitude: Int = 5500,
    currentLocation: Location? = null
): FlightPlan? {
    if (rawText.isBlank()) return null

    val dbHelper = AirportDatabaseHelper(context)
    val waypoints = rawText.trim()
        .split("\\s+".toRegex())
        .mapNotNull { dbHelper.lookupWaypoint(it.uppercase()) }

    if (waypoints.isEmpty()) return null

    // ✈️ Single waypoint: use current location as 'from'
    if (waypoints.size == 1 && currentLocation != null) {
        val from = Waypoint(
            name = ".",
            type = "USER",
            lat = currentLocation.latitude,
            lon = currentLocation.longitude,
            elev = 0f,
            showDist = false,
            visible = false,
            magVar = 0.0
        )
        val to = waypoints[0]

        val leg = FlightPlanUtils.generateLeg(from, to, tas, fuelBurn, cruiseAltitude)
        leg.active = true
        return FlightPlan(mutableListOf(leg))
    }

    // Normal multi-leg
    if (waypoints.size < 2) return null

    val legs = FlightPlanUtils.generateLegs(
        waypoints = waypoints,
        tas = tas,
        fuelBurn = fuelBurn,
        cruiseAltitude = cruiseAltitude
    )
        val mutableLegs = legs.toMutableList()
        mutableLegs.firstOrNull()?.active = true
        return FlightPlan(mutableLegs)
}

object FlightPlanUtils {

    fun generateLeg(
        from: Waypoint,
        to: Waypoint,
        tas: Int,
        fuelBurn: Double,
        cruiseAltitude: Int,
        windDir: Int = 0,
        windSpeed: Int = 0
    ): FlightLeg {
        val trueCourse = calculateTrueCourse(from.lat, from.lon, to.lat, to.lon)
        val distanceNM = calculateDistance(from.lat, from.lon, to.lat, to.lon).round1()
        val wca = estimateWCA(trueCourse, windDir, windSpeed, tas)
        val magneticCourse = ((trueCourse + wca + from.magVar).roundToInt() + 360) % 360
        val groundspeed = estimateGroundSpeed(tas, windDir, windSpeed, trueCourse).roundToInt()
        val eteMinutes = if (groundspeed > 0) (distanceNM / groundspeed) * 60 else 0.0
        val fuelUsed = ((eteMinutes / 60) * fuelBurn).round1()
        val ete = formatETA(eteMinutes)

        return FlightLeg(
            from = from,
            to = to,
            distanceNM = distanceNM,
            trueCourse = trueCourse.roundToInt(),
            magneticCourse = magneticCourse,
            cruisingAltitude = cruiseAltitude,
            tas = tas,
            groundspeed = groundspeed,
            ete = ete,
            fuelUsed = fuelUsed
        )
    }


    fun generateLegs(
        waypoints: List<Waypoint>,
        tas: Int,
        fuelBurn: Double,
        cruiseAltitude: Int,
        windDir: Int = 0,
        windSpeed: Int = 0
    ): List<FlightLeg> {
        val legs = mutableListOf<FlightLeg>()

        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to = waypoints[i + 1]

            val trueCourse = calculateTrueCourse(from.lat, from.lon, to.lat, to.lon)
            val distanceNM = calculateDistance(from.lat, from.lon, to.lat, to.lon).round1()
            val wca = estimateWCA(trueCourse, windDir, windSpeed, tas)
            val magneticCourse = ((trueCourse + wca + from.magVar).roundToInt() + 360) % 360
            val groundspeed = estimateGroundSpeed(tas, windDir, windSpeed, trueCourse).roundToInt()
            val eteMinutes = if (groundspeed > 0) (distanceNM / groundspeed) * 60 else 0.0
            val fuelUsed = ((eteMinutes / 60) * fuelBurn).round1()
            val ete = formatETA(eteMinutes)

            legs.add(
                FlightLeg(
                    from = from,
                    to = to,
                    distanceNM = distanceNM,
                    trueCourse = trueCourse.roundToInt(),
                    magneticCourse = magneticCourse,
                    cruisingAltitude = cruiseAltitude,
                    tas = tas,
                    groundspeed = groundspeed,
                    ete = ete,
                    fuelUsed = fuelUsed
                )
            )
        }

        return legs
    }

    fun calculateTrueCourse(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lon1Rad = Math.toRadians(lon1)
        val lat2Rad = Math.toRadians(lat2)
        val lon2Rad = Math.toRadians(lon2)

        val deltaLon = lon2Rad - lon1Rad
        val y = sin(deltaLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLon)

        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 3440.065
        val lat1Rad = Math.toRadians(lat1)
        val lon1Rad = Math.toRadians(lon1)
        val lat2Rad = Math.toRadians(lat2)
        val lon2Rad = Math.toRadians(lon2)
        val dLat = lat2Rad - lat1Rad
        val dLon = lon2Rad - lon1Rad
        val a = sin(dLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun estimateWCA(tc: Double, windDir: Int, windSpd: Int, tas: Int): Double {
        val angle = Math.toRadians((windDir - tc + 360) % 360)
        return Math.toDegrees(asin((windSpd * sin(angle)) / tas))
    }

    private fun estimateGroundSpeed(tas: Int, windDir: Int, windSpd: Int, tc: Double): Double {
        val angle = Math.toRadians((windDir - tc + 360) % 360)
        return tas + windSpd * cos(angle)
    }

    private fun formatETA(etaMinutes: Double): String {
        return if (etaMinutes >= 60) {
            val hours = (etaMinutes / 60).toInt()
            val minutes = (etaMinutes % 60).toInt()
            String.format("%d:%02d", hours, minutes)
        } else {
            val minutes = etaMinutes.toInt()
            String.format("%d", minutes)
        }
    }

    private fun Double.round1(): Double = String.format("%.1f", this).toDouble()
}
