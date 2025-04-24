package com.airportweather.map

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.airportweather.map.databinding.ActivityFlightPlanBinding
import com.airportweather.map.utils.AirportDatabaseHelper
import com.airportweather.map.utils.FlightPlanHolder
import com.airportweather.map.utils.buildFlightPlanFromText
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

class FlightPlanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlightPlanBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var fusedLocationClient: FusedLocationProviderClient


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // ✅ Initialize View Binding
        binding = ActivityFlightPlanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)

        // ✅ Load the "current" flight plan when opening the page
        loadFlightPlan()

        // ✅ Listen for text input in flightPlanEdit
        binding.flightPlanEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank()) {
                    binding.activateFlightPlanButton.text = "Exit"
                } else {
                    binding.activateFlightPlanButton.text = "Activate"
                }

                binding.flightPlanEdit.removeTextChangedListener(this)

                val current = s.toString()
                val upper = current.uppercase()

                if (current != upper) {
                    s?.replace(0, s.length, upper)
                    binding.flightPlanEdit.setSelection(minOf(binding.flightPlanEdit.selectionStart, upper.length))
                }

                val dbHelper = AirportDatabaseHelper(this@FlightPlanActivity)
                val input = s.toString().uppercase()
                val endsWithSpace = input.endsWith(" ")
                val words = input.trim().split("\\s+".toRegex())

                // Clear existing spans
                s?.getSpans(0, s.length, ForegroundColorSpan::class.java)?.forEach {
                    s.removeSpan(it)
                }

                var position = 0
                for (i in words.indices) {
                    val word = words[i]
                    if (word.isEmpty()) continue

                    val isLastWord = (i == words.lastIndex) && !endsWithSpace
                    val color = when {
                        dbHelper.fixExists(word) -> Color.YELLOW
                        dbHelper.navExists(word) -> Color.BLUE
                        dbHelper.airportExists(word) -> Color.GREEN
                        dbHelper.fixPrefixExists(word) && isLastWord -> Color.WHITE
                        dbHelper.navPrefixExists(word) && isLastWord -> Color.WHITE
                        dbHelper.airportPrefixExists(word) && isLastWord -> Color.WHITE
                        else -> Color.RED
                    }

                    s?.setSpan(
                        ForegroundColorSpan(color),
                        position,
                        position + word.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    position += word.length + 1 // account for the space
                }

                binding.flightPlanEdit.addTextChangedListener(this)
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })


        // BUTTONS
        // ✅ When "Reverse Flight Plan" button is clicked
        binding.reverseFlightPlanButton.setOnClickListener {
            val currentPlan = binding.flightPlanEdit.text.toString().trim()
            if (currentPlan.isEmpty()) return@setOnClickListener

            val reversedPlan = currentPlan
                .split(Regex("\\s+"))     // split by any whitespace
                .reversed()               // reverse the list
                .joinToString(" ")        // join back to a string

            binding.flightPlanEdit.setText(reversedPlan)
        }

        // ✅ When "Load Flight Plan" button is clicked, load saved flight plan
        binding.loadFlightPlanButton.setOnClickListener {
            val allPlans = loadAllFlightPlans()
            val planNames = allPlans.map { it.first }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Load Flight Plan")
                .setItems(planNames) { _, which ->
                    val planText = allPlans[which].second
                    binding.flightPlanEdit.setText(planText)
                }
                .show()
        }

        // ✅ When "Delete Flight Plan" button is clicked, delete saved flight plan
        binding.deleteFlightPlanButton.setOnClickListener {
            val allPlans = loadAllFlightPlans()
            val planNames = allPlans.map { it.first }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Delete Flight Plan")
                .setItems(planNames) { _, which ->
                    // Get the plan name for the selected item.
                    val planName = allPlans[which].first
                    // Delete the flight plan using your function.
                    deleteNamedFlightPlan(planName)
                    // Optionally clear the flight plan edit field if it was loaded.
                    //binding.flightPlanEdit.text.clear()
                    // Provide some feedback.
                    Toast.makeText(this, "$planName deleted", Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // ✅ When "Save Flight Plan" button is clicked, save current flight plan
        binding.saveFlightPlanButton.setOnClickListener {
            val currentPlan = binding.flightPlanEdit.text.toString().trim()
            if (currentPlan.isEmpty()) return@setOnClickListener

            // Show input dialog
            val input = EditText(this)
            input.hint = "Enter flight plan name"

            AlertDialog.Builder(this)
                .setTitle("Save Flight Plan")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val name = input.text.toString().trim()
                    if (name.isNotEmpty()) {
                        saveNamedFlightPlan(name, currentPlan)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // ✅ When "Activate Flight Plan" button is clicked
        // it might also be an exit button
        binding.activateFlightPlanButton.setOnClickListener {
            val rawText = binding.flightPlanEdit.text.toString().trim()
            if (rawText.isBlank()) {
                // Clear any existing flight plan and go back to map
                FlightPlanHolder.currentPlan = null
                sharedPreferences.edit().remove("WAYPOINTS").apply()

                val intent = Intent(this, MainActivity::class.java)
                startActivity(intent)
                return@setOnClickListener
            }

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ){
                Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                val flightPlan = buildFlightPlanFromText(this, rawText, currentLocation = location)

                if (flightPlan != null) {
                    val intent = Intent(this, MainActivity::class.java)
                    intent.putParcelableArrayListExtra(
                        "WAYPOINTS",
                        ArrayList(flightPlan.legs.flatMap { listOf(it.from, it.to) }.distinctBy { it.name })
                    )

                    FlightPlanHolder.currentPlan = flightPlan
                    sharedPreferences.edit().putString("WAYPOINTS", rawText).apply()
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Invalid flight plan", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ✅ When "Clear Flight Plan" button is clicked, remove waypoints
        binding.clearFlightPlanButton.setOnClickListener {
            binding.flightPlanEdit.text.clear()

            // Remove waypoints sent to map
            sharedPreferences.edit().remove("WAYPOINTS").apply()

            // change activate button to exit button
            binding.activateFlightPlanButton.text = "Exit"
            // Send empty list to MainActivity
            //sendWaypointsToMap(emptyList())
        }
    }

    // current flight plan
    private fun loadFlightPlan() {
        val rawText = sharedPreferences.getString("WAYPOINTS", "") ?: ""
        binding.flightPlanEdit.setText(rawText)
        binding.activateFlightPlanButton.text = if (rawText.isNotBlank()) "Activate" else "Exit"

//        Log.d("FlightPlanActivity", "Loaded rawText: '$rawText'")
//        val flightPlan = buildFlightPlanFromText(this, rawText)
//        Log.d("FlightPlanActivity", "Flight plan built: ${flightPlan != null}")

//        // Manually check and update the button label
//        if (rawText.isNotBlank()) {
//            val flightPlan = buildFlightPlanFromText(this, rawText)
//            if (flightPlan != null) {
//                FlightPlanHolder.currentPlan = flightPlan
//                binding.activateFlightPlanButton.text = "Activate"
//            } else {
//                binding.activateFlightPlanButton.text = "Exit"
//            }
//        } else {
//            binding.activateFlightPlanButton.text = "Exit"
//        }
    }


    // saved flight plans
    private fun deleteNamedFlightPlan(name: String) {
        val prefs = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)
        val plans = prefs.getStringSet("SAVED_FLIGHT_PLANS", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        plans.removeIf { it.startsWith("$name|") }
        prefs.edit().putStringSet("SAVED_FLIGHT_PLANS", plans).apply()
    }

    private fun saveNamedFlightPlan(name: String, flightPlan: String) {
        val prefs = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)
        val allPlans = prefs.getStringSet("SAVED_FLIGHT_PLANS", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        allPlans.removeIf { it.startsWith("$name|") }
        allPlans.add("$name|$flightPlan")
        prefs.edit().putStringSet("SAVED_FLIGHT_PLANS", allPlans).apply()
    }

    private fun loadAllFlightPlans(): List<Pair<String, String>> {
        val prefs = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)
        return prefs.getStringSet("SAVED_FLIGHT_PLANS", emptySet())?.mapNotNull {
            val parts = it.split("|", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        } ?: emptyList()
    }

    // push to map
    private fun sendWaypointsToMap(waypoints: List<Waypoint>) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putParcelableArrayListExtra("WAYPOINTS", ArrayList(waypoints))
        Log.d("FlightPlan", "Sending ${waypoints.size} waypoints")
        for (wp in waypoints) {
            Log.d("FlightPlan", "→ ${wp.name} (${wp.lat}, ${wp.lon})")
        }
        startActivity(intent)
    }
}
