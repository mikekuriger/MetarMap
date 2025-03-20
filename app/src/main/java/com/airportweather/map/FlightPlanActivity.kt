package com.airportweather.map

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.ArrayAdapter
import androidx.appcompat.app.AppCompatActivity
import com.airportweather.map.databinding.ActivityFlightPlanBinding
import java.io.BufferedReader
import java.io.InputStreamReader

class FlightPlanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlightPlanBinding
    private lateinit var sharedPreferences: SharedPreferences
    private val airportMap = mutableMapOf<String, Boolean>() // Stores valid airports

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Initialize View Binding
        binding = ActivityFlightPlanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)

        // ✅ Load the saved flight plan when opening the page
        loadFlightPlan()

        loadAirportsFromCSV()

        setupAutoComplete()

        // ✅ Example: Change text programmatically
        binding.flightPlanText.text = "Flight Plan"

        // ✅ Listen for text input in flightPlanEdit
        binding.flightPlanEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.flightPlanEdit.removeTextChangedListener(this) // Prevent infinite loop

                val upperCaseText = s.toString().uppercase()
                if (s.toString() != upperCaseText) {
                    binding.flightPlanEdit.setText(upperCaseText)
                    binding.flightPlanEdit.setSelection(upperCaseText.length) // Keep cursor at the end
                }

                //validateWaypoints(binding.flightPlanEdit.text.toString())

                binding.flightPlanEdit.addTextChangedListener(this) // Reattach listener
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })


        // ✅ When "Plot Flight Plan" button is clicked, send waypoints to MainActivity
        binding.plotFlightPlanButton.setOnClickListener {
            val waypoints = binding.flightPlanEdit.text.toString().trim().split("\\s+".toRegex())
            if (waypoints.isEmpty()) return@setOnClickListener

            // ✅ Validate, Save, Submit
            //validateWaypoints(binding.flightPlanEdit.text.toString())
            saveFlightPlan(binding.flightPlanEdit.text.toString())
            sendWaypointsToMap(waypoints)
        }
    }


    private fun setupAutoComplete() {
        val airportCodes = loadAirportsFromCSV() // Load ICAO codes into a list
        if (airportCodes.isEmpty()) {
            Log.e("DEBUG", "Airport list is empty! AutoComplete will not work.")
            return
        }

        //val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, airportCodes)
        val adapter = ArrayAdapter(this, R.layout.item_autocomplete, R.id.autocompleteText, airportCodes)


        // ✅ Attach adapter to AutoCompleteTextView
        binding.flightPlanEdit.setAdapter(adapter)
        binding.flightPlanEdit.threshold = 1 // Start suggesting after 1 character

        binding.flightPlanEdit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) binding.flightPlanEdit.showDropDown() // ✅ Show dropdown on focus
        }
    }

    private fun loadAirportsFromCSV(): MutableList<String> {
        val airportCodes: MutableList<String> = mutableListOf()
        val inputStream = resources.openRawResource(R.raw.airports)
        val reader = BufferedReader(InputStreamReader(inputStream))

        reader.useLines { lines ->
            lines.forEach { line ->
                val tokens = line.split(",")
                if (tokens.size > 5) {
                    val id = tokens[1].trim('"').uppercase()
                    airportCodes.add(id) // ✅ Store ICAO code in list
                }
            }
        }

        Log.d("DEBUG", "Loaded Airports: $airportCodes") // ✅ Debugging output
        return airportCodes
    }


    /*private fun loadAirportsFromCSV(): MutableList<String> {
        val airportCodes: MutableList<String> = mutableListOf()
        val inputStream = resources.openRawResource(R.raw.airports)
        val reader = BufferedReader(InputStreamReader(inputStream))

        reader.useLines { lines ->
            lines.forEach { line ->
                val tokens = line.split(",")
                if (tokens.size > 5) {
                    val id = tokens[1].trim('"').uppercase() // Convert to uppercase (standard ICAO)
                    airportMap[id] = true
                }
            }
        }
        return airportCodes
    }*/


    private fun validateWaypoints(input: String) {
        val waypoints = input.trim().split("\\s+".toRegex())
        val invalidWaypoints = waypoints.filter { it.isNotEmpty() && !airportMap.containsKey(it.uppercase()) }

        // ✅ Change text color if any invalid waypoints exist
        if (invalidWaypoints.isNotEmpty()) {
            binding.flightPlanEdit.setTextColor(android.graphics.Color.RED)
        } else {
            binding.flightPlanEdit.setTextColor(android.graphics.Color.BLACK)
        }

         // ✅ Update UI for next and final waypoint
        //binding.nextWaypointName.text = waypoints.firstOrNull()?.uppercase() ?: "N/A"
        //binding.destText.text = waypoints.lastOrNull()?.uppercase() ?: "N/A"
    }

    private fun saveFlightPlan(flightPlan: String) {
        sharedPreferences.edit().putString("SAVED_FLIGHT_PLAN", flightPlan).apply()
    }

    private fun loadFlightPlan() {
        val savedFlightPlan = sharedPreferences.getString("SAVED_FLIGHT_PLAN", "")
        binding.flightPlanEdit.setText(savedFlightPlan)
    }

    private fun sendWaypointsToMap(waypoints: List<String>) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putStringArrayListExtra("WAYPOINTS", ArrayList(waypoints))
        startActivity(intent)
    }
}
