package com.airportweather.map

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.widget.TextView
import com.airportweather.map.databinding.ActivityFlightPlanBinding

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

        // ✅ Load the "current" flight plan when opening the page
        loadFlightPlan()

        // ✅ Example: Change text programmatically
        //binding.flightPlanText.text = "Flight Plan"

        // ✅ Listen for text input in flightPlanEdit
        /*binding.flightPlanEdit.addTextChangedListener(object : TextWatcher {
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
        })*/
        binding.flightPlanEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.flightPlanEdit.removeTextChangedListener(this)

                val dbHelper = AirportDatabaseHelper(this@FlightPlanActivity)
                val input = s.toString().uppercase()
                val endsWithSpace = input.endsWith(" ")
                val words = input.trim().split("\\s+".toRegex())

                val spannable = SpannableStringBuilder()

                for (i in words.indices) {
                    val word = words[i]
                    if (word.isEmpty()) continue

                    val isComplete = (i < words.lastIndex) || endsWithSpace
//                    val color = when {
//                        isComplete && dbHelper.airportExists(word) -> Color.GREEN
//                        !isComplete && dbHelper.airportPrefixExists(word) -> Color.WHITE
//                        else -> Color.RED
//                    }

                    val color = when {
                        dbHelper.airportExists(word) -> Color.GREEN
                        dbHelper.airportPrefixExists(word) -> Color.WHITE
                        else -> Color.RED
                    }

                    val start = spannable.length
                    spannable.append(word)
                    spannable.setSpan(
                        ForegroundColorSpan(color),
                        start,
                        start + word.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )

                    if (i < words.lastIndex || endsWithSpace) {
                        spannable.append(" ")
                    }
                }

                val cursorPos = binding.flightPlanEdit.selectionStart
                binding.flightPlanEdit.setText(spannable, TextView.BufferType.SPANNABLE)
                binding.flightPlanEdit.setSelection(minOf(cursorPos, spannable.length))


//                val cursorPos = spannable.length
//                binding.flightPlanEdit.setText(spannable)
//                binding.flightPlanEdit.setSelection(cursorPos)

                binding.flightPlanEdit.addTextChangedListener(this)
            }



            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })


        // BUTTONS

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

        // ✅ When "Delete Flight Plan" button is clicked, delete saved flight plan
        binding.deleteFlightPlanButton.setOnClickListener {
            //deleteNamedFlightPlan(flightplanName)
        }

        // ✅ When "Activate Flight Plan" button is clicked, send waypoints to MainActivity
        binding.activateFlightPlanButton.setOnClickListener {
            val waypoints = binding.flightPlanEdit.text.toString().trim().split("\\s+".toRegex())
            if (waypoints.isEmpty()) return@setOnClickListener

            // ✅ Validate, Save, Submit
            //validateWaypoints(flightplan)
            saveFlightPlan(binding.flightPlanEdit.text.toString())
            sendWaypointsToMap(waypoints)
        }

        // ✅ When "Clear Flight Plan" button is clicked, remove waypoints
        binding.clearFlightPlanButton.setOnClickListener {
            binding.flightPlanEdit.text.clear()

            // Remove saved flight plan
            sharedPreferences.edit().remove("SAVED_FLIGHT_PLAN").apply()

            // Remove waypoints sent to map
            sharedPreferences.edit().remove("WAYPOINTS").apply()

            // Send empty list to MainActivity
            //sendWaypointsToMap(emptyList())
        }
    }

    /*private fun loadAirportsFromCSV(): MutableList<String> {
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
    }*/

    // current flight plan
    private fun loadFlightPlan() {
        val currentFlightPlan = sharedPreferences.getString("SAVED_FLIGHT_PLAN", "")
        binding.flightPlanEdit.setText(currentFlightPlan)
    }

    private fun saveFlightPlan(flightPlan: String) {
        sharedPreferences.edit().putString("SAVED_FLIGHT_PLAN", flightPlan).apply()
    }

    // saved flight plans
    fun deleteNamedFlightPlan(name: String) {
        val prefs = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)
        val plans = prefs.getStringSet("SAVED_FLIGHT_PLANS", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        plans.removeIf { it.startsWith("$name|") }
        prefs.edit().putStringSet("SAVED_FLIGHT_PLANS", plans).apply()
    }

    fun saveNamedFlightPlan(name: String, flightPlan: String) {
        val prefs = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)
        val allPlans = prefs.getStringSet("SAVED_FLIGHT_PLANS", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        allPlans.removeIf { it.startsWith("$name|") }
        allPlans.add("$name|$flightPlan")
        prefs.edit().putStringSet("SAVED_FLIGHT_PLANS", allPlans).apply()
    }

    fun loadAllFlightPlans(): List<Pair<String, String>> {
        val prefs = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)
        return prefs.getStringSet("SAVED_FLIGHT_PLANS", emptySet())?.mapNotNull {
            val parts = it.split("|", limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        } ?: emptyList()
    }

    // push to map
    private fun sendWaypointsToMap(waypoints: List<String>) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putStringArrayListExtra("WAYPOINTS", ArrayList(waypoints))
        startActivity(intent)
    }

    private fun validateWaypoints(input: String) {
        val waypoints = input.trim().split("\\s+".toRegex())
        val invalidWaypoints = waypoints.filter { it.isNotEmpty() && !airportMap.containsKey(it.uppercase()) }

        // ✅ Change text color if any invalid waypoints exist
        if (invalidWaypoints.isNotEmpty()) {
            binding.flightPlanEdit.setTextColor(android.graphics.Color.RED)
        } else {
            binding.flightPlanEdit.setTextColor(android.graphics.Color.WHITE)
        }

        // ✅ Update UI for next and final waypoint
        //binding.nextWaypointName.text = waypoints.firstOrNull()?.uppercase() ?: "N/A"
        //binding.destText.text = waypoints.lastOrNull()?.uppercase() ?: "N/A"
    }

}
