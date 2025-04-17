package com.airportweather.map

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
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

        // Map styles
        val styles = MapStyle.entries.map { it.name }
        val spinner = findViewById<Spinner>(R.id.mapStyleSpinner)

        // ✅ Set up the adapter
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, styles)

        // ✅ Set spinner to current saved style
        val currentStyle = MapStyleManager.getStyle(this)
        val index = styles.indexOf(currentStyle.name)
        if (index >= 0) {
            spinner.setSelection(index)
        }

        // ✅ Track if the user actually touched the spinner to avoid auto-saving
        var userTouched = false
        spinner.setOnTouchListener { view, _ ->
            userTouched = true
            view.performClick()  // ✅ satisfies accessibility
            false  // allow normal behavior to proceed
        }

        // ✅ Handle selection
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (!userTouched) return  // skip first auto-triggered call

                val selected = MapStyle.valueOf(styles[position])
                val current = MapStyleManager.getStyle(this@SettingsActivity)

                if (selected != current) {
                    MapStyleManager.saveStyle(this@SettingsActivity, selected)
                    Log.d("MapStyle", "User changed style to: ${selected.name}")

                    // Restart MapActivity to apply new style
                    val intent = Intent(this@SettingsActivity, MainActivity::class.java)
                    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }


        // other settings

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

