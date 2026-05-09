package com.airportweather.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.airportweather.map.databinding.ActivityMainBinding
import com.google.android.gms.location.*
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polygon
import com.google.android.gms.maps.model.PolygonOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileOverlay
import com.google.android.gms.maps.model.TileOverlayOptions
import com.google.android.gms.maps.model.TileProvider
import com.google.android.material.navigation.NavigationView
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import org.json.JSONArray
import org.json.JSONObject
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
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.text.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.view.WindowCompat
import com.airportweather.map.utils.AirportDatabaseHelper
import com.airportweather.map.utils.DatabaseSyncUtils
import com.airportweather.map.utils.FlightPlanHolder
import com.airportweather.map.utils.FlightPlanUtils
import com.airportweather.map.utils.loadMetarDataFromCache
import com.airportweather.map.utils.loadTafDataFromCache
import com.airportweather.map.utils.saveMetarDataToCache
import com.airportweather.map.utils.saveTafDataToCache
import com.airportweather.map.utils.Waypoint
import java.net.InetAddress

// Data classes moved to: WeatherModels.kt, TfrModels.kt, StratuxModels.kt, FlightData.kt
// METAR/TAF download + parse moved to: WeatherRepository.kt
// TFR download + parse moved to: TfrRepository.kt

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

    var DEBUG_LOGGING_ENABLED = true
    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var binding: ActivityMainBinding
    private lateinit var recorder: KMLRecorder
    private lateinit var trackFileLauncher: ActivityResultLauncher<Intent>

    private var airspaceLoaded = false
    private var tfrLoaded = false
    private var metarLoaded = false
    private var sectionalLoaded = false
    private var areTFRsVisible = true
    private var areAirspacesVisible = true
    private var areMetarsVisible = true
    private var sectionalVisible = false
    private var terminalVisible = sectionalVisible
    private var isFollowingUser = false
    private val tfrPolygons = mutableListOf<Polygon>()
    private val airspacePolygons = mutableListOf<Polygon>()
    private val metarMarkers = mutableListOf<Marker>()
    private val tfrPolygonInfo = mutableMapOf<Polygon, MutableList<TFRProperties>>()
    private var metarData: List<METAR> = emptyList()
    private var tafData: List<TAF> = emptyList()
    private var currentLayerName: String = "FlightConditions"
    private var sectionalOverlay: TileOverlay? = null
    private var terminalOverlay: TileOverlay? = null
    private var lastKnownUserLocation: Location? = null
    private val activeSpeed = 10
    private val plannedAirSpeed = 95 // make editable in the UI
    private var showVersion = true
    private var showZoom = true
    private lateinit var sharedPrefs: SharedPreferences
    private var isTrafficEnabled = true
    private var hideDistantTraffic = true
    private var isMetarVisible = true
    private val handler = Handler(Looper.getMainLooper())
    private var stratuxStarted = false
    private var isStratuxGpsActive = false
    private val trafficMap = mutableMapOf<String, TrafficTarget>()
    private val aircraftMarkers = mutableMapOf<String, Marker>()
    private val aircraftLabels = mutableMapOf<String, Marker>()
    private val aircraftLabelsBottom = mutableMapOf<String, Marker>()
    private var staleTimeout = 10
    private val updateRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()

            if (isTrafficEnabled && lastKnownUserLocation != null) {
                for ((hex, marker) in aircraftMarkers) {
                    val target = trafficMap[hex] ?: continue
                    val elapsedSec = (now - target.lastUpdated) / 1000.0
                    if (elapsedSec > staleTimeout) continue  // Skip stale

                    val predicted = extrapolatePosition(target, elapsedSec)
                    val current = marker.position

                    val smoothLat = (current.latitude * 0.7) + (predicted.latitude * 0.3)
                    val smoothLon = (current.longitude * 0.7) + (predicted.longitude * 0.3)

                    val smoothed = LatLng(smoothLat, smoothLon)
                    marker.position = smoothed
                    aircraftLabels[hex]?.position = smoothed
                    aircraftLabelsBottom[hex]?.position = smoothed
                }
            }
            handler.postDelayed(this, 1000)
        }
    }

    private val trafficHandler = Handler(Looper.getMainLooper())
    private val pruneHandler = Handler(Looper.getMainLooper())
    private val pruneRunnable = object : Runnable {
        override fun run() {
            pruneStaleAircraft()
            pruneHandler.postDelayed(this, 30000)  // run every 30 seconds
        }
    }
    private var lastAltitude: Double? = null
    private var lastTime: Long? = null
    private var trackLine: Polyline? = null
    private var stopStartTime: Long? = null  // for recorging tracks
    private val recentRedTargets = mutableMapOf<String, Long>()  // hex → timestamp when red was last true
    private val viewModel: MainViewModel by viewModels()
    private lateinit var markerFactory: MarkerFactory

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
        // const val ACTIVE_SPEED = 10
    }
// end vars

    sealed class MapLayer {
        data object FlightConditions : MapLayer()
        data object Wind : MapLayer()
        data object Temperature : MapLayer()
        data object Altimeter : MapLayer()
        data object Ceiling : MapLayer()
        data object Clouds : MapLayer()
        data object None : MapLayer()

        companion object {
            fun fromName(name: String): MapLayer {
                return when (name) {
                    "FlightConditions" -> FlightConditions
                    "Altimeter" -> Altimeter
                    "Temperature" -> Temperature
                    "Ceiling" -> Ceiling
                    "Clouds" -> Clouds
                    "Wind Barbs" -> Wind
                    "None" -> None
                    else -> FlightConditions
                }
            }
        }
    }

    // print out saved prefs
    private fun prefsDump() {
        val prefNames =
            listOf("FlightPlanPrefs", "MapSettings", "AppPrefs", "SavedLocation", "db_versions")
        for (name in prefNames) {
            val prefs = getSharedPreferences(name, MODE_PRIVATE)
            for ((key, value) in prefs.all) {
                Log.d("PrefsDump", "$name → $key = $value")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Respect system UI insets
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefsDump()
        handler.postDelayed(updateRunnable, 1000)
        //handler.post(updateRunnable)

        // ✅ Initialize View Binding
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // grab preferences
        sharedPrefs = getSharedPreferences("MapSettings", MODE_PRIVATE)

        //areAirspacesVisible = sharedPrefs.getBoolean("show_airspace", true)
        areMetarsVisible = sharedPrefs.getBoolean("show_metars", true)
        //sectionalVisible = sharedPrefs.getBoolean("show_chart", true)
        //terminalVisible = sectionalVisible
        //areTFRsVisible = sharedPrefs.getBoolean("show_tfrs", true)

        // ✅ Load airports from CSV (weather data)
        //loadAirportsFromCSV()

        // ✅ Download databases and load airports
        lifecycleScope.launch {
            DatabaseSyncUtils.syncAirportDatabases(
                this@MainActivity,
                getSharedPreferences("db_versions", MODE_PRIVATE)
            )
            //loadAirportsFromDatabase()
        }

        // side buttons
        // recordButton
        val recordButton = binding.recordButton
        recordButton.setOnClickListener {
            if (!recorder.isRecording) {
                recorder.start()
                //Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
                Log.d("KMLRecorder_main", "Button pressed — recording started")
            } else {
                recorder.stop()
                //Toast.makeText(this, "Recording stopped", Toast.LENGTH_SHORT).show()
                Log.d("KMLRecorder_main", "Button pressed — recording stopped")
            }

            // ✅ Always reflect current state
            val newColor = if (recorder.isRecording) Color.RED else Color.BLACK
            recordButton.setColorFilter(newColor)
        }


        // ✅ Stratux Button
        val stratuxButton = binding.stratuxButton
        stratuxButton.setOnClickListener {
            val intent = Intent(this, StratuxStatusActivity::class.java)
            startActivity(intent)
        }

        // ✅ Flight info button
        val flightPlanButton = binding.flightPlanButton
        val flightInfoLayout = binding.flightInfoLayout
        flightPlanButton.setOnClickListener {
            Log.d("FlightToggle", "Flight Plan button clicked")  // ✅ Log click event

            val isVisible = flightInfoLayout.visibility == View.VISIBLE
            Log.d(
                "FlightToggle",
                "Current visibility: $isVisible"
            )  // ✅ Log visibility before toggle

            flightInfoLayout.visibility = if (isVisible) View.GONE else View.VISIBLE

            flightInfoLayout.bringToFront()
            flightInfoLayout.requestLayout()

            Log.d(
                "FlightToggle",
                "New visibility: ${flightInfoLayout.visibility}"
            )  // ✅ Log visibility after toggle
        }

        // ✅ NavLog Button
        val navLogButton = binding.navLogButton
        navLogButton.setOnClickListener {
            val intent = Intent(this, NavLogActivity::class.java)
            startActivity(intent)
        }

        // ✅ Settings / Options Button
        val settingsButton = binding.settingsButton
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            //trackFileLauncher.launch(intent)
            startActivity(intent)
        }

        // ✅ Follow Button
        val followButton = binding.customCenterButton
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

        // ✅ Initialize Navigation Drawer
        drawerLayout = binding.drawerLayout
        navView = binding.navView
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        navView.setNavigationItemSelectedListener(this)

        // location stuff
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                if (isStratuxGpsActive) return  // skip if Stratux is driving
                locationResult.lastLocation?.let { handleNewLocation(it) }
            }
        }

        // location updates
        requestLocationUpdates()

        //Display VERSION
        val versionText = binding.versionText
        if (showVersion) {
            versionText.visibility = View.VISIBLE
        } else {
            versionText.visibility = View.GONE
        }
        versionText.text = getString(
            R.string.app_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )

        // Initialize the map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // load saved layers and preferences
        val sharedPrefs = getSharedPreferences("MapSettings", MODE_PRIVATE)
        currentLayerName =
            sharedPrefs.getString("selectedLayer", "FlightConditions") ?: "FlightConditions"

        Log.d("MapDebug", "Loaded layer: $currentLayerName")

        // initialize spinner and adapter (for weather choices)
        val layerSpinner = binding.layerSelector
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

        // ADSB stratux
        lastKnownUserLocation?.let { checkStratuxAndConnectIfEnabled(it) }
        //trafficHandler.post(trafficRunnable)

        // prune aircraft that are no longer transmitting
        pruneHandler.post(pruneRunnable)

        //KML Recorder (record gps track)
        recorder = KMLRecorder(this)


        //KML files (for display)
        trackFileLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == RESULT_OK) {
                    val filePath = result.data?.getStringExtra("selectedTrackFile")
                    if (filePath != null) {
                        val file = File(filePath)
                        loadTrackFromKml(file)
                    }
                }
            }
    }
    // end of onCreate

    override fun onResume() {
        super.onResume()
        if (::mMap.isInitialized) {
            loadMapPreferences()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateRunnable)
        pruneHandler.removeCallbacks(pruneRunnable)
        trafficHandler.removeCallbacksAndMessages(null)
        if (::markerFactory.isInitialized) markerFactory.dispose()
        super.onDestroy()
        StratuxManager.disconnectAll()
    }

    // settings
    private fun loadMapPreferences() {
        val prefs = getSharedPreferences("MapSettings", MODE_PRIVATE)
        val showAirspace = prefs.getBoolean("show_airspace", true)
        val showTfrs = prefs.getBoolean("show_tfrs", true)
        val showMetars = prefs.getBoolean("show_metars", true)
        val showChart = prefs.getBoolean("show_chart", true)

        // Apply logic to show/hide layers
        setLayerVisibility(showAirspace, showTfrs, showMetars, showChart)
    }

    private fun setLayerVisibility(
        showAirspace: Boolean,
        showTfrs: Boolean,
        showMetars: Boolean,
        showChart: Boolean,
    ) {
        toggleAirspace(showAirspace)
        updateButtonState(binding.toggleAirspaceButton, showAirspace)

        toggleTFRVisibility(showTfrs)
        updateButtonState(binding.toggleTfrButton, showTfrs)

        toggleMetarVisibility(showMetars)
        updateButtonState(binding.toggleMetarButton, showMetars)

        toggleSectionalOverlay(mMap, showChart)
        updateButtonState(binding.toggleVfrsecButton, showChart)

    }

    // ✅ Navigation Drawer
    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_downloads -> startActivity(Intent(this, DownloadActivity::class.java))
            R.id.nav_flightplanning -> startActivity(Intent(this, FlightPlanActivity::class.java))
            R.id.nav_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.nav_tracks -> {
                val intent = Intent(this, TracksActivity::class.java)
                trackFileLauncher.launch(intent)
            }
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
    private fun requestLocationUpdates() {
        Log.d("LocationUpdate", "requestLocationUpdates was triggered")
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 50).build()

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
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )

    }

    private fun deleteAllTracks(context: Context) {
        val trackDir = File(context.getExternalFilesDir(null), "tracks")
        if (trackDir.exists() && trackDir.isDirectory) {
            val deleted = trackDir.listFiles()?.map { it.delete() } ?: emptyList()
            Log.d("TrackCleanup", "Deleted ${deleted.count { it }} track files")
        } else {
            Log.d("TrackCleanup", "Track directory not found")
        }
    }


    // Handles All Calculations for flight planning (via db)
    private fun calculateFlightData(
        location: Location,
        flightPlan: FlightPlan
    ): FlightData {
        val userLatLng = LatLng(location.latitude, location.longitude)
        val groundSpeed = location.speed.toDouble() * 1.94384  // Convert to knots
        val altitude = location.altitude * 3.28084  // Convert to feet
        val activeLeg = flightPlan.legs.firstOrNull { it.active } ?: return FlightData.empty()

        val wpFrom = activeLeg.from
        val wpTo = activeLeg.to

        val fromLatLng = LatLng(wpFrom.lat, wpFrom.lon)
        val toLatLng = LatLng(wpTo.lat, wpTo.lon)
        val magVar = wpFrom.magVar

        val track = location.bearing.toDouble()
        val magneticTrack = (track + magVar + 360) % 360

        //val bearingToWp = calculateBearing(userLatLng.latitude, userLatLng.longitude, toLatLng.latitude, toLatLng.longitude)
        val trueCourse = FlightPlanUtils.calculateTrueCourse(userLatLng.latitude, userLatLng.longitude, toLatLng.latitude, toLatLng.longitude)
        val bearingToWp = (trueCourse + magVar + 360) % 360
        val distanceToWp = FlightPlanUtils.calculateDistance(userLatLng.latitude, userLatLng.longitude, toLatLng.latitude, toLatLng.longitude)
        val etaMinutes = calculateETA(distanceToWp, groundSpeed, plannedAirSpeed)
        val eta = formatETA(etaMinutes)

        return FlightData(
            wpLocation = toLatLng,
            currentLeg = "${wpFrom.name} → ${wpTo.name}",
            track = magneticTrack,
            bearing = bearingToWp,
            distance = distanceToWp,
            groundSpeed = groundSpeed,
            plannedAirSpeed = plannedAirSpeed,
            altitude = altitude,
            eta = eta,
            waypoints = listOf(wpTo) // or flightPlan.legs.map { it.to }
        )
    }

    /*private fun calculateFlightData(
        location: Location,
        waypoints: List<Waypoint>?
    ): FlightData {
        val dbHelper = AirportDatabaseHelper(this)
        val userLatLng = LatLng(location.latitude, location.longitude)
        val groundSpeed = location.speed.toDouble() * 1.94384  // Convert to knots
        val altitude = location.altitude * 3.28084  // Convert to feet

        val wpName: String = waypoints?.getOrNull(0)?.name ?: "direct"
        val wp2Name: String =
            waypoints?.getOrNull(1)?.name ?: waypoints?.getOrNull(0)?.name ?: "----"

        val wp2 = dbHelper.lookupWaypoint(wp2Name)
        if (wp2 == null || wp2.type == "FIX") {
            Log.i("FlightCalc", "Skipping airport check for FIX: $wp2Name")
            return FlightData.empty()
        }

        val airportInfo = dbHelper.getAirportInfo(wp2Name)
        if (airportInfo == null) {
            Log.e("FlightCalc", "No airport data for $wp2Name")
            return FlightData.empty()
        }

        val wpLocation = LatLng(airportInfo.lat, airportInfo.lon)
        val magVar = airportInfo.magVar

        val track = location.bearing.toDouble()
        //magVar is negative if east, positive if west - calculated in AirportDatabaseHelper
        val magneticTrack = (track + magVar + 360) % 360 // MagVar1

        val bearingWp = calculateBearing(
            this,             // context
            userLatLng,       // current (user) location
            wpLocation,       // target waypoint
            wp2Name           // airport ID to look up magVar
        )

        val distanceWp = calculateDistance(userLatLng, wpLocation)
        val etaMinutes = calculateETA(distanceWp, groundSpeed, plannedAirSpeed)
        val eta = formatETA(etaMinutes)

        return FlightData(
            wpLocation = wpLocation,
            currentLeg = "$wpName → $wp2Name",
            track = magneticTrack,
            bearing = bearingWp,
            distance = distanceWp,
            groundSpeed = groundSpeed,
            plannedAirSpeed = plannedAirSpeed,
            altitude = altitude,
            eta = eta,
            waypoints = waypoints ?: emptyList()
        )
    }*/

    // calculate bearing and distance to next waypoint
/*    private fun calculateBearing(
        context: Context,
        currentLocation: LatLng,
        nextWaypoint: LatLng,
        airportId: String
    ): Double {
        val lat1 = Math.toRadians(currentLocation.latitude)
        val lon1 = Math.toRadians(currentLocation.longitude)
        val lat2 = Math.toRadians(nextWaypoint.latitude)
        val lon2 = Math.toRadians(nextWaypoint.longitude)
        val deltaLon = lon2 - lon1
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        val bearing = Math.toDegrees(atan2(y, x))

        val dbHelper = AirportDatabaseHelper(context)
        val info = dbHelper.getAirportInfo(airportId)
        val magVar = info?.magVar ?: 0.0  // #2

        // magVar is negative if east, positive if west - calculated in AirportDatabaseHelper
        return (bearing + magVar + 360) % 360 // MagVar2
    }*/

/*    private fun calculateDistance(currentLocation: LatLng, nextWaypoint: LatLng): Double {
        val earthRadiusNM = 3440.065
        val lat1 = Math.toRadians(currentLocation.latitude)
        val lon1 = Math.toRadians(currentLocation.longitude)
        val lat2 = Math.toRadians(nextWaypoint.latitude)
        val lon2 = Math.toRadians(nextWaypoint.longitude)
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusNM * c  // ✅ Distance in nautical miles (NM)
    }*/

    private fun calculateETA(
        distanceNM: Double,
        groundSpeedKnots: Double,
        plannedAirSpeed: Int
    ): Double {
        Log.d(
            "ETA",
            "distanceNM: $distanceNM, groundSpeedKnots: $groundSpeedKnots, plannedAirSpeed: $plannedAirSpeed"
        )
        return if (groundSpeedKnots > activeSpeed) {
            (distanceNM / groundSpeedKnots) * 60
        } else {
            (distanceNM / plannedAirSpeed) * 60
        }
    }

    @SuppressLint("DefaultLocale")
    private fun formatETA(etaMinutes: Double): String {
        return if (etaMinutes >= 60) {
            val hours = (etaMinutes / 60).toInt()
            val minutes = (etaMinutes % 60).toInt()
            String.format("%d:%02d", hours, minutes)  // ✅ Example: 1:05 (1 hour, 5 min)
        } else {
            val minutes = (etaMinutes).toInt()
            String.format("%d", minutes)  // ✅ Example: "12.5 min"
        }
    }

    private fun calculateTotalDistance(waypoints: List<Waypoint>): Double {
        var totalDistance = 0.0

        for (i in 0 until waypoints.size - 1) {
//            val from = LatLng(waypoints[i].lat, waypoints[i].lon)
//            val to = LatLng(waypoints[i + 1].lat, waypoints[i + 1].lon)
//            totalDistance += FlightPlanUtils.calculateDistance(from, to)
            totalDistance += FlightPlanUtils.calculateDistance(waypoints[i].lat, waypoints[i].lon, waypoints[i + 1].lat, waypoints[i + 1].lon)
        }

        return totalDistance
    }


    @SuppressLint("SetTextI18n")
    private fun updateFlightInfo(data: FlightData) {
        val isPlanningMode = data.groundSpeed < activeSpeed
        val etaColor = if (isPlanningMode) Color.CYAN else Color.WHITE
        val etaDestColor = if (isPlanningMode) Color.CYAN else Color.WHITE

        binding.currentLeg.text = data.currentLeg
        binding.trackText.text = "${data.track.roundToInt()}°"
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

        //val destination = data.waypoints.lastOrNull() ?: "----"
        //binding.destText.text = destination
        val destinationName = data.waypoints.lastOrNull()?.name ?: "----"
        binding.destText.text = destinationName
        binding.etaDestText.setTextColor(etaDestColor)

        //val dbHelper = AirportDatabaseHelper(this)
        //val totalDistance = calculateTotalDistance(data.waypoints, dbHelper)
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
        markerFactory = MarkerFactory(mMap, ::formatAirportDetails).also {
            it.areMetarsVisible = areMetarsVisible
        }
        observeViewModel()
        mMap.mapType = GoogleMap.MAP_TYPE_NORMAL // Options: NORMAL, SATELLITE, TERRAIN, HYBRID
        //mMap.isTrafficEnabled = false
        mMap.uiSettings.isTiltGesturesEnabled = false
        mMap.uiSettings.isRotateGesturesEnabled = false
        mMap.uiSettings.isMyLocationButtonEnabled = false
        mMap.setMinZoomPreference(5.0f) // Set minimum zoom out level
        mMap.setMaxZoomPreference(15.0f) // Set maximum zoom in level
        mMap.setInfoWindowAdapter(CustomInfoWindowAdapter(this))

        // Handle Permissions
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            mMap.isMyLocationEnabled = true
        }

        showBottomProgressBar("🚨 Initializing all the things")

        // custom map styles
        try {
            val selectedStyle = MapStyleManager.getStyle(this)
            Log.d("MapStyle", "Loading style: ${selectedStyle.name}")
            googleMap.setMapStyle(
                MapStyleOptions.loadRawResourceStyle(
                    this,
                    selectedStyle.rawResId
                )
            )
        } catch (e: Resources.NotFoundException) {
            Log.e("MapStyle", "Can't find style. Error: ", e)
        }


//        // ✅ Apply the custom dark mode style
//        try {
//            val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
//            if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
//                googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_style_modest))
//            } else {
//                try {
//                    val selectedStyle = MapStyleManager.getStyle(this)
//                    googleMap.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, selectedStyle.rawResId))
//                } catch (e: Resources.NotFoundException) {
//                    Log.e("MapStyle", "Can't find style. Error: ", e)
//                }
//            }
//        } catch (e: Resources.NotFoundException) {
//            Log.e("MapStyle", "Can't find style. Error: ", e)
//        }

        // Set listener for camera movement
        mMap.setOnCameraIdleListener {  //1
            saveMapPosition()
            updateVisibleMarkers(metarData, tafData)
            // Optionally show zoom for debug
            if (showZoom) {
                val zoom = mMap.cameraPosition.zoom
                Log.d("ZoomDebug", "Current Zoom Level: $zoom")
                binding.zoomText.text = "Zoom: ${zoom.toInt()}"
                binding.zoomText.visibility = View.VISIBLE
            } else {
                binding.zoomText.visibility = View.GONE
            }
        }

        mMap.setOnCameraMoveStartedListener { reason ->
            // Only disable follow if the move was triggered by a gesture.
            if (reason == GoogleMap.OnCameraMoveStartedListener.REASON_GESTURE) {
                isFollowingUser = false
                val customCenterButton = binding.customCenterButton
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
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            //settingsLauncher.launch(intent)
            //showFirstLaunchDialog()
        } else {
            moveToLastSavedLocationOrCurrent()
        }

        // **Load TFR GeoJSON**
        if (!tfrLoaded) {
            loadAndDrawTFR()
            tfrLoaded = true

            val tfrButton = binding.toggleTfrButton
            var isTFRVisible = sharedPrefs.getBoolean("show_tfrs", true)
            // toggle button state based on shared preferences
            updateButtonState(tfrButton, isTFRVisible)
            toggleTFRVisibility(isTFRVisible)

            tfrButton.setOnClickListener {
                isTFRVisible = !isTFRVisible
                // toggle in savedprefs
                sharedPrefs.edit().putBoolean("show_tfrs", isTFRVisible).apply()
                toggleTFRVisibility(isTFRVisible)
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

        // ✅ Only load airspace once
        if (!airspaceLoaded) {
            loadAndDrawAirspace(mMap, this)

            // Initialize the airspace toggle button
            var isAirspaceVisible = sharedPrefs.getBoolean("show_airspace", true)
            val airspaceButton = binding.toggleAirspaceButton
            // toggle button state based on shared preferences
            updateButtonState(airspaceButton, isAirspaceVisible)
            toggleAirspace(isAirspaceVisible)

            airspaceButton.setOnClickListener {
                isAirspaceVisible = !isAirspaceVisible
                // toggle saved prefs
                sharedPrefs.edit().putBoolean("show_airspace", isAirspaceVisible).apply()
                toggleAirspace(isAirspaceVisible)
                updateButtonState(airspaceButton, isAirspaceVisible)
            }
            airspaceLoaded = true
        }

        // Sectionals
        // Initialize the tile overlay toggle
        if (!sectionalLoaded) {
            var isSectionalVisible: Boolean
            val vfrSecButton = binding.toggleVfrsecButton
            if (firstLaunch) {
                isSectionalVisible = true
                updateButtonState(vfrSecButton, isSectionalVisible)
                sharedPrefs.edit().putBoolean("show_chart", isSectionalVisible).apply()
                toggleSectionalOverlay(mMap, true)
            } else {
                isSectionalVisible = sharedPrefs.getBoolean("show_chart", true)
                updateButtonState(vfrSecButton, isSectionalVisible)
                toggleSectionalOverlay(mMap, isSectionalVisible)
            }
            vfrSecButton.setOnClickListener {
                isSectionalVisible = !isSectionalVisible
                // toggle in savedprefs
                sharedPrefs.edit().putBoolean("show_chart", isSectionalVisible).apply()
                prefsDump()
                toggleSectionalOverlay(mMap, isSectionalVisible)
                updateButtonState(vfrSecButton, isSectionalVisible)
            }
            sectionalLoaded = true
        }

        // ✅ Handle flight plan waypoints
        val flightPlan = FlightPlanHolder.currentPlan

        if (flightPlan != null) {
            updateMapWithFlightPlan(flightPlan)
        }

//        val waypoints: ArrayList<Waypoint>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            intent.getParcelableArrayListExtra("WAYPOINTS", Waypoint::class.java)
//        } else {
//            @Suppress("DEPRECATION")
//            intent.getParcelableArrayListExtra("WAYPOINTS")
//        }

//        if (!waypoints.isNullOrEmpty()) {
//            updateMapWithWaypoints(waypoints)
//        }

//        val legs = generateLegs(this, waypoints, tas, fuelBurn, cruiseAltitude, windDir, windSpeed)
//        val myFlightPlan = FlightPlan(legs.toMutableList())
//        updateMapWithFlightPlan(myFlightPlan)

        // Draw track line
        updateMapWithTrack()

        // **Load Metars**
        if (!metarLoaded) {
            loadAndDrawMetar()

            // Initialize the metar toggle button
            isMetarVisible = sharedPrefs.getBoolean("show_metars", true)
            val metarButton = binding.toggleMetarButton
            // toggle button state based on shared preferences
            updateButtonState(metarButton, isMetarVisible)

            metarButton.setOnClickListener {
                isMetarVisible = !isMetarVisible
                // toggle in savedprefs
                sharedPrefs.edit().putBoolean("show_metars", isMetarVisible).apply()
                toggleMetarVisibility(isMetarVisible)
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
            metarLoaded = true
        }

        // ** try to refresh weather data every 15 mins
        startAutoRefresh(15) // testing seconds

        //read traffic preference
        isTrafficEnabled = sharedPrefs.getBoolean("show_traffic", true)
        hideDistantTraffic = sharedPrefs.getBoolean("hide_distant_traffic", true)

        if (::mMap.isInitialized) {
            checkLocationPermission()
        }

        Handler(Looper.getMainLooper()).postDelayed({
            hideBottomProgressBar()
        }, 3000)
    }
    // End onMapReady

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
        val prefs = getSharedPreferences("SavedLocation", MODE_PRIVATE)
        val savedLat = prefs.getFloat("lat", Float.MIN_VALUE)
        val savedLng = prefs.getFloat("lng", Float.MIN_VALUE)
        val savedZoom = prefs.getFloat("zoom", 10f) // Default zoom level

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (savedLat != Float.MIN_VALUE && savedLng != Float.MIN_VALUE &&
                (savedLat != 0.0f || savedLng != 0.0f)
            ) {
                // ✅ Move to last saved position
                val lastLatLng = LatLng(savedLat.toDouble(), savedLng.toDouble())
                Log.d(
                    "MapDebug",
                    "Moving to saved position: lat=$savedLat, lng=$savedLng, zoom=$savedZoom"
                )
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(lastLatLng, savedZoom))
            } else {
                // ✅ No saved position → Move to current location
                Log.d(
                    "MapDebug",
                    "No saved position, moving to current location: lat=$savedLat, lng=$savedLng, zoom=$savedZoom"
                )
                moveToCurrentLocation()
            }
        } else {
            // ✅ Request permission if not granted
            checkLocationPermission()
        }
    }

    private fun saveMapPosition() {
        val prefs = getSharedPreferences("SavedLocation", MODE_PRIVATE)
        val editor = prefs.edit()
        val target = mMap.cameraPosition.target
        editor.putFloat("lat", target.latitude.toFloat())
        editor.putFloat("lng", target.longitude.toFloat())
        editor.putFloat("zoom", mMap.cameraPosition.zoom)
        editor.apply()
        // ✅ Debugging: Check if values are saved
        Log.d(
            "MapDebug",
            "Saved Position: lat=${target.latitude}, lng=${target.longitude}, zoom=${mMap.cameraPosition.zoom}"
        )
    }

    private fun saveLayerSelection(layerName: String) {
        val sharedPrefs = getSharedPreferences("MapSettings", MODE_PRIVATE)
        with(sharedPrefs.edit()) {
            putString("selectedLayer", layerName)
            apply()
        }
        Log.d("MapDebug", "Saved layer: $layerName")
    }

    private fun refreshMarkers() {
        metarMarkers.forEach { it.remove() }
        metarMarkers.clear()

        updateVisibleMarkers(metarData, tafData)
    }
    // createDotBitmap, createTextBitmap moved to BitmapHelpers.kt

    private fun startAutoRefresh(intervalMinutes: Long) {
        // ViewModel owns the auto-refresh loop so it survives config changes;
        // the activity re-renders via its flow collector.
        viewModel.startAutoRefresh(intervalMinutes)
    }

    private fun observeViewModel() {
        // Combined METAR + TAF stream — re-render markers whenever either changes.
        // repeatOnLifecycle pauses collection when the activity isn't visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.metars, viewModel.tafs) { m, t -> m to t }
                    .collect { (metars, tafs) ->
                        metarData = metars
                        tafData = tafs
                        if (::mMap.isInitialized) updateVisibleMarkers(metars, tafs)
                    }
            }
        }
    }

    // Update markers based on visible map area
    fun Context.isInternetAvailable(): Boolean {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

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
                    MapLayer.FlightConditions -> markerFactory.createFlightConditionMarker(metar, taf, location)
                    MapLayer.Wind -> markerFactory.createMetarDotForWindLayer(metar, location)
                    MapLayer.Temperature -> markerFactory.createTemperatureMarker(metar, location)
                    MapLayer.Altimeter -> markerFactory.createAltimeterMarker(metar, location)
                    MapLayer.Ceiling -> markerFactory.createCeilingMarker(metar, location)
                    MapLayer.Clouds -> markerFactory.createCloudMarker(metar, location)
                    MapLayer.None -> null
                }

                marker?.let { metarMarkers.add(it) }

                if (currentLayer != MapLayer.FlightConditions && currentLayer != MapLayer.None) {
                    markerFactory.createMetarDotForWindLayer(metar, location)?.let { metarMarkers.add(it) }
                }

                if (currentLayer == MapLayer.Wind) {
                    markerFactory.createWindMarker(metar, taf, location)?.let { metarMarkers.add(it) }
                }
            }
        }
    }

    // Marker creation methods moved to MarkerFactory.kt
    // celsiusToFahrenheit (returning String) used only by formatAirportDetails below.

    @SuppressLint("DefaultLocale")
    private fun celsiusToFahrenheit(celsius: Double): String {
        return "%.0f".format(celsius * 9 / 5 + 32)
    }

    // Format the weather details for the popup snippet
    private fun formatAirportDetails(metars: METAR): String {
        val ageInMinutes = calculateMetarAge(metars.observationTime)
        return """
        ${getCurrentTimeLocalFormat()} (${ageInMinutes} minutes old)
        ${
            if (metars.windSpeedKt == 0 || metars.windSpeedKt == null) {
                "Wind: Calm"
            } else {
                "Wind: ${metars.windDirDegrees ?: "VRB"}° @ ${metars.windSpeedKt} kt" +
                        (if (metars.windGustKt != null && metars.windGustKt > 0) ", Gust ${metars.windGustKt} kt" else "")
            }
        }
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
                calculateDensityAltitude(
                    elevationFeet,
                    metars.tempC,
                    metars.altimeterInHg,
                    humidity.toDouble()
                )
            } else {
                "N/A"
            }
        }
    """.trimIndent()

    }

    private fun showBottomProgressBar(
        message: String,
        color: Int = ContextCompat.getColor(
            this@MainActivity,
            android.R.color.holo_blue_dark
        )
    ) {
        val progressBar = binding.progressBottomBar
        val progressMessage = binding.progressMessageBottom
        if (progressMessage == null) {
            Log.e("ProgressBar", "Progress bar or message view not found!")
            return
        }
        progressMessage.text = message
        progressBar.visibility = View.VISIBLE
        progressBar.setBackgroundColor(color)
    }

    private fun hideBottomProgressBar() {
        val progressBar = binding.progressBottomBar
        val progressOverlay = binding.progressOverlay
        progressBar.visibility = View.GONE
        //progressOverlay.visibility = View.GONE
    }

    private fun updateButtonState(button: Button, isActive: Boolean) {
        // Change button color based on state
        if (isActive) {
            button.setTextColor(Color.WHITE)
        } else {
            button.setTextColor(Color.GRAY)
        }
    }

    // TFR
    private fun loadAndDrawTFR() {
        lifecycleScope.launch {
            try {
                viewModel.refreshTfrs().join()
                drawTFRPolygons(mMap, viewModel.tfrs.value)
            } catch (e: Exception) {
                Log.e("MainActivity", "TFR refresh failed: ${e.message}", e)
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
            (altitude.replace(",", "").toIntOrNull()
                ?: 0) >= 90000 -> "Unlimited" // ✅ Convert 90,000+ to "Unlimited"
            altitude.toIntOrNull() != null -> "%,d'".format(
                altitude.replace(",", "").toInt()
            ) // ✅ Add comma + tick
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
        val startDate = try {
            dateFormat.parse(startDateString)
        } catch (e: Exception) {
            null
        }
        val endDate = try {
            dateFormat.parse(tfr.dateExpire)
        } catch (e: Exception) {
            null
        }

        // ✅ Ensure `altitudeMin` and `altitudeMax` are treated correctly
        val altitudeInfo = "${formatAltitude(tfr.altitudeMin)} - ${formatAltitude(tfr.altitudeMax)}"

        // ✅ Check if the TFR is active
        val status =
            if (endDate == null || (startDate != null && currentDate in startDate..endDate)) {
                "Active"
            } else {
                "Inactive"
            }

        // **Set Custom Colors**
        val activeColor =
            ContextCompat.getColor(context, android.R.color.holo_red_dark)  // Red for Active
        val inactiveColor =
            ContextCompat.getColor(context, android.R.color.holo_green_dark)  // Green for Inactive
        val altitudeColor =
            ContextCompat.getColor(context, android.R.color.holo_blue_dark) // Blue for altitude
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

    private fun toggleTFRVisibility(visible: Boolean) {
        areTFRsVisible = visible
        tfrPolygons.forEach { it.isVisible = areTFRsVisible }
        Log.d("Toggle", "Saved TFR visibility - set to $areTFRsVisible")
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
                        val coordinates =
                            extractPolygonCoordinates(geometry.getJSONArray("coordinates"))
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

    private fun toggleAirspace(visible: Boolean) {
        //areAirspacesVisible = !areAirspacesVisible
        areAirspacesVisible = visible
        airspacePolygons.forEach { it.isVisible = areAirspacesVisible }
        Log.d("Toggle", "Airspace visibility set to $areAirspacesVisible")
    }

    // METAR
    private fun loadAndDrawMetar() {

        lifecycleScope.launch {
            try {
                Log.d("loadAndDrawMetar", "🌦️ Updating Weather Data")
                showBottomProgressBar("🌦️ Downloading Latest Weather Data")

                viewModel.loadCachedWeather().join()
                // markers update reactively via the metars/tafs flow collector
                if (!isInternetAvailable()) {
                    Log.d("loadAndDrawMetar", "❌ No Internet Connection")
                    return@launch
                }

                viewModel.refreshWeather().join()
            } catch (e: Exception) {
                Log.e("METAR_UPDATE", "Error fetching METAR data", e)
                withContext(Dispatchers.Main) {
                    showBottomProgressBar("❌ Weather Data Failed")
                }
            } finally {
                withContext(Dispatchers.Main) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        hideBottomProgressBar()
                    }, 3000)
                }
            }
        }
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

    private fun toggleMetarVisibility(visible: Boolean) {
        areMetarsVisible = visible
        if (::markerFactory.isInitialized) markerFactory.areMetarsVisible = visible
        metarMarkers.forEach { marker ->
            marker.isVisible = areMetarsVisible
        }
        Log.d("Toggle", "METAR visibility set to $areMetarsVisible")
    }

    // createWindBarbBitmap moved to BitmapHelpers.kt

    // TILES (sectional charts)
    private fun toggleSectionalOverlay(map: GoogleMap, visible: Boolean) {
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

        //sectionalVisible = !sectionalVisible // Toggle Sectional visibility
        //terminalVisible = sectionalVisible  // Keep Terminal consistent
        sectionalVisible = visible
        terminalVisible = visible

        sectionalOverlay?.isVisible = sectionalVisible
        terminalOverlay?.isVisible = terminalVisible

        sectionalOverlay?.clearTileCache()
        terminalOverlay?.clearTileCache() // ✅ Ensures smooth transition

        Log.d("Toggle", "Sectional visibility set to $sectionalVisible")

    }

    private fun destinationPoint(start: LatLng, distanceNm: Double, bearingDeg: Double): LatLng {
        val earthRadiusNM = 3440.065
        val bearingRad = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(start.latitude)
        val lon1 = Math.toRadians(start.longitude)
        val dOverR = distanceNm / earthRadiusNM

        val lat2 = asin(
            sin(lat1) * cos(dOverR) +
                    cos(lat1) * sin(dOverR) * cos(bearingRad)
        )
        val lon2 = lon1 + atan2(
            sin(bearingRad) * sin(dOverR) * cos(lat1),
            cos(dOverR) - sin(lat1) * sin(lat2)
        )

        return LatLng(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    // GPS source
    private fun handleNewLocation(location: Location) {
        lastKnownUserLocation = location
        val userLatLng = LatLng(location.latitude, location.longitude)

        // ✅ Re-center if following
        if (isFollowingUser) {
            mMap.animateCamera(
                CameraUpdateFactory.newLatLngZoom(userLatLng, mMap.cameraPosition.zoom)
            )
        }

        // ✅ Start Stratux traffic / GPS (non-blocking for now)
        checkStratuxAndConnectIfEnabled(location)

        // ✅ Auto-recording logic (unchanged)
        val currentTime = location.time
        val currentAltitude = location.altitude
        val speedKnots = location.speed * 1.94384

        var verticalSpeedFpm = 0.0
        if (lastAltitude != null && lastTime != null) {
            val timeDelta = (currentTime - lastTime!!).coerceAtLeast(1)
            val verticalSpeedMps = (currentAltitude - lastAltitude!!) / (timeDelta / 1000.0)
            verticalSpeedFpm = verticalSpeedMps * 196.8504
        }

        lastAltitude = currentAltitude
        lastTime = currentTime

        // FIXME
        // if recorder is ON, write our GPS position
        if (recorder.isRecording) {
            recorder.logLocation(location)
        }

        // ✈️ Auto start recording on takeoff
//        if (!recorder.isRecording && speedKnots > 15 && verticalSpeedFpm > 100) {
        if (!recorder.isRecording && speedKnots > 10 ) {
            recorder.start()
            Log.d("KMLRecorder_main", "Takeoff detected — recording started")
        }

        // ✈️ Auto stop recording on landing
        if (recorder.isRecording && speedKnots < 15 && verticalSpeedFpm < 100) {
            if (stopStartTime == null) {
                stopStartTime = System.currentTimeMillis()
            }

            val stoppedDuration = System.currentTimeMillis() - stopStartTime!!
            if (stoppedDuration >= 10_000) {  // 30 seconds
                recorder.stop()
                Log.d("KMLRecorder_main", "Stopped for 10s — recording stopped")
                stopStartTime = null
            }
        } else {
            // Moving again, reset stop timer
            stopStartTime = null
        }

        // ✅ Always reflect current state
        val newColor = if (recorder.isRecording) Color.RED else Color.BLACK
        val recordButton = binding.recordButton
        recordButton.setColorFilter(newColor)

        // ✅ Flight plan leg advancement
        val flightPlan = FlightPlanHolder.currentPlan
        if (flightPlan != null) {
            val advanced = flightPlan.advanceLegIfPast(userLatLng)
            if (advanced) {
                Log.i("FlightPlan", "Leg advanced near $userLatLng")
                updateMapWithFlightPlan(flightPlan)
            }

            val activeLeg = flightPlan.legs.firstOrNull { it.active && !it.completed }
            if (activeLeg != null) {
//                val flightData = calculateFlightData(location, listOf(activeLeg.from, activeLeg.to))
                val flightData = calculateFlightData(location, flightPlan)
                updateFlightInfo(flightData)
            }
        }
    }

//    private fun handleNewLocation(location: Location) {
//        lastKnownUserLocation = location
//
//        val userLatLng = LatLng(location.latitude, location.longitude)
//
//        // Re-center if following
//        if (isFollowingUser) {
//            mMap.animateCamera(
//                CameraUpdateFactory.newLatLngZoom(
//                    userLatLng,
//                    mMap.cameraPosition.zoom
//                )
//            )
//        }
//
//        // Start Stratux traffic / GPS
//        checkStratuxAndConnectIfEnabled(location)
//
//        // Auto-recording logic
//        val currentTime = location.time
//        val currentAltitude = location.altitude
//        val speedKnots = location.speed * 1.94384
//
//        var verticalSpeedFpm = 0.0
//        if (lastAltitude != null && lastTime != null) {
//            val timeDelta = (currentTime - lastTime!!).coerceAtLeast(1)
//            val verticalSpeedMps = (currentAltitude - lastAltitude!!) / (timeDelta / 1000.0)
//            verticalSpeedFpm = verticalSpeedMps * 196.8504
//        }
//
//        lastAltitude = currentAltitude
//        lastTime = currentTime
//
//        if (!recorder.isRecording && speedKnots > 15 && verticalSpeedFpm > 100) {
//            //recorder.start()
//            binding.recordButton.setColorFilter(Color.RED)
//            Log.d("KMLRecorder_main", "Takeoff detected")
//        }
//
//        if (recorder.isRecording) {
//            //recorder.logLocation(location)
//        }
//
//        if (recorder.isRecording && speedKnots < 15 && verticalSpeedFpm < 100) {
//            //recorder.stop()
//            binding.recordButton.setColorFilter(Color.BLACK)
//            Log.d("KMLRecorder_main", "Landing detected")
//        }
//
//        // Flight Plan waypoints
//        val waypoints: ArrayList<Waypoint>? =
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                intent.getParcelableArrayListExtra("WAYPOINTS", Waypoint::class.java)
//            } else {
//                @Suppress("DEPRECATION")
//                intent.getParcelableArrayListExtra("WAYPOINTS")
//            }
//
//        if (waypoints != null) {
//            if (waypoints.isNotEmpty()) {
//                val flightData = calculateFlightData(location, waypoints)
//                updateFlightInfo(flightData)
//            }
//        }
//    }

    //Traffic markers (stratux)
    private fun checkStratuxAndConnectIfEnabled(loc: Location) {
        lifecycleScope.launch {
            val reachable = withContext(Dispatchers.IO) {
                try {
                    InetAddress.getByName("192.168.10.1").isReachable(1000)
                } catch (e: Exception) {
                    //Log.e("Stratux", "Ping failed", e)
                    false
                }
            }
            if (reachable) {
                Log.d("Stratux", "checkStratuxAndConnectIfEnabled - reachable = $reachable")
                binding.stratuxButton.setColorFilter(Color.GRAY)

                // enable GPS
//                StratuxManager.connectToGps { gps ->
//                    runOnUiThread {
//                        isStratuxGpsActive = true
//                        binding.gpsStatusIcon.setColorFilter(Color.GREEN)
//
//                        // Replace internal GPS
//                        val fakeLocation = Location("Stratux").apply {
//                            latitude = gps.latitude
//                            longitude = gps.longitude
//                            altitude = gps.altitudeFt / 3.28084
//                            if (gps.heading in 0.0..360.0) {
//                                bearing = gps.heading.toFloat()
//                            }
//                            speed = gps.speedKnots.toFloat() * 0.514444f  // knots to m/s
//                            time = System.currentTimeMillis()
//                        }
//                        //lastKnownUserLocation = fakeLocation
//                        handleNewLocation(fakeLocation)
//                    }
//                }

                isTrafficEnabled = sharedPrefs.getBoolean("show_traffic", true)
                if (isTrafficEnabled) {
//                    Log.d(
//                        "Stratux",
//                        "checkStratuxAndConnectIfEnabled - isTrafficEnabled = $isTrafficEnabled"
//                    )
                    binding.stratuxButton.setColorFilter(Color.GREEN)

                    if (!stratuxStarted) {
//                        Log.d(
//                            "Stratux",
//                            "checkStratuxAndConnectIfEnabled - stratuxStarted = $stratuxStarted, starting it"
//                        )
                        lifecycleScope.launch {
                            stratuxStarted = true
                            StratuxManager.connectToTraffic { target ->
                                if (!isTrafficEnabled) return@connectToTraffic

                                runOnUiThread {
                                    if (isTrafficEnabled) {
                                        trafficMap[target.hex] = target
                                        updateAircraftMarker(target, loc)
                                    }
                                }
                            }
                        }
                    } else {
                        Log.d(
                            "Stratux",
                            "checkStratuxAndConnectIfEnabled - stratuxStarted = $stratuxStarted, already started"
                        )
                    }
                } else {
                    Log.d(
                        "Stratux",
                        "checkStratuxAndConnectIfEnabled - isTrafficEnabled = $isTrafficEnabled, shutting down stratux"
                    )
                    stratuxStarted = false
                    //binding.stratuxButton.setColorFilter(Color.GRAY)
                    StratuxManager.disconnectTraffic()
                    clearAllTraffic()
                }
            } else {
                Log.w(
                    "Stratux",
                    "❌ Stratux not reachable - skipping connect and/or disconnecting"
                )
                stratuxStarted = false
                StratuxManager.disconnectTraffic()
                clearAllTraffic()
                binding.gpsStatusIcon.setColorFilter(Color.RED)
                // update button, RED if traffic is enabled or GRAY if traffic is disabled
                isTrafficEnabled = sharedPrefs.getBoolean("show_traffic", false)
                if (isTrafficEnabled) {
                    binding.stratuxButton.setColorFilter(Color.RED)
                } else {
                    binding.stratuxButton.setColorFilter(Color.BLACK)
                }
            }
        }
    }

    private fun clearAllTraffic() {
        Log.d("Stratux", "clearAllTraffic")
        runOnUiThread {
            aircraftMarkers.values.forEach { it.remove() }
            aircraftLabels.values.forEach { it.remove() }
            aircraftLabelsBottom.values.forEach { it.remove() }
            aircraftMarkers.clear()
            aircraftLabels.clear()
            aircraftLabelsBottom.clear()
            trafficMap.clear()
        }
    }

    private fun extrapolatePosition(target: TrafficTarget, seconds: Double): LatLng {
        val earthRadiusNM = 3440.065
        val distanceNm = (target.speedKts / 3600.0) * seconds
        val bearingRad = Math.toRadians(target.course.toDouble())
        val latRad = Math.toRadians(target.lat)
        val lonRad = Math.toRadians(target.lon)

        val newLat = asin(
            sin(latRad) * cos(distanceNm / earthRadiusNM) +
                    cos(latRad) * sin(distanceNm / earthRadiusNM) * cos(bearingRad)
        )

        val newLon = lonRad + atan2(
            sin(bearingRad) * sin(distanceNm / earthRadiusNM) * cos(latRad),
            cos(distanceNm / earthRadiusNM) - sin(latRad) * sin(newLat)
        )

        return LatLng(Math.toDegrees(newLat), Math.toDegrees(newLon))
    }
    private fun estimateTimeToConflict(
        ownLocation: Location,
        trafficLocation: Location,
        ownSpeedMps: Float,
        trafficSpeedKts: Int
    ): Int? {
        val distanceMeters = ownLocation.distanceTo(trafficLocation)
        val trafficSpeedMps = trafficSpeedKts * 0.514444
        val closingSpeed = (ownSpeedMps + trafficSpeedMps)

        if (closingSpeed <= 0) return null
        return (distanceMeters / closingSpeed).toInt()
    }

    private fun TrafficTarget.toLocation(): Location {
        return Location("traffic").apply {
            latitude = this@toLocation.lat
            longitude = this@toLocation.lon
        }
    }

    private fun updateAircraftMarker(target: TrafficTarget, location: Location) {
        val position = LatLng(target.lat, target.lon)
        val myAltitudeFt = (location.altitude * 3.28084)
        val altDiffFt = (target.altitudeFt - myAltitudeFt)
        val altDiffHundreds = (altDiffFt / 100).toInt()
        val timeToConflict = estimateTimeToConflict(location, target.toLocation(), location.speed, target.speedKts)
        val now = System.currentTimeMillis()
        val distanceMeters = location.distanceTo(target.toLocation())
        val distanceNauticalMiles = distanceMeters / 1852.0

        // hide distant traffic if selected
        if (hideDistantTraffic) {
            val horizontalDistanceNm = target.distanceNm
            val verticalDistanceFt = abs(target.altitudeFt - (location.altitude * 3.28084))

            if (horizontalDistanceNm > 15 || verticalDistanceFt > 3500) {
                aircraftMarkers[target.hex]?.isVisible = false
                aircraftLabels[target.hex]?.isVisible = false
                aircraftLabelsBottom[target.hex]?.isVisible = false
                return // Skip further updates for distant traffic
            }
        }

        // Determine threat level
        val isRed = abs(altDiffFt) <= 1200 && (
                distanceNauticalMiles <= 1.3 || (timeToConflict != null && timeToConflict <= 25)
                )

        val isYellow = abs(altDiffFt) <= 1200 && (
                distanceNauticalMiles <= 2.0 || (timeToConflict != null && timeToConflict <= 45)
                )

        val altColor = when {
            isRed -> {
                recentRedTargets[target.hex] = now
                Color.RED
            }
            recentRedTargets[target.hex]?.let { now - it < 15_000 } == true -> {
                Color.YELLOW
            }
            isYellow -> Color.YELLOW
            else -> Color.CYAN
        }

        val altString = when {
            altDiffHundreds > 0 -> "+$altDiffHundreds"
            altDiffHundreds < 0 -> "-$altDiffHundreds"
            else -> "same"
        }

        val arrow = when {
            target.verticalSpeed > 100 -> " ↑"
            target.verticalSpeed < -100 -> " ↓"
            else -> ""
        }

        val labelTextTop = "$altString$arrow"
        val labelBitmapTop = createTextBitmap(labelTextTop, altColor, Color.argb(180, 0, 0, 0), 30F)

        val labelTextBottom = "${target.tail}  ${target.speedKts}kt"
        val labelBitmapBottom = createTextBitmap(labelTextBottom, altColor, Color.argb(180, 0, 0, 0), 26F)

//        aircraftMarkers[target.hex]?.position = position
//        aircraftLabels[target.hex]?.setIcon(BitmapDescriptorFactory.fromBitmap(labelBitmapTop))
//        aircraftLabelsBottom[target.hex]?.setIcon(BitmapDescriptorFactory.fromBitmap(labelBitmapBottom))

        val marker = aircraftMarkers[target.hex]
        val labelMarker = aircraftLabels[target.hex]
        val labelBottomMarker = aircraftLabelsBottom[target.hex]

        if (marker != null && labelMarker != null && labelBottomMarker != null) {
            marker.position = position
            marker.rotation = target.course.toFloat()

            labelMarker.position = position
            labelMarker.setIcon(BitmapDescriptorFactory.fromBitmap(labelBitmapTop))

            labelBottomMarker.position = position
            labelBottomMarker.setIcon(BitmapDescriptorFactory.fromBitmap(labelBitmapBottom))
        } else {
            val newMarker = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .anchor(0.5f, 0.5f)
                    .rotation(target.course.toFloat())
                    .icon(vectorToBitmap(this, R.drawable.arrow2))
            )

            val newLabelTop = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .anchor(0.5f, 1.5f)
                    .icon(BitmapDescriptorFactory.fromBitmap(labelBitmapTop))
            )

            val newLabelBottom = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .anchor(0.5f, -0.8f)
                    .icon(BitmapDescriptorFactory.fromBitmap(labelBitmapBottom))
            )

            if (newMarker != null && newLabelTop != null && newLabelBottom != null) {
                aircraftMarkers[target.hex] = newMarker
                aircraftLabels[target.hex] = newLabelTop
                aircraftLabelsBottom[target.hex] = newLabelBottom
            }
        }

    }

/*    private fun updateAircraftMarker(target: TrafficTarget, location: Location) {

        val position = LatLng(target.lat, target.lon)
        val myAltitudeFt = (location.altitude * 3.28084)
        val altDiff = ((target.altitudeFt - myAltitudeFt) / 100).toInt()

        val altString = if (altDiff > 0) "+$altDiff" else if (altDiff < 0) "-$altDiff" else "same"
        val altColor = if (altDiff in -5..5) Color.RED else if (altDiff in -10..10) Color.YELLOW else Color.CYAN
        val arrow = when {
            target.verticalSpeed > 100 -> " ↑"
            target.verticalSpeed < -100 -> " ↓"
            else -> ""
        }

        val labelTextTop = "$altString$arrow"
        val labelBitmapTop = createTextBitmap(labelTextTop, altColor, Color.argb(180, 0, 0, 0), 30F)

        val labelTextBottom = "${target.tail}  ${target.speedKts}kt"
        val labelBitmapBottom =
            createTextBitmap(labelTextBottom, altColor, Color.argb(180, 0, 0, 0), 26F)

        val marker = aircraftMarkers[target.hex]
        val labelMarker = aircraftLabels[target.hex]
        val labelBottomMarker = aircraftLabelsBottom[target.hex]

        if (marker != null && labelMarker != null && labelBottomMarker != null) {
            marker.position = position
            marker.rotation = target.course.toFloat()

            labelMarker.position = position
            labelMarker.setIcon(BitmapDescriptorFactory.fromBitmap(labelBitmapTop))

            labelBottomMarker.position = position
            labelBottomMarker.setIcon(BitmapDescriptorFactory.fromBitmap(labelBitmapBottom))
        } else {
            val newMarker = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .anchor(0.5f, 0.5f)
                    .rotation(target.course.toFloat())
                    .icon(vectorToBitmap(this, R.drawable.arrow2))
            )

            val newLabelTop = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .anchor(0.5f, 1.5f)
                    .icon(BitmapDescriptorFactory.fromBitmap(labelBitmapTop))
            )

            val newLabelBottom = mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .anchor(0.5f, -0.8f)
                    .icon(BitmapDescriptorFactory.fromBitmap(labelBitmapBottom))
            )

            if (newMarker != null && newLabelTop != null && newLabelBottom != null) {
                aircraftMarkers[target.hex] = newMarker
                aircraftLabels[target.hex] = newLabelTop
                aircraftLabelsBottom[target.hex] = newLabelBottom
            }
        }
    }*/

    private fun pruneStaleAircraft() {
        val cutoff = System.currentTimeMillis() - 1000 * staleTimeout
        val staleTargets = trafficMap.filterValues { it.lastUpdated < cutoff }
        Log.d(
            "TrafficPrune",
            "Total aircraft: ${aircraftMarkers.size}, Stale aircraft: ${staleTargets.size}"
        )

        if (!DEBUG_LOGGING_ENABLED) {
            Log.d("TrafficPrune", "Total aircraft: ${aircraftMarkers.size}")
            Log.d("TrafficPrune", "Stale aircraft: ${staleTargets.size}")
            Log.d(
                "TrafficPrune",
                "Markers: ${aircraftMarkers.size}, Labels: ${aircraftLabels.size}, Bottom Labels: ${aircraftLabelsBottom.size}"
            )


            staleTargets.forEach { (hex, target) ->
                Log.d(
                    "TrafficPrune",
                    "🗑 Stale: $hex (${target.tail}) last seen ${System.currentTimeMillis() - target.lastUpdated}ms ago"
                )
            }

            trafficMap.forEach { (hex, target) ->
                Log.d(
                    "TrafficPrune",
                    "🛩 Active: $hex (${target.tail}) last seen ${System.currentTimeMillis() - target.lastUpdated}ms ago"
                )
            }
        }

        for ((hex, _) in staleTargets) {
            aircraftMarkers[hex]?.remove()
            aircraftLabels[hex]?.remove()
            aircraftLabelsBottom[hex]?.remove()

            aircraftMarkers.remove(hex)
            aircraftLabels.remove(hex)
            aircraftLabelsBottom.remove(hex)
            trafficMap.remove(hex)
        }
    }

    private fun vectorToBitmap(context: Context, vectorResId: Int): BitmapDescriptor {
        val vectorDrawable = ContextCompat.getDrawable(context, vectorResId)!!
        val bitmap = Bitmap.createBitmap(
            vectorDrawable.intrinsicWidth,
            vectorDrawable.intrinsicHeight,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        vectorDrawable.setBounds(0, 0, canvas.width, canvas.height)
        vectorDrawable.draw(canvas)
        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }

    //Track Line
    private fun updateMapWithTrack() {
        val path = recorder.getLatLngPath()
        trackLine?.remove()
        trackLine = mMap.addPolyline(
            PolylineOptions()
                .addAll(path)
                .width(5f)
                .color(Color.RED)
        )
    }

    // draw track lines
    private fun loadTrackFromKml(file: File) {
        val path = mutableListOf<LatLng>()
        try {
            file.forEachLine { line ->
                Log.d("KMLLine", line)

                if (line.contains(",") && line.trim().split(",").size == 3) {
                    val parts = line.trim().split(",")
                    val lon = parts[0].toDoubleOrNull()
                    val lat = parts[1].toDoubleOrNull()
                    if (lat != null && lon != null) {
                        path.add(LatLng(lat, lon))
                    }
                }
            }
            if (path.isNotEmpty()) {
                mMap.addPolyline(
                    PolylineOptions()
                        .addAll(path)
                        .width(10f)
                        .color(Color.GREEN)
                )
                mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(path.first(), 10f))
            } else {
                Toast.makeText(this, "No track data found.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to load track.", Toast.LENGTH_SHORT).show()
        }
    }

    //Flight plan markers, waypoints, LEGS
    private fun updateMapWithFlightPlan(flightPlan: FlightPlan) {
        val latLngList = mutableListOf<LatLng>()
        val waypointNames = mutableListOf<String>()
        val dbHelper = AirportDatabaseHelper(this)

        // If there are no legs but only one waypoint, treat it as a direct-to flight
//        if (ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.ACCESS_FINE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
//                this,
//                Manifest.permission.ACCESS_COARSE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            return
//        }

        for (leg in flightPlan.legs) {
            val fromLatLng =  LatLng(leg.from.lat, leg.from.lon)
            val toLatLng = LatLng(leg.to.lat, leg.to.lon)

            // Draw each leg individually
            val legColor = when {
                leg.completed -> Color.LTGRAY
                leg.active -> Color.MAGENTA
                else -> Color.CYAN
            }

            val legLine = PolylineOptions()
                .add(fromLatLng, toLatLng)
                .color(legColor)
                .width(10f)
                .zIndex(2f)

            val legBorder = PolylineOptions()
                .add(fromLatLng, toLatLng)
                .color(Color.BLACK)
                .width(12f)
                .zIndex(1f)

            mMap.addPolyline(legBorder)
            mMap.addPolyline(legLine)

            // Draw waypoint dots
            for (wp in listOf(leg.from, leg.to)) {
                val style = MarkerStyle(
                    size = 40,
                    fillColor = if (wp.type == "NAVAID") Color.CYAN else if (wp.type == "FIX") Color.YELLOW else Color.LTGRAY,
                    borderColor = Color.WHITE,
                    borderWidth = 6
                )
                mMap.addMarker(
                    MarkerOptions()
                        .position(LatLng(wp.lat, wp.lon))
                        .icon(markerFactory.createDotDescriptor(style))
                        .anchor(0.5f, 0.5f)
                        .zIndex(2f)
                        .visible(true)
                )
            }

            if (latLngList.isEmpty()) {
                latLngList.add(fromLatLng)
                waypointNames.add(leg.from.name)
            }
            latLngList.add(toLatLng)
            waypointNames.add(leg.to.name)
        }

        // Draw waypoint labels
        for (i in latLngList.indices) {
            val position = latLngList[i]
            val label = waypointNames.getOrNull(i) ?: "WP$i"
            val labelBitmap = createTextBitmap(label, Color.WHITE, Color.argb(180, 0, 0, 0))
            mMap.addMarker(
                MarkerOptions()
                    .position(position)
                    .icon(BitmapDescriptorFactory.fromBitmap(labelBitmap))
                    .anchor(-0.1f, 0.5f)
                    .zIndex(3f)
            )
        }

        // Draw runways at destination
        val destination = flightPlan.legs.lastOrNull()?.to?.name ?: return
        val runways = dbHelper.getRunwaysForAirport(destination)
        val extensionLengthNm = 5.0
        for (rwy in runways) {
            val end1LatLng = LatLng(rwy.end1.lat, rwy.end1.lon)
            val end2LatLng = LatLng(rwy.end2.lat, rwy.end2.lon)
            val ext2 = destinationPoint(end1LatLng, extensionLengthNm, rwy.end1.heading)
            val ext1 = destinationPoint(end2LatLng, extensionLengthNm, rwy.end2.heading)

            // Runway body
            mMap.addPolyline(
                PolylineOptions().add(end1LatLng, end2LatLng).color(Color.WHITE).width(20f)
                    .zIndex(3f)
            )
            mMap.addPolyline(
                PolylineOptions().add(end1LatLng, end2LatLng).color(Color.BLACK).width(10f)
                    .zIndex(3.1f)
            )
            mMap.addPolyline(
                PolylineOptions().add(end1LatLng, end2LatLng).color(Color.WHITE).width(5f)
                    .zIndex(3.2f)
            )

            // Extended centerlines
            mMap.addPolyline(
                PolylineOptions().add(end1LatLng, ext1).color(Color.WHITE).width(20f).zIndex(3f)
            )
            mMap.addPolyline(
                PolylineOptions().add(end1LatLng, ext1).color(Color.BLACK).width(10f)
                    .zIndex(3.1f)
            )
            mMap.addPolyline(
                PolylineOptions().add(end2LatLng, ext2).color(Color.WHITE).width(20f).zIndex(3f)
            )
            mMap.addPolyline(
                PolylineOptions().add(end2LatLng, ext2).color(Color.BLACK).width(10f)
                    .zIndex(3.1f)
            )

            // Runway end labels
            val offsetNm1 = when (rwy.end1.endId.lastOrNull()) {
                'L' -> 0.5; 'R' -> -0.5; 'C' -> 0.0; else -> 0.15
            }
            val offsetNm2 = when (rwy.end2.endId.lastOrNull()) {
                'L' -> 0.5; 'R' -> -0.5; 'C' -> 0.0; else -> 0.15
            }
            val labelPos1 =
                destinationPoint(ext1, offsetNm1, (rwy.end1.heading + 180 + 90) % 360)
            val labelPos2 =
                destinationPoint(ext2, offsetNm2, (rwy.end2.heading + 180 + 90) % 360)

            val labelBitmap1 = if (rwy.end1.rhtp == "Y") {
                createTextBitmap("${rwy.end1.endId} ⤵", Color.WHITE, Color.RED)
            } else {
                createTextBitmap(rwy.end1.endId, Color.WHITE, Color.BLACK)
            }
            val labelBitmap2 = if (rwy.end2.rhtp == "Y") {
                createTextBitmap("${rwy.end2.endId} ⤵", Color.WHITE, Color.RED)
            } else {
                createTextBitmap(rwy.end2.endId, Color.WHITE, Color.BLACK)
            }

            mMap.addMarker(
                MarkerOptions()
                    .position(labelPos1)
                    .icon(BitmapDescriptorFactory.fromBitmap(labelBitmap1))
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .zIndex(6f)
            )
            mMap.addMarker(
                MarkerOptions()
                    .position(labelPos2)
                    .icon(BitmapDescriptorFactory.fromBitmap(labelBitmap2))
                    .anchor(0.5f, 0.5f)
                    .flat(true)
                    .zIndex(6f)
            )
        }

        // ✅ Adjust camera
        if (latLngList.isNotEmpty()) {
            val boundsBuilder = LatLngBounds.builder()
            latLngList.forEach { boundsBuilder.include(it) }
            val bounds = boundsBuilder.build()
            val padding = 100
            mMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding))
        }

        // ✅ Show flight info
        val hasMultipleWaypoints = flightPlan.legs.size > 1
        binding.flightInfoLayout.visibility = View.VISIBLE
        binding.flightInfoLayout2.visibility =
            if (hasMultipleWaypoints) View.VISIBLE else View.GONE
        binding.flightInfoLayout.bringToFront()
        binding.flightInfoLayout.requestLayout()
    }
}

// Tile providers moved to: TileProviders.kt