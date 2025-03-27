package com.airportweather.map

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class NavLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NavLogLegAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nav_log)

        recyclerView = findViewById(R.id.navLogRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // TODO: Replace with actual nav log data
//        val from: String,
//        val to: String,
//        val distanceNM: Double,
//        val trueCourse: Int,
//        val variation: Int,
//        val magneticCourse: Int,
//        val cruisingAltitude: Int,
//        val tas: Int,
//        val groundspeed: Int,
//        val ete: String,
//        val fuelUsed: Double,
//        val windDirection: Int,
//        val windSpeed: Int
//        val wca: Int,
//        val temp: Int
        val sampleLegs = listOf(
            NavLogLeg("KSQL", "KOAK", 24.0, 123, 7, 116, 5500, 110, 102, "0:14", 2.1, 0, 0, 0, 0),
            NavLogLeg("KOAK", "KMRY", 73.0, 152, 13, 165, 7500, 115, 108, "0:41", 4.3, 0, 0, 0, 0)
        )

        adapter = NavLogLegAdapter(sampleLegs)
        recyclerView.adapter = adapter
    }
}
