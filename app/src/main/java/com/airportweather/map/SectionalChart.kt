package com.airportweather.map

data class SectionalChart(
    val name: String,
    val url: String,
    val fileSize: String,
    val totalSize: String,
    var isInstalled: Boolean,
    var isDownloading: Boolean = false,
    val fileName: String,
    val terminal: TerminalChart?,
    val terminalFileName: String?,
    val hasTerminal: Boolean
)

data class TerminalChart(
    val name: String,
    val url: String,
    val fileSize: String,
    var isInstalled: Boolean,
    var isDownloading: Boolean = false,
    val fileName: String
)
