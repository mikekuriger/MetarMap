package com.airportweather.map

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.WindowManager
import android.widget.Toast
import androidx.core.view.WindowCompat
import com.airportweather.map.databinding.ActivityNavLogBinding
import com.airportweather.map.utils.FlightPlanHolder

class NavLogActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NavLogLegAdapter
    private lateinit var binding: ActivityNavLogBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        binding = ActivityNavLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val flightPlan = FlightPlanHolder.currentPlan
        if (flightPlan == null) {
            Toast.makeText(this, "No flight plan loaded", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val rows = NavLogRow.build(flightPlan)
        recyclerView = binding.navLogRecyclerView
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = NavLogLegAdapter(rows)
        recyclerView.adapter = adapter

        val totalDistance = flightPlan.legs.sumOf { it.distanceNM }
        val totalFuelUsed = flightPlan.legs.sumOf { it.fuelUsed }
        val totalMinutes = flightPlan.legs.sumOf {
            val parts = it.ete.split(":")
            if (parts.size == 2) parts[0].toInt() * 60 + parts[1].toInt()
            else parts[0].toIntOrNull() ?: 0
        }
        val totalETE = String.format("%d:%02d", totalMinutes / 60, totalMinutes % 60)

        // Fuel remaining at the destination is the last row's running value
        // (matches what we show per-leg). Falls back to fuel-used when no
        // starting fuel was entered, so the pilot still gets a useful number.
        val finalFuelRemaining = rows.lastOrNull()?.fuelRemaining ?: 0.0
        val totalFuelText = if (flightPlan.startingFuel > 0.0) {
            "%.1f rem".format(finalFuelRemaining)
        } else {
            "%.1f used".format(totalFuelUsed)
        }

        binding.navlogTotalsRow.totalDistance.text = "%.1f".format(totalDistance)
        binding.navlogTotalsRow.totalFuel.text = totalFuelText
        binding.navlogTotalsRow.totalEte.text = totalETE
    }
}
