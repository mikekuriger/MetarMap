package com.airportweather.map

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.text.SpannableStringBuilder
import android.view.Gravity
import android.view.MenuItem
import android.widget.ImageButton
import androidx.drawerlayout.widget.DrawerLayout
import com.airportweather.map.databinding.ActivityMainBinding
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.navigation.NavigationView
import com.google.maps.android.ui.IconGenerator
import okhttp3.OkHttpClient
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
@Serializable
data class TAF(
    val stationId: String,
    val visibility: String?, // Example: "6+"
    val skyCover: List<String?>, // Example: ["BKN", "SCT"]
    val cloudBase: List<Int?>, // Example: [25000, 30000]
    var flightCategory: String? = null
)
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
data class FlightPlan(
    val wpLocation: LatLng,
    val currentLeg: String,
    val bearing: Double,
    val distance: Double,
    val groundSpeed: Double,
    val plannedAirSpeed: Int,
    val altitude: Double,
    val eta: String,
    val waypoints: List<String>
)
data class FlightData(
    val wpLocation: LatLng,
    val currentLeg: String,
    val track: Double,
    val bearing: Double,
    val distance: Double,
    val groundSpeed: Double,
    val plannedAirSpeed: Int,
    val altitude: Double,
    val eta: String,
    val waypoints: List<String>
)


// METAR
suspend fun downloadAndUnzipMetarData(filesDir: File): List<METAR> {
    return withContext(Dispatchers.IO) {
        try {
            // ✅ Load existing cached METARs
            val cachedMetars = loadMetarDataFromCache(filesDir)

            // ✅ Download and unzip new METAR data
            val url = URL("https://aviationweather.gov/data/cache/metars.cache.csv.gz")
            val connection = url.openConnection()
            val inputStream: InputStream = GZIPInputStream(connection.getInputStream())
            val outputFile = File(filesDir, "metars.cache.csv")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw Exception("Downloaded METAR file is empty or missing")
            }

            Log.d("METAR_DOWNLOAD", "Downloaded METAR data to ${outputFile.absolutePath}, size: ${outputFile.length()} bytes")

            // ✅ Parse the new METAR data
            val newMetars = parseMetarCsv(outputFile)

            // ✅ Merge new METARs with cached ones
            val mergedMetars = mergeMetarData(cachedMetars, newMetars)

            // ✅ Save the merged METARs back to the cache
            saveMetarDataToCache(mergedMetars, filesDir)

            return@withContext mergedMetars
        } catch (e: Exception) {
            Log.e("METAR_DOWNLOAD", "Error downloading or unzipping METAR data: ${e.message}")
            e.printStackTrace()
            return@withContext loadMetarDataFromCache(filesDir) // ✅ Load cached METARs if download fails
        }
    }
}
fun mergeMetarData(cachedMetars: List<METAR>, newMetars: List<METAR>): List<METAR> {
    val metarMap = cachedMetars.associateBy { it.stationId }.toMutableMap()

    newMetars.forEach { metar ->
        metarMap[metar.stationId] = metar // Replace old METAR if new one exists
    }

    return metarMap.values.toList()
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
    if (fields.size < 43) {
        Log.e("METAR_PARSE", "Skipping line due to insufficient fields: $line")
        return null // Skip malformed lines instead of crashing
    }

    // ✅ Filter: Only allow station IDs that start with "K"
//    if (!fields[1].startsWith("K")) {
//        //Log.d("METAR_PARSE", "Skipping non-US station: $fields[1]")
//        return null
//    }

    return try {
        METAR(
            stationId = fields[1],
            observationTime = fields[2],
            latitude = fields[3].toDouble(),
            longitude = fields[4].toDouble(),
            tempC = fields[5].toDoubleOrNull(),
            dewpointC = fields[6].toDoubleOrNull(),
            windDirDegrees = fields[7].toIntOrNull(),
//            windDirDegrees = when (fields[7].uppercase()) {
//                "VRB" -> -1  // Special value for variable wind
//                else -> fields[7].toIntOrNull()  // Normal numeric value or null
//            },
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
// TAF
suspend fun downloadAndUnzipTafData(filesDir: File): List<TAF> {
    return withContext(Dispatchers.IO) {
        try {
            // ✅ Load existing cached TAFs
            val cachedTafs = loadTafDataFromCache(filesDir)

            // ✅ Download and unzip new TAF data
            val url = URL("https://aviationweather.gov/data/cache/tafs.cache.csv.gz") // Correct TAF URL
            val connection = url.openConnection()
            val inputStream: InputStream = GZIPInputStream(connection.getInputStream())
            val outputFile = File(filesDir, "tafs.cache.csv") // Save as TAF file
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw Exception("Downloaded TAF file is empty or missing")
            }

            Log.d("TAF_DOWNLOAD", "Downloaded TAF data to ${outputFile.absolutePath}, size: ${outputFile.length()} bytes")

            // ✅ Parse the new TAF data
            val newTafs = parseTAFCsv(outputFile)

            // ✅ Merge new TAFs with cached ones
            val mergedTafs = mergeTafData(cachedTafs, newTafs)

            // ✅ Save the merged TAFs back to the cache
            saveTafDataToCache(mergedTafs, filesDir)

            return@withContext mergedTafs
        } catch (e: Exception) {
            Log.e("TAF_DOWNLOAD", "Error downloading or unzipping TAF data: ${e.message}")
            e.printStackTrace()
            return@withContext loadTafDataFromCache(filesDir) // ✅ Load cached TAFs if download fails
        }
    }
}
fun mergeTafData(cachedTafs: List<TAF>, newTafs: List<TAF>): List<TAF> {
    val tafMap = cachedTafs.associateBy { it.stationId }.toMutableMap()

    newTafs.forEach { taf ->
        tafMap[taf.stationId] = taf // ✅ Replace old TAF if new one exists
    }

    return tafMap.values.toList()
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
// TFR
suspend fun getOrDownloadTfrs(filesDir: File): File? {
    val geoJsonCacheDir = File(filesDir, "geojson").apply { mkdirs() }
    val tfrFile = File(geoJsonCacheDir, "tfrs.geojson")
    val maxAgeMillis = TimeUnit.HOURS.toMillis(1) // 1 hour threshold
    println("✅ getOrDownloadTfrs: ${tfrFile.absolutePath}, dir: $geoJsonCacheDir")

    // Check if the cached file is valid
    if (tfrFile.exists() && tfrFile.length() > 0 && System.currentTimeMillis() - tfrFile.lastModified() < maxAgeMillis) {
        println("✅ Using cached TFR GeoJSON: ${tfrFile.absolutePath}, size: ${tfrFile.length()} bytes")
        return tfrFile
    }

    // If file doesn't exist or is outdated, download a fresh copy
    return try {
        println("🔄 Downloading new TFR GeoJSON...")
        downloadTfrData(geoJsonCacheDir)
    } catch (e: IOException) {
        println("🚨 Failed to download TFR GeoJSON: ${e.message}")
        if (tfrFile.exists() && tfrFile.length() > 0) {
            println("⚠️ Using last cached version at ${tfrFile.absolutePath}")
            tfrFile
        } else {
            null // No valid file available
        }
    }
}
suspend fun downloadTfrData(filesDir: File): File {
    return withContext(Dispatchers.IO) {
        try {
//            val url = URL("https://raw.githubusercontent.com/airframesio/data/refs/heads/master/json/faa/tfrs.json")
            val url = URL("https://raw.githubusercontent.com/mikekuriger/MetarMap/refs/heads/main/scripts/tfrs.geojson")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: ${connection.responseCode}")
            }

            val inputStream: InputStream = connection.inputStream
            val outputFile = File(filesDir, "tfrs.geojson")
            println("✅ downloadTfrData: ${outputFile}, ${outputFile.absolutePath}")

            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            println("Downloaded TFR GeoJSON to ${outputFile.absolutePath}, size: ${outputFile.length()} bytes")
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            throw IOException("Error downloading TFR GeoJSON: ${e.message}")
        }
    }
}
fun parseTFRGeoJson(file: File): List<TFRFeature> {
    val tfrFeatures = mutableListOf<TFRFeature>()

    try {
        // Read the GeoJSON file content
        val geoJsonString = file.bufferedReader().use { it.readText() }
        val geoJsonObject = JSONObject(geoJsonString)
        val features = geoJsonObject.getJSONArray("features")

        // Iterate through features in GeoJSON
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val geometry = feature.getJSONObject("geometry")
            val properties = feature.getJSONObject("properties")
            val type = geometry.getString("type")
            val coordinates = geometry.getJSONArray("coordinates")

            if (type == "Polygon") {
                // Parse the coordinates
                val parsedCoordinates = mutableListOf<List<List<Double>>>()
                for (j in 0 until coordinates.length()) {
                    val ring = coordinates.getJSONArray(j)
                    val parsedRing = mutableListOf<List<Double>>()

                    for (k in 0 until ring.length()) {
                        val point = ring.getJSONArray(k)
                        val lng = point.getDouble(0)
                        val lat = point.getDouble(1)
                        parsedRing.add(listOf(lng, lat))
                    }
                    parsedCoordinates.add(parsedRing)
                }

                // Create a TFRGeometry object
                val tfrGeometry = TFRGeometry(type, parsedCoordinates)

                // Extract updated properties
                val description = properties.optString("description", "No description available")
                val notam = properties.optString("notam", "Unknown")
                val dateIssued = properties.optString("dateIssued", "Unknown")
                val dateEffective = properties.optString("dateEffective", "Unknown")
                val dateExpire = properties.optString("dateExpire", "Ongoing")
                val tfrType = properties.optString("type", "Unknown")
                val altitudeMin = properties.optString("lowerVal", "Surface")
                val altitudeMax = properties.optString("upperVal", "Unlimited")
                val facility = properties.optString("facility", "Unknown")
                //val fullDescription = properties.optString("fullDescription", "No details available")

                // Create TFRProperties object
                val tfrProperties = TFRProperties(
                    description, notam, dateIssued, dateEffective, dateExpire, tfrType, altitudeMin, altitudeMax, facility
                )

                // Add to list
                tfrFeatures.add(TFRFeature(tfrProperties, tfrGeometry))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        println("Error parsing TFR GeoJSON: ${e.message}")
    }

    return tfrFeatures
}

// Other functions...
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
        val actualVaporPressure = 6.11 * 10.0.pow((7.5 * dewpointC) / (237.7 + dewpointC))
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

class MainActivity : AppCompatActivity(), OnMapReadyCallback, NavigationView.OnNavigationItemSelectedListener {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var binding: ActivityMainBinding

    private var isFollowingUser = false
    private var areTFRsVisible = true
    private val tfrPolygons = mutableListOf<Polygon>()
    private var areAirspacesVisible = true
    private val airspacePolygons = mutableListOf<Polygon>()
    private var areMetarsVisible = true
    private val metarMarkers = mutableListOf<Marker>()
    private val tfrPolygonInfo = mutableMapOf<Polygon, MutableList<TFRProperties>>()
    private var metarData: List<METAR> = emptyList()
    private var tafData: List<TAF> = emptyList()
    private var currentLayerName: String = "FlightConditions"
    private var sectionalOverlay: TileOverlay? = null
    private var sectionalVisible = false
    private var terminalOverlay: TileOverlay? = null
    private var terminalVisible = false
    private var lastKnownUserLocation: LatLng? = null
    private val airportMap = mutableMapOf<String, LatLng>()
    private val airportMagVarMap: MutableMap<String, Double> = mutableMapOf()
    private val activeSpeed = 10
    private val plannedAirSpeed = 95 // make editable in the UI

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        // const val ACTIVE_SPEED = 10
    }

    sealed class MapLayer {
        data object FlightConditions : MapLayer()
        data object Wind : MapLayer()
        data object Temperature : MapLayer()
        data object Altimeter : MapLayer()
        data object Ceiling : MapLayer()
        data object Clouds : MapLayer()

        companion object {
            fun fromName(name: String): MapLayer {
                return when(name) {
                    "FlightConditions" -> FlightConditions
                    "Altimeter" -> Altimeter
                    "Temperature" -> Temperature
                    "Ceiling" -> Ceiling
                    "Clouds" -> Clouds
                    "Wind Barbs" -> Wind
                    else -> FlightConditions
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Initialize View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Load airports from CSV
        loadAirportsFromCSV()

        // ✅ Download databases if needed
        syncDatabaseFiles(this)

        // ✅ Toggle Flight Info Visibility
        binding.flightPlan.setOnClickListener {
            Log.d("FlightToggle", "Flight Plan button clicked")  // ✅ Log click event

            val isVisible = binding.flightInfoLayout.visibility == View.VISIBLE
            Log.d("FlightToggle", "Current visibility: $isVisible")  // ✅ Log visibility before toggle

            binding.flightInfoLayout.visibility = if (isVisible) View.GONE else View.VISIBLE

            binding.flightInfoLayout.bringToFront()
            binding.flightInfoLayout.requestLayout()

            Log.d("FlightToggle", "New visibility: ${binding.flightInfoLayout.visibility}")  // ✅ Log visibility after toggle
        }

        // ✅ Toggle Flight Dest visibility (testing)
//        binding.layers.setOnClickListener {
//            Log.d("FlightToggle", "Layer button clicked")  // ✅ Log click event
//
//            val isVisible = binding.flightInfoLayout2.visibility == View.VISIBLE
//            Log.d("FlightToggle", "Current visibility: $isVisible")  // ✅ Log visibility before toggle
//
//            binding.flightInfoLayout2.visibility = if (isVisible) View.GONE else View.VISIBLE
//
//            binding.flightInfoLayout2.bringToFront()
//            binding.flightInfoLayout2.requestLayout()
//
//            Log.d("FlightToggle", "New visibility: ${binding.flightInfoLayout2.visibility}")  // ✅ Log visibility after toggle
//        }

        // ✅ Initialize Navigation Drawer
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)

//11:57:05.071 ActionBarDrawerToggle    W  DrawerToggle may not show up because NavigationIcon is not visible. You may need to call actionbar.setDisplayHomeAsUpEnabled(true);
//        val toggle = ActionBarDrawerToggle(
//            this, drawerLayout, R.string.navigation_drawer_open, R.string.navigation_drawer_close
//        )
//        drawerLayout.addDrawerListener(toggle)
//        toggle.syncState()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        navView.setNavigationItemSelectedListener(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // enables the map to re-center when user moves his location (traveling)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val userLatLng = LatLng(location.latitude, location.longitude)
                    lastKnownUserLocation = userLatLng
                    Log.d("LocationUpdate", "User Location: $userLatLng")

                    // ✅ Only follow the user if they haven't moved the map
                    if (isFollowingUser) {
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, mMap.cameraPosition.zoom))
                    }
                }
            }
        }

        // #1
        Log.d("LocationUpdate", "Function triggered in onCreate")
        requestLocationUpdates()

        // Follow Button
        val followButton = findViewById<ImageButton>(R.id.custom_center_button)
        followButton.setOnClickListener {
            isFollowingUser = !isFollowingUser // Toggle the follow mode
            val newColor = if (isFollowingUser) Color.BLACK else Color.RED
            followButton.setColorFilter(newColor)
            if (isFollowingUser) {
                try {
                    fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                        if (location != null) {
                            val currentLatLng = LatLng(location.latitude, location.longitude)

                            // 🔍 Check current zoom level
                            if (::mMap.isInitialized) {
                                val currentZoom = mMap.cameraPosition.zoom
                                val targetZoom =
                                    if (currentZoom < 6f) 9f else currentZoom  // Threshold decision
                                mMap.animateCamera(
                                    CameraUpdateFactory.newLatLngZoom(
                                        currentLatLng,
                                        targetZoom
                                    )
                                )
                            }
                        }
                    }
                    Log.d("MapMove", "Following, Custom Button - Following = $isFollowingUser")
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
                } else {
                Log.d("MapMove", "NOT Following, Custom Button - Following = $isFollowingUser")
            }
        }

        //Display VERSION
        /*val versionText = findViewById<TextView>(R.id.versionText)
        versionText.text = getString(
            R.string.app_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )*/

        // Initialize the map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // load saved layer choice
        val sharedPrefs = getSharedPreferences("MapSettings", MODE_PRIVATE)
        currentLayerName = sharedPrefs.getString("selectedLayer", "FlightConditions") ?: "FlightConditions"
        Log.d("MapDebug", "Loaded layer: $currentLayerName")

        // initialize spinner and adapter
        val layerSpinner = findViewById<Spinner>(R.id.layer_selector)
        val adapter = ArrayAdapter.createFromResource(
            this,
            R.array.layer_options,
            R.layout.spinner_item
        ).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }

        // Apply the custom adapter
        layerSpinner.adapter = adapter

        // ✅ Find index of `currentLayerName` in the array and set selection
        val layers = resources.getStringArray(R.array.layer_options)
        val selectedIndex = layers.indexOf(currentLayerName)
        if (selectedIndex >= 0) {
            layerSpinner.setSelection(selectedIndex)
        }

        // ✅ Set up the listener to save selected layer when changed
        layerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val selectedLayer = parent?.getItemAtPosition(pos).toString()

                if (selectedLayer != currentLayerName) {
                    currentLayerName = selectedLayer
                    saveLayerSelection(selectedLayer)
                    refreshMarkers()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}

        }
    }
    // end of onCreate

    // ✅ Navigation Drawer
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_downloads -> startActivity(Intent(this, DownloadActivity::class.java))
            R.id.nav_flightplanning -> startActivity(Intent(this, FlightPlanActivity::class.java))
        }
        drawerLayout.closeDrawers() // ✅ Close drawer after selection
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            drawerLayout.open()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ✅ Start Location Updates MRK 50
    private fun requestLocationUpdates() {
        Log.d("LocationUpdate", "requestLocationUpdates was triggered")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 50).build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val userLatLng = LatLng(location.latitude, location.longitude)
                    Log.d("LocationUpdate", "User Location: $userLatLng")

                    if (isFollowingUser) {
                        mMap.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(
                                userLatLng,
                                mMap.cameraPosition.zoom
                            )
                        )
                    }

                    // 🔹 Fetch waypoints from intent #1
                    val waypoints = intent.getStringArrayListExtra("WAYPOINTS") ?: return
                    if (waypoints.isEmpty()) {
                        Log.e("FlightData", "No waypoints available. Skipping calculation.")
                        return
                    } else {
                        val flightData = calculateFlightData(location, waypoints)
                        // 🔹 Send processed data to UI function
                        updateFlightInfo(flightData)
                    }
                }
            }
        }, Looper.getMainLooper())
    }

    // Handles All Calculations for flight planning
    private fun calculateFlightData(location: Location, waypoints: List<String>): FlightData {
        val userLatLng = LatLng(location.latitude, location.longitude)
        val groundSpeed = location.speed.toDouble() * 1.94384  // Convert to knots
        val altitude = location.altitude * 3.28084  // Convert to feet
        val track = location.bearing.toDouble()
        val latLngList = waypoints.mapNotNull { airportMap[it] }

        // if only one waypoint, use direct to
        val wpName: String
        val wp2Name: String
        if (waypoints.size > 1) {
            wpName = waypoints[0]  // ✅ First waypoint
            wp2Name = waypoints[1] // ✅ Second waypoint
        } else {
            wpName = "direct"
            wp2Name = waypoints.getOrNull(0) ?: "----"
        }

        //val wpLocation = latLngList.getOrNull(1) ?: latLngList.getOrNull(0) ?: userLatLng
        val wpLocation: LatLng
        if (latLngList.size > 1) {
            wpLocation = latLngList[1]
        } else {
            wpLocation = latLngList[0]
        }

        val bearingWp = calculateBearing(userLatLng, wpLocation, wp2Name)
        val distanceWp = calculateDistance(userLatLng, wpLocation)
        val etaMinutes = calculateETA(distanceWp, groundSpeed, plannedAirSpeed)
        val eta = formatETA(etaMinutes)

        return FlightData(
            wpLocation = wpLocation,
            currentLeg = "$wpName → $wp2Name",
            track = track,
            bearing = bearingWp,
            distance = distanceWp,
            groundSpeed = groundSpeed,
            plannedAirSpeed = plannedAirSpeed,
            altitude = altitude,
            eta = eta,
            waypoints = waypoints
        )
    }

    // calculate bearing and distance to next waypoint (fake burbank)
    fun calculateBearing(currentLocation: LatLng, nextWaypoint: LatLng, airportId: String): Double {
        val lat1 = Math.toRadians(currentLocation.latitude)
        val lon1 = Math.toRadians(currentLocation.longitude)
        val lat2 = Math.toRadians(nextWaypoint.latitude)
        val lon2 = Math.toRadians(nextWaypoint.longitude)
        val deltaLon = lon2 - lon1
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        var bearing = Math.toDegrees(atan2(y, x))
        // ✅ Add/Subtract magnetic variation (default to 0 if missing)
        val magVar = airportMagVarMap[airportId] ?: 0.0
        bearing -= magVar
        return (bearing + 360) % 360  // Normalize to 0-360°
    }
    fun calculateDistance(currentLocation: LatLng, nextWaypoint: LatLng): Double {
        val R = 3440.065  // Earth radius in nautical miles
        val lat1 = Math.toRadians(currentLocation.latitude)
        val lon1 = Math.toRadians(currentLocation.longitude)
        val lat2 = Math.toRadians(nextWaypoint.latitude)
        val lon2 = Math.toRadians(nextWaypoint.longitude)
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c  // ✅ Distance in nautical miles (NM)
    }
    fun calculateETA(distanceNM: Double, groundSpeedKnots: Double, plannedAirSpeed: Int): Double {
        Log.d("ETA", "distanceNM: $distanceNM, groundSpeedKnots: $groundSpeedKnots, plannedAirSpeed: $plannedAirSpeed")
        if (groundSpeedKnots > activeSpeed) {
            return (distanceNM / groundSpeedKnots) * 60
        } else {
            return (distanceNM / plannedAirSpeed) * 60
            //Double.POSITIVE_INFINITY  // 🚨 Avoid divide-by-zero error
        }
    }
    fun formatETA(etaMinutes: Double): String {
        return if (etaMinutes >= 60) {
            val hours = (etaMinutes / 60).toInt()
            val minutes = (etaMinutes % 60).toInt()
            String.format("%d:%02d", hours, minutes)  // ✅ Example: 1:05 (1 hour, 5 min)
        } else {
            val minutes = (etaMinutes).toInt()
            String.format("%d", minutes)  // ✅ Example: "12.5 min"
        }
    }
    fun calculateTotalDistance(waypoints: List<String>): Double {
        var totalDistance = 0.0

        for (i in 0 until waypoints.size - 1) {
            val from = airportMap[waypoints[i].uppercase()]
            val to = airportMap[waypoints[i + 1].uppercase()]

            if (from != null && to != null) {
                totalDistance += calculateDistance(from, to)
            }
        }
        return totalDistance
    }

    fun calculateTotalETA(totalDistance: Double, groundSpeedKnots: Double): String {
        if (groundSpeedKnots <= 0) return "--:--" // Prevent divide by zero

        val etaMinutes = (totalDistance / groundSpeedKnots) * 60
        val hours = etaMinutes.toInt() / 60
        val minutes = etaMinutes.toInt() % 60

        return String.format("%02d:%02d", hours, minutes)
    }

    fun updateFlightInfo(data: FlightData) {
        Log.i("updateFlightInfo", "wpLocation: ${data.wpLocation}")
        Log.i("updateFlightInfo", "wayPoints: ${data.waypoints}")
        Log.i("updateFlightInfo", "track: ${data.track}")
        Log.i("updateFlightInfo", "groundSpeed: ${data.groundSpeed}")
        Log.i("updateFlightInfo", "plannedAirSpeed: ${data.plannedAirSpeed}")
        Log.i("updateFlightInfo", "altitude: ${data.altitude}")
        Log.i("updateFlightInfo", "eta: ${data.eta}")
        Log.i("updateFlightInfo", "bearing: ${data.bearing}")
        Log.i("updateFlightInfo", "distance: ${data.distance}")

        val isPlanningMode = data.groundSpeed < activeSpeed
        val etaColor = if (isPlanningMode) Color.CYAN else Color.WHITE
        val etaDestColor = if (isPlanningMode) Color.CYAN else Color.WHITE

        binding.currentLeg.text = data.currentLeg
        //binding.trackText.text = "${data.track.roundToInt()}°"
        binding.bearingText.text = "${data.bearing.roundToInt()}°"
        binding.distanceText.text = "${data.distance.roundToInt()}nm"
        binding.gpsSpeed.text = "${data.groundSpeed.roundToInt()}kt"
        binding.altitudeText.text = "${data.altitude.roundToInt()}"
        binding.etaText.text = data.eta
        binding.etaText.setTextColor(etaColor)

        if (isPlanningMode) {
            binding.trackText.text = "---"
            binding.trackText.setTextColor(Color.WHITE)

        } else {
            // 🔥 Set trackText color based on deviation from bearing
            val delta = abs((data.track - data.bearing + 540) % 360 - 180) // Normalize 0–180
            val trackColor = when {
                delta > 15 -> Color.RED
                delta > 5 -> Color.YELLOW
                else -> Color.WHITE
            }
            binding.trackText.setTextColor(trackColor)
            binding.trackText.text = "${data.track.roundToInt()}°"
        }

        val destination = data.waypoints.lastOrNull() ?: "----"
        binding.destText.text = destination
        binding.etaDestText.setTextColor(etaDestColor)

        val totalDistance = calculateTotalDistance(data.waypoints)
        binding.dtdText.text = "${totalDistance.roundToInt()}nm"

        val etaMinutes = calculateETA(totalDistance, data.groundSpeed, data.plannedAirSpeed)
        val totalETA = formatETA(etaMinutes)
        binding.etaDestText.text = totalETA
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        mMap.isMyLocationEnabled = true
    }

    @SuppressLint("PotentialBehaviorOverride")
    override fun onMapReady(googleMap: GoogleMap) {

        mMap = googleMap
        mMap.mapType = GoogleMap.MAP_TYPE_NORMAL // Options: NORMAL, SATELLITE, TERRAIN, HYBRID
        mMap.isTrafficEnabled = false
        mMap.uiSettings.isTiltGesturesEnabled = false
        mMap.uiSettings.isRotateGesturesEnabled = false
        mMap.uiSettings.isMyLocationButtonEnabled = false
        mMap.setMinZoomPreference(5.0f) // Set minimum zoom out level
        mMap.setMaxZoomPreference(15.0f) // Set maximum zoom in level
        mMap.setInfoWindowAdapter(CustomInfoWindowAdapter(this))

        // Handle Permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true
        }


        showBottomProgressBar("🚨 Initializing all the things")

        // ✅ Apply the custom dark mode style
        try {
            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
                googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_modest))
            } else {
                //googleMap.setMapStyle(null)
                googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_apple))
            }
        } catch (e: Resources.NotFoundException) {
            Log.e("MapStyle", "Can't find style. Error: ", e)
        }

        // Set listener for camera movement
        mMap.setOnCameraIdleListener {  //1
            saveMapPosition()
            updateVisibleMarkers(metarData, tafData)
            // Optionally show zoom for debug
            //val zoom = mMap.cameraPosition.zoom
            //Log.d("ZoomDebug", "Current Zoom Level: $zoom")
            // findViewById<TextView>(R.id.zoomLevelText)?.text = "Zoom: ${zoom.toInt()}"
        }

        mMap.setOnCameraMoveStartedListener { reason ->
            // Only disable follow if the move was triggered by a gesture.
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                isFollowingUser = false
                val customCenterButton = findViewById<ImageButton>(R.id.custom_center_button)
                customCenterButton.setColorFilter(Color.RED)
                Log.d("MapDebug", "User manually moved the map. Disabling follow #1")
            }
        }

        //requestLocationUpdates()
        //refreshMarkers()

        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val firstLaunch = prefs.getBoolean("first_launch", true)

        if (firstLaunch) {
            prefs.edit().putBoolean("first_launch", false).apply()
            val burbank = LatLng(34.1819, -118.3079)
            mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(burbank, 9f))
            //showFirstLaunchDialog()
        } else {
            moveToLastSavedLocationOrCurrent()
        }

        //moveToLastSavedLocationOrCurrent()

        // **Load TFR GeoJSON**
        var enableTfr = 1
        if (enableTfr == 1) {
            loadAndDrawTFR()
            // Initialize the tfr toggle button
            val tfrButton = findViewById<Button>(R.id.toggle_tfr_button)
            var isTFRVisible = true
            tfrButton.setOnClickListener {
                isTFRVisible = !isTFRVisible
                toggleTFRVisibility()
                updateButtonState(tfrButton, isTFRVisible)
            }

            // Handle when user clicks on a TFR
            mMap.setOnPolygonClickListener { polygon ->
                val tfrList = tfrPolygonInfo[polygon]  // Get all TFRs for this polygon

                if (tfrList != null) {
                    if (tfrList.size == 1) {
                        // ✅ Show single TFR pop-up
                        showTfrPopup(this, tfrList[0])
                    } else {
                        // ✅ Show list selection if multiple TFRs exist
                        showTfrSelectionDialog(this, tfrList)
                    }
                }
            }
        }

        // **Load Airspace Boundaries**
        var enableAirspace = 1
        if (enableAirspace == 1) {
            loadAndDrawAirspace(mMap, this)

            // Initialize the airspace toggle button
            val airspaceButton = findViewById<Button>(R.id.toggle_airspace_button)
            var isAirspaceVisible = true
            airspaceButton.setOnClickListener {
                isAirspaceVisible = !isAirspaceVisible
                toggleAirspace()
                updateButtonState(airspaceButton, isAirspaceVisible)
            }
        }

        // Sectionals
        var enableSectional = 1
        if (enableSectional == 1) {
            // Initialize the tile overlay toggle
            var isSectionalVisible: Boolean
            val vfrSecButton = findViewById<Button>(R.id.toggle_vfrsec_button)
            if (firstLaunch) {
                isSectionalVisible = true
                toggleSectionalOverlay(mMap)
            } else {
                isSectionalVisible = false
            }
            vfrSecButton.setOnClickListener {
                isSectionalVisible = !isSectionalVisible
                toggleSectionalOverlay(mMap)
                updateButtonState(vfrSecButton, isSectionalVisible)
            }
        }

        // Flight Plan
        var enableFlightPlan = 1
        if (enableFlightPlan == 1) {
            // ✅ Handle flight plan waypoints
            //intent #2
            intent.getStringArrayListExtra("WAYPOINTS")?.let { waypoints ->
                if (waypoints.isNotEmpty()) {
                    updateMapWithWaypoints(waypoints)
                }
            }
        }

        // **Load Metars**
        var enableMetar = 1
        if (enableMetar == 1) {
            loadAndDrawMetar()
            // ** refresh weather data
            startAutoRefresh(15)

            // Initialize the metar toggle button
            val metarButton = findViewById<Button>(R.id.toggle_metar_button)
            var isMetarVisible = true
            metarButton.setOnClickListener {
                isMetarVisible = !isMetarVisible
                toggleMetarVisibility()
                updateButtonState(metarButton, isMetarVisible)
            }

            // Handle when user clicks on a metar marker
            mMap.setOnMarkerClickListener { marker ->
                val data = marker.tag as? MetarTafData // ✅ Retrieve METAR & TAF together
                if (data != null) {
                    showMetarDialog(data.metar, data.taf)
                }
                true
            }
        }

        if (::mMap.isInitialized) {
            checkLocationPermission()
        }

        hideBottomProgressBar()


        /*// Initialize the airspace toggle button
        val airspaceButton = findViewById<Button>(R.id.toggle_airspace_button)
        var isAirspaceVisible = true
        airspaceButton.setOnClickListener {
            isAirspaceVisible = !isAirspaceVisible
            toggleAirspace()
            updateButtonState(airspaceButton, isAirspaceVisible)
        }*/

        /*// Initialize the tfr toggle button
        val tfrButton = findViewById<Button>(R.id.toggle_tfr_button)
        var isTFRVisible = true
        tfrButton.setOnClickListener {
            isTFRVisible = !isTFRVisible
            toggleTFRVisibility()
            updateButtonState(tfrButton, isTFRVisible)
        }*/

        /*// Handle when user clicks on a TFR
        mMap.setOnPolygonClickListener { polygon ->
            val tfrList = tfrPolygonInfo[polygon]  // Get all TFRs for this polygon

            if (tfrList != null) {
                if (tfrList.size == 1) {
                    // ✅ Show single TFR pop-up
                    showTfrPopup(this, tfrList[0])
                } else {
                    // ✅ Show list selection if multiple TFRs exist
                    showTfrSelectionDialog(this, tfrList)
                }
            }
        }*/

        /*// Initialize the metar toggle button
        val metarButton = findViewById<Button>(R.id.toggle_metar_button)
        var isMetarVisible = true
        metarButton.setOnClickListener {
            isMetarVisible = !isMetarVisible
            toggleMetarVisibility()
            updateButtonState(metarButton, isMetarVisible)
        }

        // Handle when user clicks on a metar marker
        mMap.setOnMarkerClickListener { marker ->
            val data = marker.tag as? MetarTafData // ✅ Retrieve METAR & TAF together
            if (data != null) {
                showMetarDialog(data.metar, data.taf)
            }
            true
        }*/

    }

    private fun checkLocationPermission() {
        if (!::mMap.isInitialized) return

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            checkLocationPermission()
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
            Log.d("MapMove", "Moving to current position, Following = $isFollowingUser")
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
    private fun moveToLastSavedLocationOrCurrent() {
        val prefs = getSharedPreferences("map_prefs", MODE_PRIVATE)
        val savedLat = prefs.getFloat("lat", Float.MIN_VALUE)
        val savedLng = prefs.getFloat("lng", Float.MIN_VALUE)
        val savedZoom = prefs.getFloat("zoom", 10f) // Default zoom level

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (savedLat != Float.MIN_VALUE && savedLng != Float.MIN_VALUE &&
                (savedLat != 0.0f || savedLng != 0.0f)
            ) {
                // ✅ Move to last saved position
                val lastLatLng = LatLng(savedLat.toDouble(), savedLng.toDouble())
                Log.d("MapDebug", "Moving to saved position: lat=$savedLat, lng=$savedLng, zoom=$savedZoom")
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(lastLatLng, savedZoom))
            } else {
                // ✅ No saved position → Move to current location
                Log.d("MapDebug", "No saved position, moving to current location: lat=$savedLat, lng=$savedLng, zoom=$savedZoom")
                moveToCurrentLocation()
            }
        } else {
            // ✅ Request permission if not granted
            checkLocationPermission()
        }
    }
    private fun saveMapPosition() {
        val prefs = getSharedPreferences("map_prefs", MODE_PRIVATE)
        val editor = prefs.edit()
        val target = mMap.cameraPosition.target
        editor.putFloat("lat", target.latitude.toFloat())
        editor.putFloat("lng", target.longitude.toFloat())
        editor.putFloat("zoom", mMap.cameraPosition.zoom)
        editor.apply()
        // ✅ Debugging: Check if values are saved
        Log.d("MapDebug", "Saved Position: lat=${target.latitude}, lng=${target.longitude}, zoom=${mMap.cameraPosition.zoom}")
    }
    private fun saveLayerSelection(layerName: String) {
        val sharedPrefs = getSharedPreferences("MapSettings", MODE_PRIVATE)
        with(sharedPrefs.edit()) {
            putString("selectedLayer", layerName)
            apply()
        }
        Log.d("MapDebug", "Saved layer: $layerName")
    }
    private fun createDotBitmap(size: Int, fillColor: Int, borderColor: Int, borderWidth: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.isAntiAlias = true

        // Draw the border
        paint.color = Color.BLACK
        if (borderColor == Color.WHITE || borderColor == fillColor) {
            canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - borderWidth + 1, paint) // small
        } else {
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint) // big
        }

        // Draw the TAF (outer circle)
        if (borderColor != Color.WHITE && borderColor != fillColor) {
            paint.color = borderColor
            canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 1, paint)
        }

        // Draw the METAR (filled inner circle)
        paint.color = fillColor
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - borderWidth, paint)

        return bitmap
    }
    private fun refreshMarkers() {
        metarMarkers.forEach { it.remove() }
        metarMarkers.clear()

        updateVisibleMarkers(metarData, tafData)
    }
    private fun createTextBitmap(text: String, textColor: Int, bgColor: Int = Color.TRANSPARENT): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = 50f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }

        // Measure text bounds
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)

        val padding = 10 // Add a small padding around the text
        val width = bounds.width() + 2 * padding
        val height = bounds.height() + 2 * padding

        // Create bitmap with the exact size needed
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Draw background if needed
        if (bgColor != Color.TRANSPARENT) {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = bgColor
                style = Paint.Style.FILL
            }
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        // Draw text centered within the background
        val textHeight = bounds.height()
        //val adjustedY = height / 2f + textHeight / 2f + paint.descent()
        val adjustedY = height / 2f + textHeight / 2f - paint.descent() / 3

        // Draw text
        canvas.drawText(text, padding.toFloat(), adjustedY, paint)

        return bitmap
    }
    private fun startAutoRefresh(intervalMinutes: Long) {
        lifecycleScope.launch {
            while (true) {
                delay(intervalMinutes * 60 * 1000) // ✅ Wait before updating
                Log.d("AUTO_REFRESH", "Refreshing METAR & TAF data")
                showBottomProgressBar("🚨 Refreshing METAR & TAF data")
                loadAndDrawMetar() // ✅ Re-fetch and update data
            }
        }
    }

    // Update markers based on visible map area
    private fun updateVisibleMarkers(metars: List<METAR>, tafs: List<TAF>) {
        val visibleBounds = mMap.projection.visibleRegion.latLngBounds
        metarMarkers.forEach { it.remove() }
        metarMarkers.clear()

        //Log.d("DEBUG", "Total METARs loaded: ${metars.size}")

        metars.forEach { metar ->
            val location = LatLng(metar.latitude, metar.longitude)

            // ✅ Log every airport (before filtering)
            //Log.d("METAR_DEBUG", "Processing: ${metar.stationId} at $location")

            if (!visibleBounds.contains(location)) return@forEach
                //Log.d("METAR_DEBUG", "Skipping ${metar.stationId}: Out of visible bounds")

            val existingMarker = metarMarkers.find { it.position == location }

            if (existingMarker != null) {
                // 🔹 Just update visibility, don't recreate
                existingMarker.isVisible = areMetarsVisible
            } else {

                val taf = tafs.find { it.stationId == metar.stationId }
                val currentLayer = MapLayer.fromName(currentLayerName)
                val marker = when (currentLayer) {
                    MapLayer.FlightConditions -> createFlightConditionMarker(metar, taf, location)
                    MapLayer.Wind -> createMetarDotForWindLayer(metar, location)
                    MapLayer.Temperature -> createTemperatureMarker(metar, location)
                    MapLayer.Altimeter -> createAltimeterMarker(metar, location)
                    MapLayer.Ceiling -> createCeilingMarker(metar, location)
                    MapLayer.Clouds -> createCloudMarker(metar, location)
                }

                // Add the marker from the `when` clause if it's not null
                marker?.let { metarMarkers.add(it) }

                // Add the wind dot for all layers except FlightConditions
                if (currentLayer != MapLayer.FlightConditions) {
                    createMetarDotForWindLayer(metar, location)?.let { metarMarkers.add(it) }
                }

                // ✅ Add wind barb only when "Wind Barbs" layer is selected
                if (currentLayer == MapLayer.Wind) {
                    createWindMarker(metar, taf, location)?.let { metarMarkers.add(it) }
                }
            }
        }
    }
    private fun createDotMarker(metar: METAR, taf: TAF?, location: LatLng, style: MarkerStyle): Marker? {
        // Skip invalid color combinations
        if (style.fillColor == Color.WHITE) return null

        val dotBitmap = createDotBitmap(
            size = style.size,
            fillColor = style.fillColor,
            borderColor = style.borderColor,
            borderWidth = style.borderWidth
        )

        return mMap.addMarker(
            MarkerOptions()
                .position(location)
                .icon(BitmapDescriptorFactory.fromBitmap(dotBitmap))
                .anchor(0.5f, 0.5f)
                .visible(areMetarsVisible)
                .title(
                    "${metar.stationId} - ${metar.flightCategory}" +
                            (taf?.flightCategory?.takeIf { it != metar.flightCategory }
                                ?.let { " (TAF = $it)" } ?: "")
                )
                .snippet(formatAirportDetails(metar))
        )?.apply {
            tag = MetarTafData(metar, taf) // Set tag on Marker, not Options
        }
    }
    private fun getFlightCategoryColor(category: String?): Int {
        return when (category?.uppercase()) {
            "VFR" -> Color.GREEN
            "MVFR" -> Color.parseColor("#0080FF")
            "IFR" -> Color.RED
            "LIFR" -> Color.parseColor("#FF00FF")
            else -> Color.WHITE
        }
    }
    private fun createWindMarker(metar: METAR, taf: TAF?, location: LatLng): Marker? {
        val windSpeed = metar.windSpeedKt ?: 0
        val windDir = metar.windDirDegrees
        if (windSpeed <= 4) return null

        val barbBitmap = createWindBarbBitmap(windSpeed, windDir)
        return mMap.addMarker(
            MarkerOptions()
                .position(location)
                .icon(barbBitmap?.let { BitmapDescriptorFactory.fromBitmap(it) })
                .anchor(0.5f, 0.5f)
                .visible(areMetarsVisible)
                .title(
                    "${metar.stationId} - ${metar.flightCategory}" +
                            (taf?.flightCategory?.takeIf { it != metar.flightCategory }
                                ?.let { " (TAF = $it)" } ?: "")
                )
                .snippet(formatAirportDetails(metar))
        )?.apply {
            tag = MetarTafData(metar, taf)
        }
    }
    private fun createFlightConditionMarker(metar: METAR, taf: TAF?, location: LatLng): Marker? {
        val style = MarkerStyle(
            size = 60,
            fillColor = getFlightCategoryColor(metar.flightCategory),
            borderColor = taf?.flightCategory?.let { getFlightCategoryColor(it) } ?: Color.WHITE,
            borderWidth = 13
        )
        return createDotMarker(metar, taf, location, style)
    }
    private fun createMetarDotForWindLayer(metar: METAR, location: LatLng): Marker? {
        val style = MarkerStyle(
            size = 50,
            fillColor = getFlightCategoryColor(metar.flightCategory),
            borderColor = Color.WHITE, // ✅ White border to "skip" the TAF border effect
            borderWidth = 13
        )
        return createDotMarker(metar, null, location, style) // No TAF border
    }

/*    private fun createTemperatureMarker(metar: METAR, location: LatLng): Marker? {
        metar.tempC?.let {
            val tempColor = when {
                it >= 27 -> Color.RED
                it <= 5 -> Color.BLUE
                else -> Color.WHITE
            }
            val bgColor = Color.argb(125, 0, 0, 0)
            val bitmap = createTextBitmap("${celsiusToFahrenheit(it)}°F", tempColor, bgColor)
            //val bitmap = createTextBitmap("${it}°C", tempColor)
            return mMap.addMarker(MarkerOptions()
                .position(location)
                .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                .visible(areMetarsVisible)
                .anchor(0.5f, 0.5f))
        }
        return null
    }*/

    private fun createTemperatureMarker(metar: METAR, location: LatLng): Marker? {
        metar.tempC?.let {
            val tempColor = when {
                it >= 27 -> Color.RED
                it <= 5 -> Color.BLUE
                else -> Color.WHITE
            }

            // Create an IconGenerator instance
            val iconGenerator = IconGenerator(this)
            // Customize background, padding, and text style
            iconGenerator.setStyle(IconGenerator.STYLE_BLUE)
            iconGenerator.setContentPadding(20, 10, 20, 10)
            // Optionally set a custom text appearance here

            // Generate the bitmap with your desired text
            val text = "${celsiusToFahrenheit(it)}°F"
            val bitmap = iconGenerator.makeIcon(text)

            // Optionally, if you need to scale the bitmap, you can do so:
            // val scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, false)

            return mMap.addMarker(MarkerOptions()
                .position(location)
                .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                .visible(areMetarsVisible)
                .anchor(.5f, 1.2f))
        }
        return null
    }
    private fun createAltimeterMarker(metar: METAR, location: LatLng): Marker? {
        metar.altimeterInHg?.let {
            val bgColor = Color.BLACK
            val bitmap = createTextBitmap("%.2f".format(it), Color.WHITE, bgColor)
            return mMap.addMarker(MarkerOptions()
                .position(location)
                .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                .visible(areMetarsVisible)
                .anchor(0.5f, 1.3f))
        }
        return null
    }
    private fun createCloudMarker(metar: METAR, location: LatLng): Marker? {
        metar.skyCover1.let {
            val bgColor = Color.BLACK
            val bitmap = it?.let { it1 -> createTextBitmap(it1, Color.WHITE, bgColor) }
            return mMap.addMarker(MarkerOptions()
                .position(location)
                .icon(bitmap?.let { it1 -> BitmapDescriptorFactory.fromBitmap(it1) })
                .visible(areMetarsVisible)
                .anchor(0.5f, 1.3f))
        }
    }
    private fun createCeilingMarker(metar: METAR, location: LatLng): Marker? {
        metar.cloudBase1?.let {
            val bgColor = Color.BLACK
            val bitmap = createTextBitmap(it.toString(), Color.WHITE, bgColor)
            return mMap.addMarker(MarkerOptions()
                .position(location)
                .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                .visible(areMetarsVisible)
                .anchor(0.5f, 1.3f))
        }
        return null
    }

    @SuppressLint("DefaultLocale")
    private fun celsiusToFahrenheit(celsius: Double): String {
        return "%.0f".format(celsius * 9/5 + 32)
    }

    // Format the weather details for the popup snippet
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
    private fun showBottomProgressBar(message: String) {
        val progressBar = findViewById<LinearLayout>(R.id.progress_bottom_bar)
        val progressOverlay = findViewById<FrameLayout>(R.id.progress_overlay)
        val progressMessage = findViewById<TextView>(R.id.progress_message_bottom)
        if (progressBar == null || progressMessage == null) {
            Log.e("ProgressBar", "Progress bar or message view not found!")
            return
        }
        progressMessage.text = message
        progressBar.visibility = View.VISIBLE
        progressOverlay.visibility = View.VISIBLE
    }
    private fun hideBottomProgressBar() {
        val progressBar = findViewById<LinearLayout>(R.id.progress_bottom_bar)
        val progressOverlay = findViewById<FrameLayout>(R.id.progress_overlay)
        progressBar.visibility = View.GONE
        progressOverlay.visibility = View.GONE
    }
    private fun updateButtonState(button: Button, isActive: Boolean) {
        // Change button color based on state
        if (isActive) {
            //button.setBackgroundResource(R.drawable.rounded_button) // Active state
            button.setTextColor(Color.WHITE)
        } else {
            //button.setBackgroundResource(R.drawable.rounded_button) // Inactive state
            button.setTextColor(Color.GRAY)
        }
    }
    // TFR
    private fun loadAndDrawTFR() {
        lifecycleScope.launch {
            try {
                //showBottomProgressBar("✈️ Loading TFR Data")
                val tfrFile = getOrDownloadTfrs(filesDir)
                if (tfrFile != null) {
                    println("✅ Parsing tfr data...")
                    val tfrFeatures = parseTFRGeoJson(tfrFile)
                    drawTFRPolygons(mMap, tfrFeatures)
                } else {
                    //showBottomProgressBar("❌ No TFR Data Available")
                    println("🚨 No TFR data available")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "TFR download failed: ${e.message}")
                //showBottomProgressBar("❌ TFR Data Failed")
            } finally {
                //hideBottomProgressBar()
            }
        }
    }
    private fun drawTFRPolygons(map: GoogleMap, tfrFeatures: List<TFRFeature>) {
        val dateFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val currentDate = Date()

        // Clear existing TFR polygons to avoid duplicates
        if (tfrPolygons.isNotEmpty()) {
            tfrPolygons.forEach { it.remove() }
            tfrPolygons.clear()
        }

        // Add new polygons based on the TFR features
        for (feature in tfrFeatures) {
            val coordinatesList = feature.geometry.coordinates

            // Convert coordinates to a list of LatLng objects
            for (polygon in coordinatesList) {
                var latLngList = polygon.map { LatLng(it[1], it[0]) }.toMutableList()
                val name = feature.properties.description

                // Deduplicate points (remove unnecessary repeats)
                val deduplicatedLatLngList = latLngList.distinct()

                // Ensure the polygon is closed
                if (deduplicatedLatLngList.isNotEmpty() && deduplicatedLatLngList.first() != deduplicatedLatLngList.last()) {
                    latLngList = deduplicatedLatLngList.toMutableList()
                    latLngList.add(deduplicatedLatLngList.first()) // Close the polygon
                }

                // Extract dates from the name field
                val regex = Regex("""(\w+, \w+ \d{1,2}, \d{4}) through (\w+, \w+ \d{1,2}, \d{4})""")
                val match = regex.find(name)
                val strokeColor: Int
                val fillColor: Int

                if (match != null) {
                    val (startDateString) = match.destructured
                    val startDate = dateFormat.parse(startDateString)
                    when {
                        currentDate.before(startDate) -> { // Future TFR
                            strokeColor = Color.argb(255, 255, 128, 0)
                            fillColor = Color.argb(64, 255, 128, 0)
                        }
                        else -> { // Current TFR
                            strokeColor = Color.argb(255, 255, 0, 0)
                            fillColor = Color.argb(64, 255, 0, 0)
                        }
                    }
                } else {
                    // No date found, assume TFR is active
                    strokeColor = Color.argb(255, 255, 0, 0)
                    fillColor = Color.argb(64, 255, 0, 0)
                }
                // Add the TFR to the map
                val mapPolygon = map.addPolygon(
                    PolygonOptions()
                        .addAll(latLngList)
                        .strokeColor(strokeColor)
                        .fillColor(fillColor)
                        .strokeWidth(2f)
                        .visible(areTFRsVisible)
                        .clickable(true)
                )
                tfrPolygons.add(mapPolygon)

                // Store TFR info for later use
                tfrPolygonInfo.getOrPut(mapPolygon) { mutableListOf() }.add(feature.properties)
            }
        }
    }
    private fun formatAltitude(altitude: String): String {
        return when {
            altitude == "0" -> "Surface"  // ✅ Convert "0" to "Surface"
            (altitude.replace(",", "").toIntOrNull() ?: 0) >= 90000 -> "Unlimited" // ✅ Convert 90,000+ to "Unlimited"
            altitude.toIntOrNull() != null -> "%,d'".format(altitude.replace(",", "").toInt()) // ✅ Add comma + tick
            else -> altitude // ✅ Keep text altitudes unchanged (e.g., "FL600")
        }
    }
    //light
    /*private fun showTfrPopup(context: Context, tfr: TFRProperties) {

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        val currentDate = Date()

        // ✅ Ensure dateEffective and dateExpire are used for date parsing
        val startDateString = if (tfr.dateEffective.isEmpty() || tfr.dateEffective == "null") {
            tfr.dateIssued
        } else {
            tfr.dateEffective
        }
        val startDate = try { dateFormat.parse(startDateString) } catch (e: Exception) { null }
        val endDate = try { dateFormat.parse(tfr.dateExpire) } catch (e: Exception) { null }

        // ✅ Ensure altitudeMin and altitudeMax are treated correctly
        val altitudeInfo = "${formatAltitude(tfr.altitudeMin)} - ${formatAltitude(tfr.altitudeMax)}"

        // ✅ Check if the TFR is active
        val status = if (endDate == null || (startDate != null && currentDate in startDate..endDate)) {
            "Active"
        } else {
            "Inactive"
        }

        // **Set Custom Colors**
        val activeColor = ContextCompat.getColor(context, android.R.color.holo_red_dark)  // Red for Active
        val inactiveColor = ContextCompat.getColor(context, android.R.color.holo_green_dark)  // Green for Inactive
        val altitudeColor = ContextCompat.getColor(context, android.R.color.holo_blue_dark) // Blue for altitude

        // **Create Spannable Title with Custom Colors**
        val tfrHead = SpannableString("$status, $altitudeInfo")

        // Apply color to status
        tfrHead.setSpan(
            ForegroundColorSpan(if (status == "Active") activeColor else inactiveColor),
            0, status.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Apply color to altitude info
        tfrHead.setSpan(
            ForegroundColorSpan(altitudeColor),
            status.length + 2, tfrHead.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val tfrBody = """
            ${tfr.facility} ${tfr.notam} ${tfr.type}
            Effective: ${startDate?.let { outputFormat.format(it) } ?: "Unknown"}
            Description: ${tfr.description}
        """.trimIndent()

        AlertDialog.Builder(context)
            .setTitle(tfrHead)
            .setMessage(tfrBody)
            .setPositiveButton("OK", null)
            .show()
    }*/
    //dark
    @SuppressLint("SetTextI18n")
    private fun showTfrPopup(context: Context, tfr: TFRProperties) {

        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val outputFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)
        val currentDate = Date()

        // ✅ Ensure `dateEffective` and `dateExpire` are used for date parsing
        val startDateString = if (tfr.dateEffective.isEmpty() || tfr.dateEffective == "null") {
            tfr.dateIssued
        } else {
            tfr.dateEffective
        }
        val startDate = try { dateFormat.parse(startDateString) } catch (e: Exception) { null }
        val endDate = try { dateFormat.parse(tfr.dateExpire) } catch (e: Exception) { null }

        // ✅ Ensure `altitudeMin` and `altitudeMax` are treated correctly
        val altitudeInfo = "${formatAltitude(tfr.altitudeMin)} - ${formatAltitude(tfr.altitudeMax)}"

        // ✅ Check if the TFR is active
        val status = if (endDate == null || (startDate != null && currentDate in startDate..endDate)) {
            "Active"
        } else {
            "Inactive"
        }

        // **Set Custom Colors**
        val activeColor = ContextCompat.getColor(context, android.R.color.holo_red_dark)  // Red for Active
        val inactiveColor = ContextCompat.getColor(context, android.R.color.holo_green_dark)  // Green for Inactive
        val altitudeColor = ContextCompat.getColor(context, android.R.color.holo_blue_dark) // Blue for altitude
        val defaultTextColor = Color.LTGRAY  // ✅ Light gray for regular text

        // **Create Spannable Title with Custom Colors**
        val tfrHead = SpannableString("$status, $altitudeInfo")

        // Apply color to status
        tfrHead.setSpan(
            ForegroundColorSpan(if (status == "Active") activeColor else inactiveColor),
            0, status.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Apply color to altitude info
        tfrHead.setSpan(
            ForegroundColorSpan(altitudeColor),
            status.length + 2, tfrHead.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // **Custom Message TextView for better control**
        val messageTextView = TextView(context).apply {
            text = """
            ${tfr.facility} ${tfr.notam} ${tfr.type}
            Effective: ${startDate?.let { outputFormat.format(it) } ?: "Unknown"}
            Description: ${tfr.description}
        """.trimIndent()
            textSize = 14f  // ✅ Smaller text for better readability
            setPadding(60, 20, 40, 20)
            setTextColor(defaultTextColor)  // ✅ Light gray text for dark mode
        }

        // **Custom Layout for Dark Background**
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)  // ✅ Black with transparency
            addView(messageTextView)
        }

        // **Create and show AlertDialog**
        val alertDialog = AlertDialog.Builder(context)
            .setCustomTitle(TextView(context).apply {
                text = tfrHead
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setPadding(60, 30, 40, 10)
                setTextColor(Color.WHITE)  // ✅ Title is always white
                gravity = Gravity.START
            })
            .setView(layout)  // ✅ Custom layout with dark background
            .setPositiveButton("OK", null)
            .create()

        // **Ensure dark background for the entire dialog**
        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.argb(200, 0, 0, 0)))

        alertDialog.show()
    }
    private fun showTfrSelectionDialog(context: Context, tfrList: List<TFRProperties>) {
        val tfrTitles = tfrList.map { "${it.notam} - ${it.type}" }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("Please Select One")
            .setItems(tfrTitles) { _, which ->
                showTfrPopup(context, tfrList[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    private fun toggleTFRVisibility() {
        areTFRsVisible = !areTFRsVisible
        tfrPolygons.forEach { it.isVisible = areTFRsVisible }
        Log.d("Toggle", "TFR visibility set to $areTFRsVisible")
    }
    // AIRSPACE
    private fun loadAndDrawAirspace(map: GoogleMap, context: Context) {
        if (airspacePolygons.isNotEmpty()) return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                //showBottomProgressBar("🗺️ Loading Airspace Boundaries")
                val inputStream = context.resources.openRawResource(R.raw.airspace)
                val geoJsonString = inputStream.bufferedReader().use { it.readText() }
                val geoJsonObject = JSONObject(geoJsonString)
                val features = geoJsonObject.getJSONArray("features")

                val newPolygons = mutableListOf<Polygon>() // Temporary storage for polygons
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val geometry = feature.getJSONObject("geometry")
                    val properties = feature.getJSONObject("properties")
                    val type = geometry.getString("type")

                    if (type == "Polygon") {
                        val coordinates = extractPolygonCoordinates(geometry.getJSONArray("coordinates"))
                        val polygonOptions = PolygonOptions().addAll(coordinates)

                        // Customize polygon style
                        val airspaceTypeCode = properties.optString("TYPE_CODE", "Unknown")
                        if (airspaceTypeCode == "MODE-C") {
                            polygonOptions.strokeColor(Color.argb(90, 200, 63, 200))
                                .strokeWidth(2f)
                        } else if (airspaceTypeCode == "CLASS") {
                            val airspaceClass = properties.optString("LOCAL_TYPE", "Unknown").trim()
                            //Log.d("DEBUG", "Airspace Class: '$airspaceClass'")
                            //Log.d("DEBUG", "Airspace Properties: $properties")

                            when (airspaceClass) {
                                "CLASS_B" -> polygonOptions.strokeColor(Color.argb(128, 0, 64, 255))
                                    .strokeWidth(8f)
                                "CLASS_C" -> polygonOptions.strokeColor(Color.MAGENTA)
                                    .strokeWidth(4f)
                                "CLASS_D", "CLASS_E4" -> polygonOptions.strokeColor(
                                    if (airspaceClass == "CLASS_D") Color.parseColor("#0080FF") else Color.parseColor(
                                        "#863F67"
                                    )
                                ).strokeWidth(4f)
                                    .strokePattern(listOf(Dash(20f), Gap(10f)))
//                                "CLASS_E5" -> polygonOptions.strokeColor(Color.argb(32, 134, 63, 103))
//                                    .strokeWidth(15f)
                                else -> polygonOptions.strokeColor(Color.TRANSPARENT)
                                    .fillColor(Color.argb(0, 0, 0, 0))
                                    .strokeWidth(1f)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            val polygon = map.addPolygon(polygonOptions)
                            polygon.isVisible = areAirspacesVisible
                            newPolygons.add(polygon)
                        }
                    }
                }

                airspacePolygons.addAll(newPolygons) // Add polygons to global list
                //executeNextTask()

            } catch (e: Exception) {
                Log.e("GeoJSON", "Error loading boundaries: ${e.localizedMessage}")
                //showBottomProgressBar("❌ Airspace Boundaries Failed")
                //executeNextTask()
            }
        }
    }
    private fun extractPolygonCoordinates(coordinatesArray: JSONArray): List<LatLng> {
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
    private fun toggleAirspace() {
        areAirspacesVisible = !areAirspacesVisible
        airspacePolygons.forEach { it.isVisible = areAirspacesVisible }
        Log.d("Toggle", "Airspace visibility set to $areAirspacesVisible")
    }
    // METAR
    private fun loadAndDrawMetar() {

        lifecycleScope.launch {
            try {
                // ✅ Load cached METAR & TAF data before downloading new ones
                showBottomProgressBar("🌦️ Loading Weather Data")
                metarData = loadMetarDataFromCache(filesDir)
                tafData = loadTafDataFromCache(filesDir)

                updateVisibleMarkers(metarData, tafData) // Show cached data immediately

                // ✅ Download and merge new data
                val newMetars = downloadAndUnzipMetarData(filesDir)
                val newTafs = downloadAndUnzipTafData(filesDir)

                // ✅ Merge new and cached data
                metarData = mergeMetarData(metarData, newMetars)
                tafData = mergeTafData(tafData, newTafs)

                // ✅ Save updated data
                saveMetarDataToCache(metarData, filesDir)
                saveTafDataToCache(tafData, filesDir)

                updateVisibleMarkers(metarData, tafData) // Refresh UI

            } catch (e: Exception) {
                Log.e("METAR_UPDATE", "Error fetching METAR data", e)
                withContext(Dispatchers.Main) {
                    showBottomProgressBar("❌ Weather Data Failed")
                   // executeNextTask()  // ✅ Continue even if failed
                }
            } finally {
                withContext(Dispatchers.Main) {
                    hideBottomProgressBar()
                }
            }
        }
    }
    private fun mergeMetarData(cachedMetars: List<METAR>, newMetars: List<METAR>): List<METAR> {
        val metarMap = cachedMetars.associateBy { it.stationId }.toMutableMap()
        newMetars.forEach { metar -> metarMap[metar.stationId] = metar } // Update with new data
        return metarMap.values.toList()
    }
    private fun mergeTafData(cachedTafs: List<TAF>, newTafs: List<TAF>): List<TAF> {
        val tafMap = cachedTafs.associateBy { it.stationId }.toMutableMap()
        newTafs.forEach { taf -> tafMap[taf.stationId] = taf } // Update with new data
        return tafMap.values.toList()
    }
    //light
    /*private fun showMetarDialog(metar: METAR, taf: TAF?) {
        val messageTextView = TextView(this).apply {
            text = formatAirportDetails(metar)
            textSize = 15f  // ✅ Adjust the text size (Default is ~16sp, so 12sp is smaller)
            setPadding(80, 20, 40, 20)  // ✅ Add padding for better readability
        }
        AlertDialog.Builder(this)
            .setTitle(
                metar.stationId + " - " + metar.flightCategory +
                        (if (taf != null && taf.flightCategory != metar.flightCategory) " (TAF = ${taf.flightCategory})" else "")
            )
            .setView(messageTextView)
            .setPositiveButton("OK", null) // ✅ Closes dialog
            .show()
    }*/
    //dark
    private fun showMetarDialog(metar: METAR, taf: TAF?) {
        // Custom Title TextView (Colored Flight Category)
        val titleTextView = TextView(this).apply {
            val titleText = SpannableStringBuilder()

            // Airport Identifier (Yellow)
            val stationIdStart = titleText.length
            titleText.append(metar.stationId)
            titleText.append(" - ")
            titleText.setSpan(
                ForegroundColorSpan(Color.WHITE),
                stationIdStart,
                titleText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // Flight Condition (Custom Color based on METAR)
            val flightCategoryStart = titleText.length
            titleText.append(metar.flightCategory)
            val metarColor = when (metar.flightCategory) {
                "VFR" -> Color.GREEN
                "MVFR" -> Color.argb(255, 50, 80, 255)
                "IFR" -> Color.RED
                "LIFR" -> Color.MAGENTA
                else -> Color.WHITE
            }

            titleText.setSpan(
                ForegroundColorSpan(metarColor),
                flightCategoryStart,
                titleText.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            // If TAF exists and differs, add the "Becoming" text and TAF condition
            if (taf != null && taf.flightCategory != metar.flightCategory) {
                // "Becoming" text (Yellow)
                val becomingStart = titleText.length
                titleText.append(" → ")
                titleText.setSpan(
                    ForegroundColorSpan(Color.WHITE),
                    becomingStart,
                    titleText.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // TAF Flight Condition (Custom Color based on TAF)
                val tafStart = titleText.length
                titleText.append(taf.flightCategory)
                val tafColor = when (taf.flightCategory) {
                    "VFR" -> Color.GREEN
                    "MVFR" -> Color.argb(255, 50, 80, 255)
                    "IFR" -> Color.RED
                    "LIFR" -> Color.MAGENTA
                    else -> Color.WHITE
                }
                titleText.setSpan(
                    ForegroundColorSpan(tafColor),
                    tafStart,
                    titleText.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            text = titleText
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setPadding(80, 40, 40, 10)
            gravity = Gravity.START // ✅ Align title to the left
        }

        // ✅ Custom Message TextView (Smaller Text)
        val messageTextView = TextView(this).apply {
            text = formatAirportDetails(metar)  // ✅ Use your existing function
            textSize = 13f  // ✅ Set smaller text
            setPadding(80, 10, 40, 20)
            setTextColor(Color.LTGRAY)  // ✅ Set text color to light gray
        }

        // ✅ Create a wrapper layout with a semi-transparent (colored) background
        val bgColor = when (metar.flightCategory) {
            "VFR" -> Color.argb(200, 0, 40, 0)
            "MVFR" -> Color.argb(200, 0, 0, 40)
            "IFR" -> Color.argb(200, 40, 0, 0)
            "LIFR" -> Color.argb(200, 40, 0, 40)
            else -> Color.WHITE
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)  // ✅ Black with transparency
            addView(titleTextView)
            addView(messageTextView)
        }

        // ✅ Create and show AlertDialog
        val alertDialog = AlertDialog.Builder(this)
            .setView(layout)  // ✅ Use custom layout
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .create()

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(bgColor))

        alertDialog.show()
    }

    private fun toggleMetarVisibility() {
        areMetarsVisible = !areMetarsVisible
        metarMarkers.forEach { marker ->
            marker.isVisible = areMetarsVisible
        }
        Log.d("Toggle", "METAR visibility set to $areMetarsVisible")
    }
    private fun createWindBarbBitmap(windSpeedKt: Int, windDirDegrees: Int?): Bitmap? {
        // Skip calm winds or invalid data
        if (windSpeedKt < 4) return null
        //if (windSpeedKt < 4 || windDirDegrees == null) return null

        // Special case: Variable wind (use 360 as magic number)
        val isVariable = windDirDegrees == null

        // Fixed size bitmap (adjust as needed)
        val size = 150
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        return when {
            // Variable wind indicator
            isVariable -> {
                // Draw compass rose circle
                canvas.drawCircle(size / 2f, size / 2f, 40f, paint)

                // Add arrows in 4 cardinal directions
                listOf(0f, 90f, 180f, 270f).forEach { angle ->
                    canvas.save()
                    canvas.rotate(angle, size / 2f, size / 2f)
                    canvas.drawLine(size / 2f, 30f, size / 2f, 50f, paint) // Short arrows
                    canvas.restore()
                }

                // Add wind speed at bottom
                paint.style = Paint.Style.FILL
                paint.textSize = 24f
                paint.textAlign = Paint.Align.CENTER
                canvas.drawText("${windSpeedKt}kt", size / 2f, size - 20f, paint)
                bitmap
            }

            // Normal wind direction (0-359 degrees)
            //windDirDegrees in 0..360 -> {
            else -> {

                val centerX = size / 2f
                val centerY = size / 2f
                val staffLength = size * 0.4f  // Length of main line

                // Rotate canvas to wind direction (wind comes FROM this direction)
                canvas.save()
                if (windDirDegrees != null) {
                    canvas.rotate(windDirDegrees.toFloat(), centerX, centerY)
                }

                // Draw main staff line (center to edge)
                canvas.drawLine(
                    centerX, centerY,
                    centerX, centerY - staffLength,
                    paint
                )

                var remainingKts = windSpeedKt
                val flags = remainingKts / 50
                remainingKts %= 50
                val fullLines = remainingKts / 10
                remainingKts %= 10
                val halfLines = remainingKts / 5

                // Start drawing symbols at staff end
                var currentY = centerY - staffLength

                // Draw flags (50kt) - right side triangles
                repeat(flags) {
                    canvas.drawLine(
                        centerX, currentY,
                        centerX + 30f, currentY - 30f, // Right-leaning flag
                        paint
                    )
                    currentY += 30f  // Move toward station
                }

                // Draw full lines (10kt) - right side
                repeat(fullLines) {
                    canvas.drawLine(
                        centerX, currentY,
                        centerX + 30f, currentY, // Right horizontal line
                        paint
                    )
                    currentY += 20f
                }

                // Draw half lines (5kt) - right side
                repeat(halfLines) {
                    canvas.drawLine(
                        centerX, currentY,
                        centerX + 15f, currentY, // Shorter right line
                        paint
                    )
                    currentY += 20f
                }

                canvas.restore()
                return bitmap
            }
        }
    }
    // TILES (sectional charts)
    private fun toggleSectionalOverlay(map: GoogleMap) {
        if (sectionalOverlay == null) {
            val sectionalTileProvider = SectionalTileProvider(this)
            val tileOverlayOptions = TileOverlayOptions()
                .tileProvider(sectionalTileProvider)
                .transparency(0.0f)
                .zIndex(-1.0f) // ✅ Sectional is the base layer
            sectionalOverlay = map.addTileOverlay(tileOverlayOptions)
        }

        if (terminalOverlay == null) {
            val terminalTileProvider = TerminalTileProvider(this)
            val tileOverlayOptions = TileOverlayOptions()
                .tileProvider(terminalTileProvider)
                .transparency(0.0f)
                .zIndex(-0.5f) // ✅ Terminal is over Sectional
            terminalOverlay = map.addTileOverlay(tileOverlayOptions)
        }

        sectionalVisible = !sectionalVisible // Toggle Sectional visibility
        terminalVisible = sectionalVisible  // Keep Terminal consistent

        sectionalOverlay?.isVisible = sectionalVisible
        terminalOverlay?.isVisible = terminalVisible

        sectionalOverlay?.clearTileCache()
        terminalOverlay?.clearTileCache() // ✅ Ensures smooth transition

        Log.d("Toggle", "Sectional visibility set to $sectionalVisible")
    }
    //Flight plan markers
    @SuppressLint("MissingPermission")
    private fun updateMapWithWaypoints(waypoints: List<String>) {
        //mMap.clear()
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            val latLngList = mutableListOf<LatLng>()

            // ✅ If we got the user's location, and only one airport use it as the starting point
            if (waypoints.size == 1 && location != null) {
                val userLatLng = LatLng(location.latitude, location.longitude)
                latLngList.add(userLatLng)
            }

            // ✅ Add all waypoints from the flight plan
            for (waypoint in waypoints) {
                val latLng = airportMap[waypoint.uppercase()]
                if (latLng != null) {
                    latLngList.add(latLng)
                }
            }

            // ✅ Draw magenta line between waypoints NAV LINES
            if (latLngList.size > 1) {
                val polylineOptions = PolylineOptions()
                    .addAll(latLngList)
                    //.color(Color.argb(255, 255, 16, 240))  // PINK for Current Leg
                    .color(Color.argb(255, 16, 16, 240))  // BLUE for planning
                    .width(20f)
                    .zIndex(2f)  //markers are re-drawn which makes them appear on top :-(
                mMap.addPolyline(polylineOptions)
            }

            // ✅ Center the map on the user's last known location (or first waypoint if no location available)
            /*val currentLatLng = LatLng(location.latitude, location.longitude)
            val focusPoint = currentLatLng ?: latLngList.firstOrNull()
            focusPoint?.let { mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it, 10f)) }*/

            // ✅ Center the map on the route
            if (latLngList.isNotEmpty()) {
                val boundsBuilder = LatLngBounds.builder()
                for (point in latLngList) {
                    boundsBuilder.include(point)
                }
                val bounds = boundsBuilder.build()
                val padding = 100 // pixels of padding around route

                val cameraUpdate = CameraUpdateFactory.newLatLngBounds(bounds, padding)
                mMap.moveCamera(cameraUpdate)
            } else if (location != null) {
                val userLatLng = LatLng(location.latitude, location.longitude)
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 10f))
            }


            // ✅ Simplified Flight Plan Visibility Toggle
            val hasMultipleWaypoints = waypoints.size > 1
            binding.flightInfoLayout.visibility = View.VISIBLE
            binding.flightInfoLayout2.visibility =
                if (hasMultipleWaypoints) View.VISIBLE else View.GONE

            binding.flightInfoLayout.bringToFront()
            binding.flightInfoLayout.requestLayout()
            Log.d("AUTOTOGGLE", "Current visibility: ${binding.flightInfoLayout.visibility}")
            Log.d("AUTOTOGGLE", "Current visibility2: ${binding.flightInfoLayout2.visibility}")
        }
    }

    // Download airport databases
    private fun syncDatabaseFiles(context: Context) {
        showBottomProgressBar("🚨 Updating databases...")
        val localDir = context.filesDir
        val prefs = context.getSharedPreferences("db_versions", Context.MODE_PRIVATE)

        val manifestUrl = "https://regiruk.netlify.app/sqlite/db_manifest.json"

        Thread {
            try {
                val connection = URL(manifestUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("DBSync", "Failed to fetch manifest")
                    return@Thread
                }

                val manifestJson = connection.inputStream.bufferedReader().readText()
                val manifest = JSONObject(manifestJson)

                val dbKeys = listOf("faa_airports", "faa_fixes", "faa_frequencies")

                dbKeys.forEach { key ->
                    val dbInfo = manifest.getJSONObject(key)
                    val version = dbInfo.getString("version")
                    val url = dbInfo.getString("url")
                    val fileName = url.substringAfterLast("/")
                    val localFile = File(localDir, fileName)

                    val storedVersion = prefs.getString("${key}_version", null)

                    if (!localFile.exists() || storedVersion != version) {
                        Log.d("DBSync", "Downloading $fileName (version mismatch or missing)")
                        downloadDbFile(context, url, fileName) { success ->
                            if (success) {
                                prefs.edit().putString("${key}_version", version).apply()
                                Log.d("DBSync", "$fileName downloaded and updated to version $version")
                            } else {
                                Log.e("DBSync", "Failed to download $fileName")
                            }
                        }
                    } else {
                        Log.d("DBSync", "$fileName is up to date")
                    }
                }

            } catch (e: Exception) {
                Log.e("DBSync", "Error syncing DB files: ${e.message}")
            }
        }.start()
        hideBottomProgressBar()
    }

    private fun downloadDbFile(
        context: Context,
        url: String,
        fileName: String,
        onComplete: (Boolean) -> Unit
    ) {
        val saveFile = File(context.filesDir, fileName)

        // ✅ Skip if already downloaded
        if (saveFile.exists()) {
            Log.d("DBDownload", "$fileName already exists, skipping download")
            onComplete(true)
            return
        }

        Thread {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = connection.inputStream
                    saveFile.outputStream().use { output ->
                        inputStream.copyTo(output)
                    }
                    Log.d("DBDownload", "$fileName downloaded successfully")
                    onComplete(true)
                } else {
                    Log.e("DBDownload", "Failed to download $fileName: ${connection.responseCode}")
                    onComplete(false)
                }
            } catch (e: Exception) {
                Log.e("DBDownload", "Error downloading $fileName: ${e.message}")
                onComplete(false)
            }
        }.start()
    }

    // Load Airports from CSV
    private fun loadAirportsFromCSV() {
        val inputStream = resources.openRawResource(R.raw.airports2) // ✅ Use new CSV file
        val reader = BufferedReader(InputStreamReader(inputStream))

        reader.useLines { lines ->
            lines.forEach { line ->
                val tokens = line.split(",") // ✅ Split CSV by commas

                if (tokens.size > 30) { // ✅ Ensure enough fields exist
                    val airportId = tokens[4].trim('"').uppercase() // ✅ ICAO code (ARPT_ID)
                    val city = tokens[6].trim('"') // ✅ City (CITY)
                    val airportName = tokens[12].trim('"') // ✅ Airport Name (ARPT_NAME)
                    val lat = tokens[19].toDoubleOrNull() ?: return@forEach // ✅ Latitude (LAT_DECIMAL)
                    val lon = tokens[24].toDoubleOrNull() ?: return@forEach // ✅ Longitude (LONG_DECIMAL)
                    val elev = tokens[25].toDoubleOrNull() ?: 0.0 // ✅ Elevation (ELEV_FT)
                    val magVar = tokens[28].toDoubleOrNull() ?: 0.0 // ✅ Magnetic Variation (MAG_VARN)
                    val magHemis = tokens[29].trim('"') // ✅ "E" (East) or "W" (West)

                    // ✅ Convert Magnetic Variation: East = Positive, West = Negative
                    val correctedMagVar = if (magHemis == "W") -magVar else magVar

                    airportMap[airportId] = LatLng(lat, lon) // ✅ Store LatLng
                    airportMagVarMap[airportId] = correctedMagVar  // ✅ Store Magnetic Variation
                }
            }
        }
    }

    /*private fun loadAirportsFromCSV() {
        val inputStream = resources.openRawResource(R.raw.airports)
        val reader = BufferedReader(InputStreamReader(inputStream))

        reader.useLines { lines ->
            lines.forEach { line ->
                val tokens = line.split(",") // CSV split
                if (tokens.size > 5) {
                    val id = tokens[1].trim('"') // Airport code (e.g., KBUR)
                    val lat = tokens[4].toDoubleOrNull() ?: return@forEach
                    val lng = tokens[5].toDoubleOrNull() ?: return@forEach
                    airportMap[id] = LatLng(lat, lng) // Store in map
                }
            }
        }
    }*/
}

abstract class BaseTileProvider(
    protected val context: Context,
    protected val baseUrl: String,
    private val localFolder: String
) : TileProvider {
    protected val tileSize = 256

    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val localFile = File(context.filesDir, "tiles/$localFolder/$zoom/$x/$y.png")

        return when {
            localFile.exists() -> loadTileFromFile(localFile)
            //MRK checkTileExists(baseUrl, zoom, x, y) -> loadTileFromURL(zoom, x, y, localFile)
            else -> null
        }
    }

    // this is for the base tiles (wall tiles) included in the app
    protected fun loadTileFromAssets(filePath: String): Tile? {
        return try {
            val inputStream = context.assets.open(filePath)
            val tileData = inputStream.use { it.readBytes() }
            Tile(256, 256, tileData)
        } catch (e: Exception) {
            Log.e("TileProvider", "Tile not found in assets: $filePath")
            null
        }
    }

    // this is for sectional tiles added by downloading
    protected fun loadTileFromFile(file: File): Tile? {
        return try {
            val tileData = file.readBytes()
            Tile(tileSize, tileSize, tileData)
        } catch (e: Exception) {
            Log.e("TileProvider", "Error loading tile from file: ${e.message}")
            null
        }
    }

    protected fun loadTileFromURL(zoom: Int, x: Int, y: Int, saveToFile: File): Tile? {
        val tileUrl = "$baseUrl/$zoom/$x/$y.png"
        return try {
            val connection = URL(tileUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val tileData = connection.inputStream.use { it.readBytes() }

                // Cache the tile locally for offline use
                saveToFile.parentFile?.mkdirs()
                saveToFile.writeBytes(tileData)

                Tile(tileSize, tileSize, tileData)
            } else {
                Log.e("TileProvider", "Tile not found at URL: $tileUrl")
                null
            }
        } catch (e: Exception) {
            Log.e("TileProvider", "Error loading tile from URL: ${e.message}")
            null
        }
    }

    protected fun checkTileExists(baseUrl: String, zoom: Int, x: Int, y: Int): Boolean {
        val tileUrl = "$baseUrl/$zoom/$x/$y.png"
        return try {
            val connection = URL(tileUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD" // Use HEAD to check if file exists without downloading
            connection.connect()
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }
}

class SectionalTileProvider(context: Context) : BaseTileProvider(
    context,
    baseUrl = "https://regiruk.netlify.app/Sectional/30",
    localFolder = "Sectional"
) {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val wallFilePath = "tiles/$zoom/$x/$y.png"
        val sectionalFile = File(context.filesDir, "tiles/Sectional/$zoom/$x/$y.png")

        return when {
            // ✅ Load Wall tiles first (zoom 4-7)
            zoom in 4..7 -> loadTileFromAssets(wallFilePath)

            // ✅ Load Sectional tiles (zoom 8-12)
            zoom in 8..12 && sectionalFile.exists() -> loadTileFromFile(sectionalFile)
            zoom in 8..12 && checkTileExists(baseUrl, zoom, x, y) -> loadTileFromURL(zoom, x, y, sectionalFile)

            else -> null // No tile available
        }
    }
}

class TerminalTileProvider(context: Context) : BaseTileProvider(
    context,
    baseUrl = "https://regiruk.netlify.app/Terminal",
    localFolder = "Terminal"
) {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val terminalFile = File(context.filesDir, "tiles/Terminal/$zoom/$x/$y.png")
        return when {
            terminalFile.exists() -> loadTileFromFile(terminalFile)
            checkTileExists(baseUrl, zoom, x, y) -> loadTileFromURL(zoom, x, y, terminalFile)
            else -> null // No tile available
        }
    }
}
