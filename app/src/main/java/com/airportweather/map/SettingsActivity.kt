package com.airportweather.map

import android.content.SharedPreferences
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.airportweather.map.databinding.ActivitySettingsBinding

lateinit var sharedPrefs: SharedPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sharedPrefs = getSharedPreferences("MapSettings", MODE_PRIVATE)

        val showAirspace = binding.toggleAirspaceButton
        val showTfrs = binding.toggleTfrButton
        val showMetars = binding.toggleMetarButton
        val showChart = binding.toggleChartsButton
        val showTraffic = binding.toggleTrafficButton

        showAirspace.isChecked = sharedPrefs.getBoolean("show_airspace", true)
        showTfrs.isChecked = sharedPrefs.getBoolean("show_tfrs", true)
        showMetars.isChecked = sharedPrefs.getBoolean("show_metars", true)
        showChart.isChecked = sharedPrefs.getBoolean("show_chart", true)
        showTraffic.isChecked = sharedPrefs.getBoolean("show_traffic", true)

        showAirspace.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("show_airspace", isChecked).apply()
        }

        showTfrs.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("show_tfrs", isChecked).apply()
        }

        showMetars.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("show_metars", isChecked).apply()
        }

        showChart.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("show_chart", isChecked).apply()
        }

        showTraffic.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("show_traffic", isChecked).apply()
        }
    }
}

