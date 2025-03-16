package com.airportweather.map

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.airportweather.map.databinding.ActivityFlightPlanBinding

class FlightPlanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlightPlanBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Initialize View Binding
        binding = ActivityFlightPlanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Example: Change text programmatically
        binding.flightPlanText.text = "Flight Plan Loaded!"
    }
}
