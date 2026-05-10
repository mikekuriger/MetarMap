package com.airportweather.map

import com.airportweather.map.utils.Waypoint
import com.google.android.gms.maps.model.LatLng

data class FlightData(
    val wpLocation: LatLng,
    val currentLeg: String,
    val track: Double,
    val bearing: Double,
    /** Distance to the NEXT waypoint, in nm. */
    val distance: Double,
    val groundSpeed: Double,
    val plannedAirSpeed: Int,
    val altitude: Double,
    /** ETA to the NEXT waypoint, formatted. */
    val eta: String,
    val waypoints: List<Waypoint>,
    /** Name of the final waypoint of the flight plan ("---" when no plan). */
    val finalDestinationName: String = "----",
    /** Total nm remaining from current position through all unfinished legs to the final destination. */
    val distanceToDestinationNm: Double = 0.0,
) {
    companion object {
        fun empty(): FlightData {
            return FlightData(
                wpLocation = LatLng(0.0, 0.0),
                currentLeg = "N/A",
                track = 0.0,
                bearing = 0.0,
                distance = 0.0,
                groundSpeed = 0.0,
                plannedAirSpeed = 100,
                altitude = 0.0,
                eta = "--:--",
                waypoints = emptyList(),
                finalDestinationName = "----",
                distanceToDestinationNm = 0.0,
            )
        }
    }
}
