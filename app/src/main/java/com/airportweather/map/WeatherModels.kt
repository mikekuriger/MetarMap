package com.airportweather.map

import kotlinx.serialization.Serializable

@Serializable
data class METAR(
    val stationId: String,       // Example: "KBUR"
    val observationTime: String, // Example: "2025-01-18T00:53:00Z"
    val latitude: Double,        // Example: 34.1996
    val longitude: Double,       // Example: -118.365
    val tempC: Double?,          // Example: 13.3
    val dewpointC: Double?,      // Example: 6.1
    val windDirDegrees: Int?,    // Example: 190
    val windSpeedKt: Int?,       // Example: 6
    val windGustKt: Int?,        // Example: null
    val visibility: String?,     // Example: "10+"
    val altimeterInHg: Double?,  // Example: 30.14
    val wxString: String?,       // Example: +SN FZFG
    val skyCover1: String?,      // Example: CLR
    val cloudBase1: Int?,        // Example: 1000
    val skyCover2: String?,
    val cloudBase2: Int?,
    val skyCover3: String?,
    val cloudBase3: Int?,
    val skyCover4: String?,
    val cloudBase4: Int?,
    val flightCategory: String?, // Example: "VFR"
    val metarType: String?,      // Example: "METAR"
    val elevationM: Int?         // Example: 221
)

@Serializable
data class TAF(
    val stationId: String,
    val visibility: String?,         // Example: "6+"
    val skyCover: List<String?>,     // Example: ["BKN", "SCT"]
    val cloudBase: List<Int?>,       // Example: [25000, 30000]
    var flightCategory: String? = null
)

@Serializable
data class MetarTafData(val metar: METAR, val taf: TAF?)

@Serializable
data class MarkerStyle(
    val size: Int,
    val fillColor: Int,
    val borderColor: Int,
    val borderWidth: Int,
    val showWindBarb: Boolean = false,
    val textOverlay: String? = null
)
