package com.airportweather.map

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.airportweather.map.aircraft.AircraftListActivity
import com.airportweather.map.aircraft.AircraftRepository
import com.airportweather.map.aircraft.AircraftSelectionStore
import com.airportweather.map.databinding.ActivitySettingsBinding

lateinit var sharedPrefs: SharedPreferences

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Respect system UI insets
        WindowCompat.setDecorFitsSystemWindows(window, true)

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
        val hideDistantTraffic = binding.hideDistantTraffic
        val forceInternalGps = binding.forceInternalGps

        showAirspace.isChecked = sharedPrefs.getBoolean("show_airspace", true)
        showTfrs.isChecked = sharedPrefs.getBoolean("show_tfrs", true)
        showMetars.isChecked = sharedPrefs.getBoolean("show_metars", true)
        showChart.isChecked = sharedPrefs.getBoolean("show_chart", true)
        showTraffic.isChecked = sharedPrefs.getBoolean("show_traffic", true)
        hideDistantTraffic.isChecked = sharedPrefs.getBoolean("hide_distant_traffic", true)
        forceInternalGps.isChecked = sharedPrefs.getBoolean("force_internal_gps", false)

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

        hideDistantTraffic.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("hide_distant_traffic", isChecked).apply()
        }

        forceInternalGps.setOnCheckedChangeListener { _, isChecked ->
            sharedPrefs.edit().putBoolean("force_internal_gps", isChecked).apply()
        }

        // Aircraft management — open the aircraft list activity. The summary
        // text below the button reflects the currently-selected aircraft +
        // profile, refreshed on each onResume.
        findViewById<Button>(R.id.manageAircraftButton).setOnClickListener {
            startActivity(Intent(this, AircraftListActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAircraftSummary()
    }

    /** One-line "N12345 · Cruise 65%" summary under the Manage aircraft button. */
    private fun refreshAircraftSummary() {
        val summaryView = findViewById<TextView>(R.id.currentAircraftSummary) ?: return
        val repo = AircraftRepository.get(filesDir)
        val selection = AircraftSelectionStore(this).resolveSelection(repo)
        summaryView.text = if (selection == null) {
            "No aircraft selected"
        } else {
            val tail = selection.aircraft.general.tailNumber.ifBlank { "(no tail #)" }
            "$tail · ${selection.profile.name}"
        }
    }
}

