package com.airportweather.map.utils


data class Airport(
    //APT_BASE
    val icao: String,   //ICAO_ID
    val id: String,     //ARPT_ID
    val name: String,   //ARPT_NAME
    val city: String,   //CITY
    val state: String,  //STATE_CODE
    val lat: Double,    //LAT_DECIMAL
    val lon: Double,    //LON_DECIMAL
    val elev: Float = 0f,  //ELEV
    val fuelTypes: List<String> = emptyList(), //FUEL_TYPES

    // APT_CON	ARPT_ID
    // APT_RMK	ARPT_ID
    // APT_RWY	ARPT_ID
    val fuelPrice: Double = 0.0,
)