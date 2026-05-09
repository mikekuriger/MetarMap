package com.airportweather.map

import com.google.android.gms.maps.model.LatLng

data class GpsData(
    val latitude: Double,
    val longitude: Double,
    val altitudeFt: Double,
    val speedKnots: Double,
    val heading: Double,
    val fixQuality: Double,
    val satellites: Double,
    val satellitesTracked: Double,
    val satellitesSeen: Double,
    val horizontalAccuracy: Double,
    val verticalAccuracy: Double,
    val temperature: Double,
    val pressureAltitude: Double,
    val verticalSpeed: Double
)

data class TrafficTarget(
    val hex: String,
    val tail: String,
    val squawk: String,
    val positionValid: Boolean,
    val lat: Double,
    val lon: Double,
    val distanceNm: Double,
    val bearing: Int,
    val altitudeFt: Int,
    val verticalSpeed: Int,
    val speedKts: Int,
    val course: Int,
    val signalStrength: Double,
    val ageSeconds: Double,
    val lastUpdated: Long = System.currentTimeMillis(),
    var lastKnownPosition: LatLng = LatLng(lat, lon)
)
