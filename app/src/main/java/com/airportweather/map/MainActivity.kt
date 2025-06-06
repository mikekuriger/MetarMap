package com.airportweather.map

import android.Manifest
import android.annotation.SuppressLint
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
import android.widget.Toast
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
import android.view.MenuItem
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import java.util.LinkedList
import java.util.Queue


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

    private val taskQueue: Queue<() -> Unit> = LinkedList()

    private var isFollowingUser = true
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

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
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

/*    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }*/

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ✅ Initialize Navigation Drawer
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        navView.setNavigationItemSelectedListener(this)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val userLatLng = LatLng(location.latitude, location.longitude)
                    Log.d("LocationUpdate", "User Location: $userLatLng")

                    // ✅ Only follow the user if they haven't moved the map
                    if (isFollowingUser) {
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, mMap.cameraPosition.zoom))
                    }
                }
            }
        }

        requestLocationUpdates()

        //VERSION
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

//    private fun queueStartupTasks() {
//        taskQueue.add { showBottomProgressBar("✈️ Downloading TFR Data..."); loadAndDrawTFR() }
//        taskQueue.add { showBottomProgressBar("🌦️ Downloading Weather Data..."); loadAndDrawMetar() }
//        taskQueue.add { showBottomProgressBar("🗺️ Downloading Airspace Data..."); loadAndDrawAirspace(mMap, this) }
//        taskQueue.add { showBottomProgressBar("✅ Initialization Complete"); hideBottomProgressBar() }
//    }
//
//    private fun executeNextTask() {
//        if (!::mMap.isInitialized) {
//            Log.d("StartupQueue", "⏳ Waiting for mMap to be ready before running tasks...")
//            return // ✅ Do nothing until `mMap` is initialized
//        }
//
//        val nextTask = taskQueue.poll()
//        if (nextTask != null) {
//            Log.d("StartupQueue", "🚀 Running task: $nextTask")
//            nextTask.invoke()
//        } else {
//            Log.d("StartupQueue", "✅ All startup tasks completed.")
//            hideBottomProgressBar()
//        }
//    }


    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_downloads -> startActivity(Intent(this, DownloadActivity::class.java))
            //R.id.nav_settings -> startActivity(Intent(this, com.airportweather.map.SettingsActivity::class.java))
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

    // ✅ Start Location Updates
    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000).build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), LOCATION_PERMISSION_REQUEST_CODE)
            return
        }

        fusedLocationClient.requestLocationUpdates(locationRequest, object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val userLatLng = LatLng(location.latitude, location.longitude)
                    Log.d("LocationUpdate", "User Location: $userLatLng")

                    // ✅ Follow the user *only* if they haven't moved the map
                    if (isFollowingUser) {
                        mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, mMap.cameraPosition.zoom))
                    }
                }
            }
        }, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            mMap.isMyLocationEnabled = true

            // ✅ If user taps "My Location" button, resume following
            mMap.setOnMyLocationButtonClickListener {
                isFollowingUser = true
                false  // ✅ Allows default behavior (centering the map)
            }
        }
    }

    @SuppressLint("PotentialBehaviorOverride")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.mapType = GoogleMap.MAP_TYPE_NORMAL // Options: NORMAL, SATELLITE, TERRAIN, HYBRID
        mMap.isTrafficEnabled = false
        mMap.uiSettings.isTiltGesturesEnabled = false
        mMap.uiSettings.isRotateGesturesEnabled = false
        mMap.setMinZoomPreference(5.0f) // Set minimum zoom out level
        mMap.setMaxZoomPreference(15.0f) // Set maximum zoom in level
        mMap.setInfoWindowAdapter(CustomInfoWindowAdapter(this))
        mMap.setOnCameraIdleListener {
            saveMapPosition()
            updateVisibleMarkers(metarData, tafData)
        }

        checkLocationPermission()
        enableMyLocation()

        moveToLastSavedLocationOrCurrent()
        showBottomProgressBar("🚨 Initializing all the things")
        requestLocationUpdates()

        // ✅ Stop following when the user manually moves the map
        mMap.setOnCameraMoveListener {
            isFollowingUser = false
        }


//        // display ZOOM level for debugging maps DEBUG
//        val zoomLevelText: TextView = findViewById(R.id.zoomLevelText)
//        mMap.setOnCameraIdleListener {
//            val zoom = mMap.cameraPosition.zoom
//            zoomLevelText.text = "Zoom: ${zoom.toInt()}" // Show zoom level as an integer
//            Log.d("ZoomDebug", "Current Zoom Level: $zoom") // ✅ Debugging log
//        }

        // Initialize the tile overlay toggle
        val vfrSecButton = findViewById<Button>(R.id.toggle_vfrsec_button)
        var isSectionalVisible = false
        vfrSecButton.setOnClickListener {
            isSectionalVisible = !isSectionalVisible
            toggleSectionalOverlay(mMap)
            updateButtonState(vfrSecButton, isSectionalVisible)
        }

//        // ✅ Initialize the task queue
//        queueStartupTasks()
//
//        // ✅ Start the first task
//        executeNextTask()

        // **Load TFR GeoJSON**
        loadAndDrawTFR()

        // **Load Airspace Boundaries**
        loadAndDrawAirspace(mMap, this)

        // **Load Metars**
        loadAndDrawMetar()

        // ** refresh weather data
        startAutoRefresh(15)

        // Initialize the airspace toggle button
        val airspaceButton = findViewById<Button>(R.id.toggle_airspace_button)
        var isAirspaceVisible = true
        airspaceButton.setOnClickListener {
            isAirspaceVisible = !isAirspaceVisible
            toggleAirspace()
            updateButtonState(airspaceButton, isAirspaceVisible)
        }

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
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
            }
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
                    MapLayer.FlightConditions -> createFlightConditionMarker(
                        metar,
                        taf,
                        location
                    )
                    MapLayer.Wind -> createMetarDotForWindLayer(metar, location)
                    MapLayer.Temperature -> createTemperatureMarker(metar, location)
                    MapLayer.Altimeter -> createAltimeterMarker(metar, location)
                    MapLayer.Ceiling -> createCeilingMarker(metar, location)
                    MapLayer.Clouds -> createCloudMarker(metar, location)
                }
                marker?.let { metarMarkers.add(it) }

                // ✅ Add wind barb only when "Wind Barbs" layer is selected
                if (currentLayer == MapLayer.Wind) {
                    val windMarker = createWindMarker(metar, taf, location)
                    windMarker?.let { metarMarkers.add(it) }
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
        //if (windDir!! >= 100) return null

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
            size = 60,
            fillColor = getFlightCategoryColor(metar.flightCategory),
            borderColor = Color.WHITE, // ✅ White border to "skip" the TAF border effect
            borderWidth = 13
        )
        return createDotMarker(metar, null, location, style) // No TAF border
    }
    private fun createTemperatureMarker(metar: METAR, location: LatLng): Marker? {
        metar.tempC?.let {
            val tempColor = when {
                it >= 27 -> Color.RED
                it <= 5 -> Color.BLUE
                else -> Color.WHITE
            }
            val bgColor = Color.BLACK
            val bitmap = createTextBitmap("${celsiusToFahrenheit(it)}°F", tempColor, bgColor)
            //val bitmap = createTextBitmap("${it}°C", tempColor)
            return mMap.addMarker(MarkerOptions()
                .position(location)
                .icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                .visible(areMetarsVisible)
                .anchor(0.5f, 0.5f))
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
                .anchor(0.5f, 0.5f))
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
                .anchor(0.5f, 0.5f))
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
                .anchor(0.5f, 0.5f))
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
            button.setBackgroundColor(Color.parseColor("#90000000")) // Active state
            button.setTextColor(Color.WHITE)
        } else {
            button.setBackgroundColor(Color.parseColor("#90000000")) // Inactive state
            button.setTextColor(Color.BLACK)
        }
    }
    // TFR
    private fun loadAndDrawTFR() {
        lifecycleScope.launch {
            try {
                showBottomProgressBar("✈️ Loading TFR Data")
                val tfrFile = getOrDownloadTfrs(filesDir)
                if (tfrFile != null) {
                    println("✅ Parsing tfr data...")
                    val tfrFeatures = parseTFRGeoJson(tfrFile)
                    drawTFRPolygons(mMap, tfrFeatures)
                    //showBottomProgressBar("✈️ TFR Data Loaded")
                } else {
                    showBottomProgressBar("❌ No TFR Data Available")
                    println("🚨 No TFR data available")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "TFR download failed: ${e.message}")
                showBottomProgressBar("❌ TFR Data Failed")
            } finally {
                //executeNextTask()  // ✅ Continue with next task
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
                showBottomProgressBar("❌ Airspace Boundaries Failed")
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
                    //showBottomProgressBar("🌦️ Weather Data Loaded")
                    //executeNextTask()
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
    private fun showMetarDialog(metar: METAR, taf: TAF?) {
        val messageTextView = TextView(this).apply {
            text = formatAirportDetails(metar)
            textSize = 15f  // ✅ Adjust the text size (Default is ~16sp, so 12sp is smaller)
            setPadding(40, 20, 40, 20)  // ✅ Add padding for better readability
        }
        AlertDialog.Builder(this)
            .setTitle(
                metar.stationId + " - " + metar.flightCategory +
                        (if (taf != null && taf.flightCategory != metar.flightCategory) " (TAF = ${taf.flightCategory})" else "")
            )
            //.setMessage(formatAirportDetails(metar))
            .setView(messageTextView)
            .setPositiveButton("OK", null) // ✅ Closes dialog
            .show()
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
    /*private fun toggleSectionalOverlay(map: GoogleMap) {

        if (sectionalOverlay == null) {
            val tileProvider = SectionalTileProvider(this)
            val tileOverlayOptions = TileOverlayOptions()
                .tileProvider(tileProvider)
                .transparency(0.0f)
                .zIndex(-1.0f)
            sectionalOverlay = map.addTileOverlay(tileOverlayOptions)
        }
        sectionalVisible = !sectionalVisible
        sectionalOverlay?.isVisible = sectionalVisible
        sectionalOverlay?.clearTileCache() // Force refresh
        Log.d("Toggle", "Sectional visibility set to $sectionalVisible")
    }*/

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
            //MRK zoom in 8..12 && checkTileExists(baseUrl, zoom, x, y) -> loadTileFromURL(zoom, x, y, sectionalFile)

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
            //MRK checkTileExists(baseUrl, zoom, x, y) -> loadTileFromURL(zoom, x, y, terminalFile)
            else -> null // No tile available
        }
    }
}
