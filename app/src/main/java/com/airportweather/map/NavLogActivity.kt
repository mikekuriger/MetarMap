package com.airportweather.map

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.Log
import android.widget.TextView
import com.google.android.gms.maps.model.LatLng
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

class NavLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NavLogLegAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav_log)

        recyclerView = findViewById(R.id.navLogRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val sharedPreferences = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)
        val currentFlightPlan = sharedPreferences.getString("WAYPOINTS", "")
        val waypoints = currentFlightPlan
            ?.trim()
            ?.split("\\s+".toRegex())  // 🔥 split on any whitespace (space, tab, newline)
            ?.map { it.uppercase() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        Log.d("NavLogActivity", "Found waypoints: ${waypoints}")

        // placeholder values
        val tas = 95
        val fuelBurn = 6.0
        val cruiseAltitude = 9500
        val windDir = 0
        val windSpeed = 0

//        val sampleLegs = listOf(
//            NavLogLeg("KSQL", "KOAK", 24.0, 123, 7.0, 116, 5500, 110, 102, "0:14", 2.1, 0, 0, 0),
//            NavLogLeg("KOAK", "KMRY", 73.0, 152, 13.0, 165, 7500, 115, 108, "0:41", 4.3, 0, 0, 0)
//        )
        val legs = generateLegs(context=this, waypoints, tas, fuelBurn, cruiseAltitude, windDir, windSpeed)
        Log.d("NavLogActivity", "legs: ${legs}")

        val totalDistance = legs.sumOf { it.distanceNM }
        val totalFuel = legs.sumOf { it.fuelUsed }
        val totalMinutes = legs.sumOf {
            val parts = it.ete.split(":")
            if (parts.size == 2) parts[0].toInt() * 60 + parts[1].toInt()
            else parts[0].toInt()
        }
        val totalETE = String.format("%d:%02d", totalMinutes / 60, totalMinutes % 60)

        findViewById<TextView>(R.id.total_distance).text = "%.1f".format(totalDistance)
        findViewById<TextView>(R.id.total_fuel).text = "%.1f".format(totalFuel)
        findViewById<TextView>(R.id.total_ete).text = totalETE

        adapter = NavLogLegAdapter(legs)
        recyclerView.adapter = adapter
    }

    fun generateLegs(
        context: Context,
        waypoints: List<String>,
        tas: Int,
        fuelBurn: Double,
        cruiseAltitude: Int,
        windDir: Int = 0,
        windSpeed: Int = 0
    ): List<NavLogLeg> {
        val dbHelper = AirportDatabaseHelper(context)
        val legs = mutableListOf<NavLogLeg>()

        Log.d("NavLogActivity", "Waypoints count: ${waypoints.size}")

        for (i in 0 until waypoints.size - 1) {
            val wpFrom = waypoints[i]
            val wpTo = waypoints[i + 1]
            Log.d("NavLogActivity", "leg $i: $wpFrom -> $wpTo")

            val airportInfoFrom = dbHelper.getAirportInfo(wpFrom)
            val airportInfoTo = dbHelper.getAirportInfo(wpTo)

            if (airportInfoFrom == null || airportInfoTo == null) {
                Log.i("NavLogActivity", "Missing airport data: $wpFrom or $wpTo")
                continue
            }

            val fromLatLng = LatLng(airportInfoFrom.lat, airportInfoFrom.lon)
            val toLatLng = LatLng(airportInfoTo.lat, airportInfoTo.lon)
            val magVar = airportInfoFrom.magVar
            val trueCourse = calculateTrueCourse(fromLatLng, toLatLng)
            val distanceNM = calculateDistance(fromLatLng, toLatLng).round1()
            val wca = estimateWCA(trueCourse, windDir, windSpeed, tas)
            val magneticCourse = ((trueCourse + wca + magVar).roundToInt() + 360 ) % 360
            val groundspeed = estimateGroundSpeed(tas, windDir, windSpeed, trueCourse).roundToInt()
            val eteMinutes = if (groundspeed > 0) (distanceNM / groundspeed) * 60 else 0.0
            val fuelUsed = ((eteMinutes / 60) * fuelBurn).round1()
            val ete = formatETA(eteMinutes)

            Log.i("NavLogActivity", "fromLatLng: $fromLatLng")
            Log.i("NavLogActivity", "toLatLng: $toLatLng")
            Log.i("NavLogActivity", "magVar: $magVar")
            Log.i("NavLogActivity", "trueCourse: $trueCourse")
            Log.i("NavLogActivity", "magneticCourse: $magneticCourse")
            Log.i("NavLogActivity", "distanceNM: $distanceNM")
            Log.i("NavLogActivity", "wca: $wca")
            Log.i("NavLogActivity", "groundspeed: $groundspeed")
            Log.i("NavLogActivity", "eteMinutes: $eteMinutes")
            Log.i("NavLogActivity", "fuelUsed: $fuelUsed")
            Log.i("NavLogActivity", "ete: $ete")
            Log.i("NavLogActivity", "airportInfoFrom: $airportInfoFrom")
            Log.i("NavLogActivity", "airportInfoTo: $airportInfoTo")

            legs.add(
                NavLogLeg(
                    from = airportInfoFrom.icaoId.ifEmpty { airportInfoFrom.airportId },
                    to = airportInfoTo.icaoId.ifEmpty { airportInfoTo.airportId },
                    distanceNM = distanceNM,
                    trueCourse = trueCourse.roundToInt(),
                    variation = magVar,
                    magneticCourse = magneticCourse,
                    cruisingAltitude = cruiseAltitude,
                    tas = tas,
                    groundspeed = groundspeed,
                    ete = ete,
                    fuelUsed = fuelUsed,
                    windDirection = windDir,
                    windSpeed = windSpeed,
                    temp = 0
                )
            )
        }
        Log.d("NavLogActivity", "Generated ${legs.size} legs")
        legs.forEach { Log.d("NavLogActivity", it.toString()) }
        return legs
    }

    fun Double.round1(): Double = String.format("%.1f", this).toDouble()
    fun estimateWCA(tc: Double, windDir: Int, windSpd: Int, tas: Int): Double {
        val angle = Math.toRadians((windDir - tc + 360) % 360)
        return Math.toDegrees(asin((windSpd * sin(angle)) / tas))
    }
    fun estimateGroundSpeed(tas: Int, windDir: Int, windSpd: Int, tc: Double): Double {
        val angle = Math.toRadians((windDir - tc + 360) % 360)
        return tas + windSpd * cos(angle) // approximation
    }
    fun calculateTrueCourse(fromLocation: LatLng, toLocation: LatLng): Double {
        val lat1 = Math.toRadians(fromLocation.latitude)
        val lon1 = Math.toRadians(fromLocation.longitude)
        val lat2 = Math.toRadians(toLocation.latitude)
        val lon2 = Math.toRadians(toLocation.longitude)
        val deltaLon = lon2 - lon1
        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)
        var bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }
    fun calculateDistance(fromLocation: LatLng, toLocation: LatLng): Double {
        val R = 3440.065  // Earth radius in nautical miles
        val lat1 = Math.toRadians(fromLocation.latitude)
        val lon1 = Math.toRadians(fromLocation.longitude)
        val lat2 = Math.toRadians(toLocation.latitude)
        val lon2 = Math.toRadians(toLocation.longitude)
        val dLat = lat2 - lat1
        val dLon = lon2 - lon1
        val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c  // ✅ Distance in nautical miles (NM)
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
}