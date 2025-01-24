package com.airportweather.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PatternItem
import com.google.android.gms.maps.model.PolygonOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import com.google.maps.android.clustering.ClusterManager
import com.google.maps.android.clustering.view.DefaultClusterRenderer
import com.google.maps.android.clustering.ClusterItem
import com.google.maps.android.data.geojson.GeoJsonFeature
import com.google.maps.android.data.geojson.GeoJsonLayer
import com.google.maps.android.data.geojson.GeoJsonLineStringStyle
import com.google.maps.android.data.geojson.GeoJsonPointStyle
import com.google.maps.android.data.geojson.GeoJsonPolygon
import com.google.maps.android.data.geojson.GeoJsonPolygonStyle
import org.json.JSONArray
import org.json.JSONObject
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
    val wxString: String?,      // Example: +SN FZFG
    val skyCover1: String?,            // Example: CLR
    val cloudBase1: Int?,       // Example: 1000
    val skyCover2: String?,            // Example: CLR
    val cloudBase2: Int?,       // Example: 1000
    val skyCover3: String?,            // Example: CLR
    val cloudBase3: Int?,       // Example: 1000
    val skyCover4: String?,            // Example: CLR
    val cloudBase4: Int?,       // Example: 1000
    val flightCategory: String?, // Example: "VFR"
    val metarType: String?,      // Example: "METAR"
    val elevationM: Int?         // Example: 221
)
data class TAF(
    val stationId: String,
    val visibility: String?, // Example: "6+"
    val skyCover: List<String?>, // Example: ["BKN", "SCT"]
    val cloudBase: List<Int?>, // Example: [25000, 30000]
    var flightCategory: String? = null
)

suspend fun downloadAndUnzipMetarData(filesDir: File): File {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://aviationweather.gov/data/cache/metars.cache.csv.gz")
            val connection = url.openConnection()
            val inputStream: InputStream = GZIPInputStream(connection.getInputStream())

            val outputFile = File(filesDir, "metars.cache.csv")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                // Log success
                println("Downloaded METAR data to ${outputFile.absolutePath}, size: ${outputFile.length()} bytes")
            } else {
                throw Exception("Downloaded METAR file is empty or missing")
            }

            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Error downloading or unzipping METAR data: ${e.message}")
        }
    }
}
suspend fun downloadAndUnzipTafData(filesDir: File): File {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://aviationweather.gov/data/cache/tafs.cache.csv.gz")
            val connection = url.openConnection()
            val inputStream: InputStream = GZIPInputStream(connection.getInputStream())

            val outputFile = File(filesDir, "tafs.cache.csv")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                // Log success
                println("Downloaded TAF data to ${outputFile.absolutePath}, size: ${outputFile.length()} bytes")
            } else {
                throw Exception("Downloaded TAF file is empty or missing")
            }

            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Error downloading or unzipping TAF data: ${e.message}")
        }
    }
}
fun parseMetarCsv(file: File): List<METAR> {
    return file.bufferedReader().useLines { lines ->
        lines.dropWhile { it.isBlank() || !it.startsWith("raw_text") } // Skip header
            .drop(1) // Skip the "raw_text" line
            .mapNotNull { line ->
                try {
                    parseCsvLineToMETAR(line)
                } catch (e: Exception) {
                    println("Error parsing line: $line")
                    e.printStackTrace()
                    null
                }
            }
            .toList()
    }
}
fun parseCsvLineToMETAR(line: String): METAR? {
    val fields = line.split(",")
    return try {
        METAR(
            stationId = fields[1],
            observationTime = fields[2],
            latitude = fields[3].toDouble(),
            longitude = fields[4].toDouble(),
            tempC = fields[5].toDoubleOrNull(),
            dewpointC = fields[6].toDoubleOrNull(),
            windDirDegrees = fields[7].toIntOrNull(),
            windSpeedKt = fields[8].toIntOrNull(),
            windGustKt = fields[9].toIntOrNull(),
            visibility = fields[10],
            altimeterInHg = fields[11].toDoubleOrNull(),
            wxString = fields[21],
            skyCover1 = fields.getOrNull(22),
            cloudBase1 = fields.getOrNull(23)?.toIntOrNull(),
            skyCover2 = fields.getOrNull(24),
            cloudBase2 = fields.getOrNull(25)?.toIntOrNull(),
            skyCover3 = fields.getOrNull(26),
            cloudBase3 = fields.getOrNull(27)?.toIntOrNull(),
            skyCover4 = fields.getOrNull(28),
            cloudBase4 = fields.getOrNull(29)?.toIntOrNull(),
            flightCategory = fields[30],
            metarType = fields[42],
            elevationM = fields[43].toIntOrNull()
        )
    } catch (e: Exception) {
        println("Failed to parse line: $line")
        e.printStackTrace()
        null
    }
}
fun determineTAFConditions(forecast: TAF): String {
    // Parse visibility (convert "6+" to a double)
    val visibilityMiles = forecast.visibility?.replace("+", "")?.toDoubleOrNull() ?: 0.0

    // Determine the ceiling from the lowest cloud base
    val relevantCloudBases = forecast.cloudBase.filterNotNull() // Remove nulls
    val ceiling = if (relevantCloudBases.isNotEmpty()) {
        relevantCloudBases.minOrNull() ?: Int.MAX_VALUE
    } else {
        Int.MAX_VALUE // Clear skies
    }

    return when {
        ceiling > 3000 && visibilityMiles > 5 -> "VFR"
        ceiling in 1000..3000 || (visibilityMiles in 3.0..5.0) -> "MVFR"
        ceiling in 500..999 || (visibilityMiles in 1.0..2.9) -> "IFR"
        ceiling < 500 || visibilityMiles < 1.0 -> "LIFR"
        else -> "Unknown"
    }
}
fun parseTAFCsv(file: File): List<TAF> {
    return file.bufferedReader().useLines { lines ->
        lines.dropWhile { it.isBlank() || !it.startsWith("raw_text") } // Skip header
            .drop(1) // Skip the "raw_text" line
            .mapNotNull { line ->
                val fields = line.split(",")
                try {
                    val taf = TAF(
                        stationId = fields[1],                   // KBUR
                        visibility = fields.getOrNull(21),       // 6+
                        skyCover = listOf(
                            fields.getOrNull(26),
                            fields.getOrNull(29),
                            fields.getOrNull(32)
                        ),
                        cloudBase = listOf(
                            fields.getOrNull(27)?.toIntOrNull(),
                            fields.getOrNull(30)?.toIntOrNull(),
                            fields.getOrNull(33)?.toIntOrNull()
                        ),
                    )
                    taf.flightCategory = determineTAFConditions(taf)
                    taf
                } catch (e: Exception) {
                    println("Error parsing TAF line: $line")
                    e.printStackTrace()
                    null
                }
            }
            .toList()
    }
}
fun calculateMetarAge(observationTime: String?): Int? {
    if (observationTime == null) return null

    // Updated date format to handle ISO 8601 format with 'T' and 'Z'
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    dateFormat.timeZone = TimeZone.getTimeZone("UTC") // Assuming observationTime is in UTC

    return try {
        val observationDate = dateFormat.parse(observationTime)
        val currentTime = Date()

        // Calculate difference in minutes
        val differenceInMillis = currentTime.time - (observationDate?.time ?: 0)
        (differenceInMillis / (1000 * 60)).toInt()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
fun getCurrentTimeLocalFormat(): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault())
    dateFormat.timeZone = TimeZone.getDefault() // Use the device's default timezone
    return dateFormat.format(Date())
}
fun calculateDensityAltitude(elevation: Int?, tempC: Double?, altimeter: Double = 29.92, dewpointC: Double? = null): Int {
    if (elevation == null || tempC == null) return 0
    // Step 1: Calculate pressure altitude
    val pressureAlt = elevation + ((29.92 - altimeter) * 1000)
    // Step 2: Calculate standard temperature at elevation (ISA)
    val standardTemp = 15.0 - (elevation * 2.0 / 1000.0)
    // Step 3: Calculate temperature deviation
    val tempDeviation = tempC - standardTemp
    // Step 4: Calculate density altitude adjustment for temperature
    val tempAdjustment = (120 * tempDeviation)
    // Step 5: Calculate humidity effect if dewpoint is provided
    val humidityAdjustment = if (dewpointC != null) {
        val saturationVaporPressure = 6.11 * 10.0.pow((7.5 * tempC) / (237.7 + tempC))
        val actualVaporPressure = 6.11 * 10.0.pow((7.5 * dewpointC) / (237.7 + dewpointC))
        val relativeHumidity = (actualVaporPressure / saturationVaporPressure) * 100
        val vaporPressureEffect = actualVaporPressure * 0.125
        -vaporPressureEffect
    } else {
        0.0
    }

    // Step 6: Calculate final density altitude
    return (pressureAlt + tempAdjustment + humidityAdjustment).toInt()
}
fun calculateHumidity(tempC: Double?, dewpointC: Double?): Int {
    if (tempC == null || dewpointC == null) return 0
    val humidity = 100 * exp(
        (17.625 * dewpointC) / (dewpointC + 243.04) -
                (17.625 * tempC) / (tempC + 243.04)
    )
    return humidity.toInt()
}
fun formatClouds(metars: METAR): String {
    val cloudLayers = listOfNotNull(
        metars.skyCover1?.let { cover -> if (metars.cloudBase1 != null) "$cover ${metars.cloudBase1}'" else null },
        metars.skyCover2?.let { cover -> if (metars.cloudBase2 != null) "$cover ${metars.cloudBase2}'" else null },
        metars.skyCover3?.let { cover -> if (metars.cloudBase3 != null) "$cover ${metars.cloudBase3}'" else null },
        metars.skyCover4?.let { cover -> if (metars.cloudBase4 != null) "$cover ${metars.cloudBase4}'" else null }
    )

    return if (cloudLayers.isEmpty()) {
        "Clear skies"
    } else {
        cloudLayers.joinToString(", ")
    }
}
fun metersToFeet(meters: Int): Int = (meters * 3.28084).roundToInt()
@SuppressLint("DefaultLocale")
fun celsiusToFahrenheit(celsius: Double): Double = String.format("%.1f", celsius * 9 / 5 + 32).toDouble()

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }

    private fun checkLocationPermission() {
        if (::mMap.isInitialized) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            } else {
                enableMyLocation()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
            }
        }
    }

    private fun enableMyLocation() {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                mMap.isMyLocationEnabled = true
                moveToCurrentLocation()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun moveToCurrentLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 10f))
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun createDotBitmap(size: Int, fillColor: Int, borderColor: Int, borderWidth: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.isAntiAlias = true

        // Draw the border
        paint.color = Color.BLACK
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)

        // Draw the TAF (outer circle)
        paint.color = borderColor
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 1, paint)

        // Draw the METAR (filled inner circle)
        paint.color = fillColor
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - borderWidth, paint)

        return bitmap
    }

    // Update markers based on visible map area
    fun updateVisibleMarkers(metars: List<METAR>, tafs: List<TAF>) {
        val visibleBounds = mMap.projection.visibleRegion.latLngBounds
        //mMap.clear()

        for (metar in metars) {
            val location = LatLng(metar.latitude, metar.longitude)
            if (visibleBounds.contains(location)) {
                val dotSize = 60
                val borderWidth = 13
                // val borderColor = Color.BLACK
                // Determine the METAR circle color based on flight category
                val circleColor = when (metar.flightCategory) {
                    "VFR" -> Color.GREEN
                    "MVFR" -> Color.parseColor("#0080FF") // BLUE
                    "IFR" -> Color.RED
                    "LIFR" -> Color.parseColor("#FF00FF") // PURPLE
                    else -> Color.WHITE
                }

                // Find the corresponding TAF for this METAR station
                val taf = tafs.find { it.stationId == metar.stationId }

                // Determine the TAF border color based on forecast flight condition
                val borderColor = when (taf?.flightCategory) {
                    "VFR" -> Color.GREEN
                    "MVFR" -> Color.parseColor("#0080FF") // Blue
                    "IFR" -> Color.RED
                    "LIFR" -> Color.parseColor("#FF00FF") // Purple
                    else -> circleColor
                }

                if (circleColor == Color.WHITE) continue
                val dotBitmap = createDotBitmap(dotSize, circleColor, borderColor, borderWidth)

                // Add a larger transparent marker as the click target
                mMap.addMarker(
                    MarkerOptions()
                        .position(location)
                        .icon(BitmapDescriptorFactory.fromBitmap(dotBitmap))
                        .anchor(0.5f, 0.5f)
                        .title(
                            metar.stationId + " - " + metar.flightCategory +
                                    (if (taf != null && taf.flightCategory != metar.flightCategory) " (TAF = ${taf.flightCategory})" else "")
                        )
                        .snippet(formatAirportDetails(metar))
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize the map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.mapType = GoogleMap.MAP_TYPE_NORMAL // Options: NORMAL, SATELLITE, TERRAIN, HYBRID

        mMap.setInfoWindowAdapter(CustomInfoWindowAdapter(this))
        checkLocationPermission()
        moveToCurrentLocation()

        // **Load GeoJSON Boundaries**
        loadAndDrawGeoJsonBoundariesEfficiently(googleMap, this)

        var selectedMarkerPosition: LatLng? = null

        // Handle marker click events
        mMap.setOnMarkerClickListener { marker ->
            if (selectedMarkerPosition == marker.position) {
                // If the same marker is clicked again, hide the info window
                marker.hideInfoWindow()
                selectedMarkerPosition = null
            } else {
                // Show the info window for the newly clicked marker
                marker.showInfoWindow()
                selectedMarkerPosition = marker.position
            }
            true // Return true to consume the click event
        }

        // Fetch METAR data in a coroutine
        lifecycleScope.launch {
            try {
                val metarFile = downloadAndUnzipMetarData(filesDir)
                val metars = parseMetarCsv(metarFile)
                val tafFile = downloadAndUnzipTafData(filesDir)
                val tafs = parseTAFCsv(tafFile)

                if (metars.isEmpty()) {
                    Toast.makeText(this@MainActivity, "No airports found in METAR data", Toast.LENGTH_LONG).show()
                } else {
                    // Update markers based on visible map area
                    mMap.setOnCameraIdleListener {
                        updateVisibleMarkers(metars, tafs)
                    }
                    // Initial rendering of markers based on the current visible map area
                    updateVisibleMarkers(metars, tafs)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(
                    this@MainActivity,
                    "Error fetching METAR data: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Format the airport details for the marker snippet
    private fun formatAirportDetails(metars: METAR): String {
        val ageInMinutes = calculateMetarAge(metars.observationTime)
        return """
        ${getCurrentTimeLocalFormat()} (${ageInMinutes} minutes old)
        ${if (metars.windSpeedKt == 0 || metars.windSpeedKt == null) {
            "Wind: Calm"
        } else {
            "Wind: ${metars.windDirDegrees ?: "VRB"}° @ ${metars.windSpeedKt} kt" +
                    (if (metars.windGustKt != null && metars.windGustKt > 0) ", Gust ${metars.windGustKt} kt" else "")
        }}
        Visibility: ${metars.visibility ?: "N/A"} sm
        Clouds: ${formatClouds(metars)}
        Temperature: ${metars.tempC ?: 0.0}°C (${celsiusToFahrenheit(metars.tempC ?: 0.0)}°F)
        Dewpoint: ${metars.dewpointC ?: 0.0}°C (${celsiusToFahrenheit(metars.dewpointC ?: 0.0)}°F)
        Altimeter: ${metars.altimeterInHg ?: 0.0}
        Humidity: ${calculateHumidity(metars.tempC, metars.dewpointC)}%
        DA: ${
            if (metars.elevationM != null && metars.tempC != null && metars.altimeterInHg != null && metars.dewpointC != null) {
                val elevationFeet = metersToFeet(metars.elevationM)
                val humidity = calculateHumidity(metars.tempC, metars.dewpointC)
                calculateDensityAltitude(elevationFeet, metars.tempC, metars.altimeterInHg, humidity.toDouble())
            } else {
                "N/A"
            }
        }
    """.trimIndent()

    }

    fun loadAndDrawGeoJsonBoundariesEfficiently(
        map: GoogleMap,
        context: Context
    ) {
        try {
            val inputStream = context.resources.openRawResource(R.raw.test)
            val geoJsonString = inputStream.bufferedReader().use { it.readText() }
            val geoJsonObject = JSONObject(geoJsonString)
            val features = geoJsonObject.getJSONArray("features")

            map.clear() // Clear only when toggling layers
            for (i in 0 until features.length()) {
                val feature = features.getJSONObject(i)
                val geometry = feature.getJSONObject("geometry")
                val properties = feature.getJSONObject("properties")
                val type = geometry.getString("type")

                if (type == "Polygon") {
                    val coordinates =
                        extractPolygonCoordinates(geometry.getJSONArray("coordinates"))
                    val airspaceTypeCode = properties.optString("TYPE_CODE", "Unknown")

                    // Handle "MODE-C" airspace type
                    if (airspaceTypeCode == "MODE-C") {
                        val strokeColor = Color.argb(90, 200, 63, 200)
                        //val fillColor = Color.argb(0, 0, 0, 0)
                        val strokeWidth = 2f

                        map.addPolygon(
                            PolygonOptions()
                                .addAll(coordinates)
                                .strokeColor(strokeColor)
                                //.fillColor(fillColor)
                                .strokeWidth(strokeWidth)
                        )
                    }

                    val airspaceClass = properties.optString("CLASS", "Unknown")
                    if (airspaceTypeCode == "CLASS") {
                        // Declare variables to hold the results
                        val strokeColor: Int
                        val fillColor: Int
                        val strokeWidth: Float
                        val strokePattern: List<PatternItem>?

                        // Use when to set values
                        when (airspaceClass) {
                            "B" -> {
                                strokeColor = Color.argb(128, 0, 64, 255)
                                fillColor = Color.argb(20, 0, 64, 255)
                                strokeWidth = 8f
                                strokePattern = null // Solid line
                            }
                            "C" -> {
                                strokeColor = Color.MAGENTA
                                //strokeColor = Color.argb(128, 143, 69, 92)
                                fillColor = Color.argb(20, 150, 63, 150)
                                strokeWidth = 4f
                                strokePattern = null // Solid line
                            }
                            "D" -> {
                                strokeColor = Color.parseColor("#0080FF")
                                fillColor = Color.argb(20, 0, 128, 255)
                                strokeWidth = 4f
                                strokePattern = listOf(Dash(20f), Gap(10f)) // Dotted line
                            }
                            "E" -> {
                                strokeColor = Color.parseColor("#863F67")
                                fillColor = Color.argb(20, 134, 63, 103)
                                strokeWidth = 4f
                                strokePattern = listOf(Dash(10f), Gap(5f)) // Dotted line
                            }
                            else -> {
                                strokeColor = Color.TRANSPARENT
                                fillColor = Color.argb(0, 0, 0, 0)
                                strokeWidth = 1f
                                strokePattern = null // No line
                            }
                        }

                        map.addPolygon(
                            PolygonOptions()
                                .addAll(coordinates)
                                .strokeColor(strokeColor)
                                //.fillColor(fillColor)
                                .strokeWidth(strokeWidth)
                                .strokePattern(strokePattern)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("GeoJSON", "Error loading boundaries: ${e.localizedMessage}")
        }
    }

    fun extractPolygonCoordinates(coordinatesArray: JSONArray): List<LatLng> {
        val points = mutableListOf<LatLng>()
        for (j in 0 until coordinatesArray.length()) {
            val ring = coordinatesArray.getJSONArray(j)
            for (k in 0 until ring.length()) {
                val coord = ring.getJSONArray(k)
                points.add(LatLng(coord.getDouble(1), coord.getDouble(0)))
            }
        }
        return points
    }

}
