package com.airportweather.map

data class SectionalChart(
    val name: String,
    val url: String,
    val fileSize: String,
    var isInstalled: Boolean,
    var isDownloading: Boolean = false,
    val fileName: String
) {
}
