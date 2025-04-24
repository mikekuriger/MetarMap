package com.airportweather.map

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Waypoint(
    val name: String,
    val type: String,
    val lat: Double,
    val lon: Double,
    val elev: Float = 0f,
    val showDist: Boolean = true,
    val visible: Boolean = true,
    val magVar: Double = 0.0
) : Parcelable
