package com.airportweather.map

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Waypoint(
    val name: String,
    val type: String,
    val lat: Float,
    val lon: Float,
    val elev: Float = 0f,
    val showDist: Boolean = true,
    val visible: Boolean = true
) : Parcelable
