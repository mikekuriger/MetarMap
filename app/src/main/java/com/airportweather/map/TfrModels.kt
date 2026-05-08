package com.airportweather.map

import kotlinx.serialization.Serializable

@Serializable
data class TFRGeometry(
    val type: String,
    val coordinates: List<List<List<Double>>>
)

@Serializable
data class TFRProperties(
    val description: String,
    val notam: String,
    val dateIssued: String,
    val dateEffective: String,
    val dateExpire: String,
    val type: String,
    val altitudeMin: String,
    val altitudeMax: String,
    val facility: String,
    //val fullDescription: String
)

@Serializable
data class TFRFeature(
    val properties: TFRProperties,
    val geometry: TFRGeometry
)
