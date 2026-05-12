// File: FlightPlanUtils.kt
package com.airportweather.map.utils

import android.content.Context
import android.location.Location
import com.airportweather.map.FlightLeg
import com.airportweather.map.FlightPhase
import com.airportweather.map.FlightPlan
import com.airportweather.map.aircraft.AircraftRepository
import com.airportweather.map.aircraft.AircraftSelectionStore
import com.airportweather.map.aircraft.PerformanceProfile
import kotlin.math.*

object FlightPlanHolder {
    var currentPlan: FlightPlan? = null
}

// Fallbacks used when no aircraft profile is selected (or the profile is
// missing a particular value). Match the pre-aircraft hardcoded defaults
// so behaviour for users who haven't created an aircraft yet is unchanged.
private const val DEFAULT_FALLBACK_TAS = 95
private const val DEFAULT_FALLBACK_FUEL_BURN = 6.0
private const val DEFAULT_FALLBACK_CRUISE_ALT = 5500

/**
 * Build a [FlightPlan] from a whitespace-separated string of waypoint codes.
 * Cruise TAS, fuel burn, and cruise altitude are resolved from the currently-
 * selected aircraft profile when null. The pre-aircraft defaults kick in if
 * no aircraft is configured or a profile field is 0.
 *
 * When the active profile has climb-rate data, a CLIMB segment is prepended;
 * symmetric DESCENT segment is appended. These show in the nav log as
 * synthetic rows so the pilot sees TOC/TOD breakdowns explicitly.
 */
fun buildFlightPlanFromText(
    context: Context,
    rawText: String,
    tas: Int? = null,
    fuelBurn: Double? = null,
    cruiseAltitude: Int? = null,
    windDir: Int = 0,
    windSpeed: Int = 0,
    startingFuel: Double = 0.0,
    currentLocation: Location? = null,
): FlightPlan? {
    if (rawText.isBlank()) return null

    val selection = AircraftSelectionStore(context).resolveSelection(AircraftRepository.get(context.filesDir))
    val profile = selection?.profile
    val effectiveTas = tas
        ?: profile?.cruiseTas?.takeIf { it > 0 }
        ?: DEFAULT_FALLBACK_TAS
    val effectiveFuelBurn = fuelBurn
        ?: profile?.cruiseGph?.takeIf { it > 0.0 }
        ?: DEFAULT_FALLBACK_FUEL_BURN
    val effectiveAlt = cruiseAltitude
        ?: selection?.aircraft?.altitudes?.defaultCruiseAltitude?.takeIf { it > 0 }
        ?: DEFAULT_FALLBACK_CRUISE_ALT

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

        val resolvedLegs = FlightPlanUtils.generateLegsWithPhases(
            waypoints = listOf(from, to),
            profile = profile,
            cruiseAltitude = effectiveAlt,
            cruiseTas = effectiveTas,
            cruiseFuelBurn = effectiveFuelBurn,
            windDir = windDir,
            windSpeed = windSpeed,
        )
        val mutableLegs = resolvedLegs.toMutableList()
        mutableLegs.firstOrNull()?.active = true
        return FlightPlan(
            legs = mutableLegs,
            windDir = windDir,
            windSpeed = windSpeed,
            startingFuel = startingFuel,
        )
    }

    // Normal multi-leg
    if (waypoints.size < 2) return null

    val resolvedLegs = FlightPlanUtils.generateLegsWithPhases(
        waypoints = waypoints,
        profile = profile,
        cruiseAltitude = effectiveAlt,
        cruiseTas = effectiveTas,
        cruiseFuelBurn = effectiveFuelBurn,
        windDir = windDir,
        windSpeed = windSpeed,
    )
    val mutableLegs = resolvedLegs.toMutableList()
    mutableLegs.firstOrNull()?.active = true
    return FlightPlan(
        legs = mutableLegs,
        windDir = windDir,
        windSpeed = windSpeed,
        startingFuel = startingFuel,
    )
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
        val magneticHeading = ((trueCourse + wca + from.magVar).roundToInt() + 360) % 360
        val groundspeed = estimateGroundSpeed(tas, windDir, windSpeed, trueCourse).roundToInt()
        val eteMinutes = if (groundspeed > 0) (distanceNM / groundspeed) * 60 else 0.0
        val fuelUsed = ((eteMinutes / 60) * fuelBurn).round1()
        val ete = formatETA(eteMinutes)

        return FlightLeg(
            from = from,
            to = to,
            distanceNM = distanceNM,
            trueCourse = trueCourse.roundToInt(),
            magneticHeading = magneticHeading,
            cruisingAltitude = cruiseAltitude,
            tas = tas,
            groundspeed = groundspeed,
            ete = ete,
            fuelUsed = fuelUsed
        )
    }


    /**
     * Builds a list of legs spanning [waypoints], inserting synthetic TOC and
     * TOD waypoints where the climb completes and the descent begins. Each
     * resulting leg has exactly one [FlightPhase], so its TAS / fuel burn /
     * altitude reflect what the aircraft is actually doing on that segment.
     *
     * Algorithm:
     *  1. Compute climb_time and descent_time from elevation deltas and the
     *     profile's rates.
     *  2. Walk forward: convert each leg using climb TAS until cumulative time
     *     exhausts climb_time. The leg crossing TOC is split into a climb
     *     portion + cruise portion, with a synthetic "TOC" waypoint inserted.
     *  3. Walk backward from the destination doing the same for descent.
     *  4. Any leg not marked CLIMB or DESCENT defaults to CRUISE.
     *
     * If [profile] is null or lacks climb/descent rate data, every leg is
     * CRUISE and uses [cruiseTas] / [cruiseFuelBurn].
     */
    fun generateLegsWithPhases(
        waypoints: List<Waypoint>,
        profile: PerformanceProfile?,
        cruiseAltitude: Int,
        cruiseTas: Int,
        cruiseFuelBurn: Double,
        windDir: Int = 0,
        windSpeed: Int = 0,
    ): List<FlightLeg> {
        val climbTas = profile?.climbTas?.takeIf { it > 0 } ?: cruiseTas
        val climbGph = profile?.climbGph?.takeIf { it > 0 } ?: cruiseFuelBurn
        val descentTas = profile?.descentTas?.takeIf { it > 0 } ?: cruiseTas
        val descentGph = profile?.descentGph?.takeIf { it > 0 } ?: cruiseFuelBurn

        // Total time the aircraft will spend in each non-cruise phase, in
        // minutes. Zero (or null profile) means we never enter that phase —
        // the legs all stay CRUISE.
        val depElev = waypoints.first().elev.toInt()
        val destElev = waypoints.last().elev.toInt()
        val climbMinutesTotal = profile?.let {
            if (it.climbRate > 0 && cruiseAltitude > depElev)
                (cruiseAltitude - depElev).toDouble() / it.climbRate
            else 0.0
        } ?: 0.0
        val descentMinutesTotal = profile?.let {
            if (it.descentRate > 0 && cruiseAltitude > destElev)
                (cruiseAltitude - destElev).toDouble() / it.descentRate
            else 0.0
        } ?: 0.0

        // First pass: cruise-as-baseline legs (one per consecutive waypoint
        // pair), all marked CRUISE. We then patch in climb/descent and TOC/TOD.
        val baseLegs = mutableListOf<FlightLeg>()
        for (i in 0 until waypoints.size - 1) {
            baseLegs += buildLeg(
                from = waypoints[i],
                to = waypoints[i + 1],
                tas = cruiseTas,
                fuelBurn = cruiseFuelBurn,
                cruiseAltitude = cruiseAltitude,
                phase = FlightPhase.CRUISE,
                windDir = windDir,
                windSpeed = windSpeed,
            )
        }

        // Forward pass: convert legs to CLIMB until climbMinutesTotal is used.
        // If a leg straddles TOC, split it with a synthetic TOC waypoint.
        var climbRemaining = climbMinutesTotal
        if (climbRemaining > 0) {
            var i = 0
            while (i < baseLegs.size && climbRemaining > 0) {
                val leg = baseLegs[i]
                val climbLeg = buildLeg(
                    from = leg.from, to = leg.to,
                    tas = climbTas, fuelBurn = climbGph,
                    cruiseAltitude = cruiseAltitude,
                    phase = FlightPhase.CLIMB,
                    windDir = windDir, windSpeed = windSpeed,
                )
                val climbLegMinutes = eteToMinutes(climbLeg.ete).toDouble()
                if (climbLegMinutes <= climbRemaining + 0.01) {
                    // Whole leg fits in climb.
                    baseLegs[i] = climbLeg
                    climbRemaining -= climbLegMinutes
                    i++
                } else {
                    // Split: climb portion ends at TOC waypoint; cruise portion
                    // resumes from TOC to leg.to.
                    val frac = (climbRemaining / climbLegMinutes).coerceIn(0.0, 1.0)
                    val toc = interpolateWaypoint(leg.from, leg.to, frac, name = "TOC")
                    val climbPart = buildLeg(
                        from = leg.from, to = toc,
                        tas = climbTas, fuelBurn = climbGph,
                        cruiseAltitude = cruiseAltitude,
                        phase = FlightPhase.CLIMB,
                        windDir = windDir, windSpeed = windSpeed,
                    )
                    val cruisePart = buildLeg(
                        from = toc, to = leg.to,
                        tas = cruiseTas, fuelBurn = cruiseFuelBurn,
                        cruiseAltitude = cruiseAltitude,
                        phase = FlightPhase.CRUISE,
                        windDir = windDir, windSpeed = windSpeed,
                    )
                    baseLegs[i] = climbPart
                    baseLegs.add(i + 1, cruisePart)
                    climbRemaining = 0.0
                    i += 2
                }
            }
        }

        // Backward pass: convert tail-end CRUISE legs to DESCENT, splitting at
        // TOD if needed. Stop when we've consumed descentMinutesTotal or when
        // we hit a non-CRUISE leg (don't overwrite the climb).
        var descentRemaining = descentMinutesTotal
        if (descentRemaining > 0) {
            var j = baseLegs.size - 1
            while (j >= 0 && descentRemaining > 0 && baseLegs[j].phase == FlightPhase.CRUISE) {
                val leg = baseLegs[j]
                val descentLeg = buildLeg(
                    from = leg.from, to = leg.to,
                    tas = descentTas, fuelBurn = descentGph,
                    cruiseAltitude = cruiseAltitude,
                    phase = FlightPhase.DESCENT,
                    windDir = windDir, windSpeed = windSpeed,
                )
                val descentLegMinutes = eteToMinutes(descentLeg.ete).toDouble()
                if (descentLegMinutes <= descentRemaining + 0.01) {
                    baseLegs[j] = descentLeg
                    descentRemaining -= descentLegMinutes
                    j--
                } else {
                    // Split: cruise portion ends at TOD waypoint; descent
                    // portion resumes from TOD to leg.to.
                    val frac = ((descentLegMinutes - descentRemaining) / descentLegMinutes).coerceIn(0.0, 1.0)
                    val tod = interpolateWaypoint(leg.from, leg.to, frac, name = "TOD")
                    val cruisePart = buildLeg(
                        from = leg.from, to = tod,
                        tas = cruiseTas, fuelBurn = cruiseFuelBurn,
                        cruiseAltitude = cruiseAltitude,
                        phase = FlightPhase.CRUISE,
                        windDir = windDir, windSpeed = windSpeed,
                    )
                    val descentPart = buildLeg(
                        from = tod, to = leg.to,
                        tas = descentTas, fuelBurn = descentGph,
                        cruiseAltitude = cruiseAltitude,
                        phase = FlightPhase.DESCENT,
                        windDir = windDir, windSpeed = windSpeed,
                    )
                    baseLegs[j] = cruisePart
                    baseLegs.add(j + 1, descentPart)
                    descentRemaining = 0.0
                    break
                }
            }
        }

        return baseLegs
    }

    /** Builds a single leg with the given phase, TAS, and fuel burn. */
    private fun buildLeg(
        from: Waypoint,
        to: Waypoint,
        tas: Int,
        fuelBurn: Double,
        cruiseAltitude: Int,
        phase: FlightPhase,
        windDir: Int,
        windSpeed: Int,
    ): FlightLeg {
        val trueCourse = calculateTrueCourse(from.lat, from.lon, to.lat, to.lon)
        val distanceNM = calculateDistance(from.lat, from.lon, to.lat, to.lon).round1()
        val wca = estimateWCA(trueCourse, windDir, windSpeed, tas)
        val magneticHeading = ((trueCourse + wca + from.magVar).roundToInt() + 360) % 360
        val groundspeed = estimateGroundSpeed(tas, windDir, windSpeed, trueCourse).roundToInt()
        val eteMinutes = if (groundspeed > 0) (distanceNM / groundspeed) * 60 else 0.0
        val fuelUsed = ((eteMinutes / 60) * fuelBurn).round1()
        return FlightLeg(
            from = from, to = to,
            distanceNM = distanceNM,
            trueCourse = trueCourse.roundToInt(),
            magneticHeading = magneticHeading,
            cruisingAltitude = cruiseAltitude,
            tas = tas,
            groundspeed = groundspeed,
            ete = formatETA(eteMinutes),
            fuelUsed = fuelUsed,
            phase = phase,
        )
    }

    /** Linearly interpolates a synthetic waypoint between [from] and [to] at
     *  position [frac] (0..1 along the great-circle approximation). magVar
     *  averages between the endpoints. */
    private fun interpolateWaypoint(from: Waypoint, to: Waypoint, frac: Double, name: String): Waypoint {
        val lat = from.lat + (to.lat - from.lat) * frac
        val lon = from.lon + (to.lon - from.lon) * frac
        val magVar = from.magVar + (to.magVar - from.magVar) * frac
        return Waypoint(
            name = name,
            type = "SYN",
            lat = lat,
            lon = lon,
            elev = 0f,
            showDist = false,
            visible = false,
            magVar = magVar,
        )
    }

    private fun eteToMinutes(ete: String): Int {
        val parts = ete.split(":")
        return if (parts.size == 2) parts[0].toInt() * 60 + parts[1].toInt()
        else parts[0].toIntOrNull() ?: 0
    }

    @Deprecated("Use generateLegsWithPhases", ReplaceWith(""))
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
            val magneticHeading = ((trueCourse + wca + from.magVar).roundToInt() + 360) % 360
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
                    magneticHeading = magneticHeading,
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
        // windDir is where the wind blows FROM. The component of the wind
        // along the course is windSpd*cos(windDir - tc), positive when wind
        // FROM equals the course (i.e., headwind blowing at you). Headwind
        // reduces ground speed → subtract. Equivalently this is
        // TAS + windSpd*cos((windDir+180) - tc), using the direction the wind
        // is blowing TO.
        val angle = Math.toRadians((windDir - tc + 360) % 360)
        return tas - windSpd * cos(angle)
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
