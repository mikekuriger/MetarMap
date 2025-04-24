package com.airportweather.map

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.WindowManager
import android.widget.Toast
import com.airportweather.map.databinding.ActivityNavLogBinding
import com.airportweather.map.utils.FlightPlanHolder

class NavLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NavLogLegAdapter
    private lateinit var binding: ActivityNavLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNavLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val flightPlan = FlightPlanHolder.currentPlan
        if (flightPlan == null) {
            Toast.makeText(this, "No flight plan loaded", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val legs = flightPlan.legs
        recyclerView = binding.navLogRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = NavLogLegAdapter(legs)
        recyclerView.adapter = adapter

        val totalDistance = legs.sumOf { it.distanceNM }
        val totalFuel = legs.sumOf { it.fuelUsed }
        val totalMinutes = legs.sumOf {
            val parts = it.ete.split(":")
            if (parts.size == 2) parts[0].toInt() * 60 + parts[1].toInt()
            else parts[0].toIntOrNull() ?: 0
        }
        val totalETE = String.format("%d:%02d", totalMinutes / 60, totalMinutes % 60)

        binding.navlogTotalsRow.totalDistance.text = "%.1f".format(totalDistance)
        binding.navlogTotalsRow.totalFuel.text = "%.1f".format(totalFuel)
        binding.navlogTotalsRow.totalEte.text = totalETE
    }
}