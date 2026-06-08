package com.airportweather.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.InputMethodManager
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.util.Log
import android.widget.Toast
import com.airportweather.map.aircraft.Aircraft
import com.airportweather.map.aircraft.AircraftListActivity
import com.airportweather.map.aircraft.PerformanceProfile
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.airportweather.map.databinding.ActivityFlightPlanBinding
import com.airportweather.map.aircraft.AircraftRepository
import com.airportweather.map.aircraft.AircraftSelectionStore
import com.airportweather.map.utils.AirportDatabaseHelper
import com.airportweather.map.utils.FlightPlanHolder
import com.airportweather.map.utils.UserWaypoints
import com.airportweather.map.utils.Waypoint
import com.airportweather.map.utils.buildFlightPlanFromText
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class FlightPlanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFlightPlanBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var searchAdapter: WaypointSearchAdapter
    private var searchJob: Job? = null

    /** Snapshot of aircraft list used to back the spinners — kept in declaration
     *  order so spinner indices map 1:1. */
    private var aircraftList: List<Aircraft> = emptyList()
    private var profilesForSelected: List<PerformanceProfile> = emptyList()

    /** Altitude options currently in the cruise altitude spinner (ft MSL),
     *  in spinner order. Filtered by the selected aircraft's max ceiling. */
    private var altitudeOptions: List<Int> = emptyList()
    /** Most recent winds-aloft lookup keyed by altitude. Used to label the
     *  altitude spinner and to auto-fill the wind fields when the user picks
     *  a different altitude. */
    private var altitudeWinds: Map<Int, Pair<Int, Int>> = emptyMap()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Respect system UI insets
        WindowCompat.setDecorFitsSystemWindows(window, true)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // ✅ Initialize View Binding
        binding = ActivityFlightPlanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("FlightPlanPrefs", MODE_PRIVATE)

        setupAircraftPicker()

        // ✅ Load the "current" flight plan when opening the page
        loadFlightPlan()

        // Uppercase at the input layer instead of inside the watcher.
        // Mutating the Editable from afterTextChanged() while the IME is
        // still composing a character race-conditioned and produced
        // duplicated input ("KSBA" typed fast → "KSBKSBA"). AllCaps runs
        // before the IME commit, so no remove/re-add listener dance.
        binding.flightPlanEdit.filters = arrayOf<InputFilter>(InputFilter.AllCaps())

        // ✅ Listen for text input in flightPlanEdit
        binding.flightPlanEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrBlank()) {
                    binding.activateFlightPlanButton.text = "Exit"
                } else {
                    binding.activateFlightPlanButton.text = "Activate"
                }
                // The route course feeds head/tail wind labels in the altitude
                // spinner; recompute when waypoints change. The plan totals
                // strip also depends on waypoints, so refresh it too. Border
                // validity also depends on the route's airport elevations.
                refreshAltitudeLabels()
                refreshPreview()
                refreshValidationBorders()

                val dbHelper = AirportDatabaseHelper(this@FlightPlanActivity)
                UserWaypoints.ensureLoaded(this@FlightPlanActivity)
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
                        UserWaypoints.exists(word) -> Color.MAGENTA  // test/user waypoints
                        dbHelper.fixExists(word) -> Color.YELLOW
                        dbHelper.navExists(word) -> Color.BLUE
                        dbHelper.airportExists(word) -> Color.GREEN
                        UserWaypoints.prefixExists(word) && isLastWord -> Color.WHITE
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
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        setupWaypointSearch()

        // Preview strip → Nav Log. Tapping the rolled-up totals opens the
        // leg-by-leg breakdown. Builds a fresh plan from the current editor
        // state (waypoints + aircraft + altitude) using cached winds, so
        // the user doesn't have to Activate first just to peek at numbers.
        binding.previewSummary.setOnClickListener {
            val rawText = binding.flightPlanEdit.text?.toString()?.trim().orEmpty()
            if (rawText.isBlank()) {
                Toast.makeText(this, "Enter waypoints first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val alt = selectedCruiseAltitude()
            val wind = alt?.let { altitudeWinds[it] }
            val plan = buildFlightPlanFromText(
                context = this,
                rawText = rawText,
                cruiseAltitude = alt,
                windDir = wind?.first ?: 0,
                windSpeed = wind?.second ?: 0,
                startingFuel = startingFuelFromAircraft(),
                currentLocation = null,
            )
            if (plan == null || plan.legs.isEmpty()) {
                Toast.makeText(this, "Couldn't build plan from these waypoints", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // NavLogActivity reads from the holder. Stash the freshly-built
            // plan so the user sees the same numbers as the preview strip,
            // even if they haven't Activate'd yet.
            FlightPlanHolder.currentPlan = plan
            startActivity(Intent(this, NavLogActivity::class.java))
        }

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

                returnToMainActivity()
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

            // Default to the active aircraft's full usable capacity. A
            // per-flight override (less-than-full tanks) will come with
            // the W&B screen.
            val startingFuel = startingFuelFromAircraft()

            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                lifecycleScope.launch {
                    // Wind for plan generation comes from the FB forecast at
                    // the selected cruise altitude. Prefer the value already
                    // cached for the altitude spinner (annotated as HW/TW);
                    // if that's missing, fall back to a fresh lookup at the
                    // chosen altitude.
                    val chosenAlt = selectedCruiseAltitude()
                    val cached = chosenAlt?.let { altitudeWinds[it] }
                    val (resolvedWindDir, resolvedWindSpeed) = cached
                        ?: tryFetchWindsAloft(rawText, location, chosenAlt)
                        ?: (0 to 0)

                    val flightPlan = buildFlightPlanFromText(
                        context = this@FlightPlanActivity,
                        rawText = rawText,
                        cruiseAltitude = selectedCruiseAltitude(),
                        windDir = resolvedWindDir,
                        windSpeed = resolvedWindSpeed,
                        startingFuel = startingFuel,
                        currentLocation = location,
                    )

                    if (flightPlan != null) {
                        FlightPlanHolder.currentPlan = flightPlan
                        sharedPreferences.edit()
                            .putString("WAYPOINTS", rawText)
                            .putInt("CRUISE_ALT", selectedCruiseAltitude() ?: 0)
                            .apply()
                        returnToMainActivity()
                    } else {
                        Toast.makeText(this@FlightPlanActivity, "Invalid flight plan", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // ✅ When "Clear Flight Plan" button is clicked, remove waypoints
        binding.clearFlightPlanButton.setOnClickListener {
            binding.flightPlanEdit.text.clear()

            // Remove waypoints sent to map
            sharedPreferences.edit().remove("WAYPOINTS").apply()

            // Also drop the in-memory plan so MainActivity.onResume knows to
            // wipe the on-map polylines AND hide the flight-info HUD. Without
            // this, the HUD keeps showing the last computed numbers even after
            // the plan is gone.
            FlightPlanHolder.currentPlan = null

            // change activate button to exit button
            binding.activateFlightPlanButton.text = "Exit"
        }
    }

    /**
     * Wires the aircraft + profile spinners. Both write back to
     * [AircraftSelectionStore] so any change here propagates to the Settings
     * summary, the HUD on the map, and the flight plan generation. The
     * "Manage" button shortcuts to [AircraftListActivity] for adding/editing.
     */
    private fun setupAircraftPicker() {
        val aircraftSpinner = findViewById<android.widget.Spinner>(R.id.aircraftSpinner)
        val profileSpinner = findViewById<android.widget.Spinner>(R.id.profileSpinner)
        val manageButton = findViewById<android.widget.Button>(R.id.manageAircraftButton)

        manageButton.setOnClickListener {
            startActivity(Intent(this, AircraftListActivity::class.java))
        }

        aircraftSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (aircraftList.isEmpty() || position !in aircraftList.indices) return
                val chosen = aircraftList[position]
                val selectionStore = AircraftSelectionStore(this@FlightPlanActivity)
                if (selectionStore.selectedAircraftId != chosen.id) {
                    selectionStore.selectedAircraftId = chosen.id
                    // Aircraft change → reset to that aircraft's default profile,
                    // not whatever the previous selection had.
                    selectionStore.selectedProfileId = chosen.defaultProfile?.id
                }
                populateProfileSpinner(profileSpinner, chosen, selectionStore.selectedProfileId)
                // Max ceiling can differ between aircraft, so the altitude list
                // (and the wind annotations) need refreshing too.
                refreshAltitudeSpinner(chosen)
                refreshPreview()
                refreshValidationBorders()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        profileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (profilesForSelected.isEmpty() || position !in profilesForSelected.indices) return
                AircraftSelectionStore(this@FlightPlanActivity).selectedProfileId =
                    profilesForSelected[position].id
                // Cruise TAS/GPH differ between profiles → totals change.
                refreshPreview()
                refreshValidationBorders()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Reloads aircraft from the repository and refreshes both spinners. Called
     *  from onResume so returning from AircraftListActivity / AircraftEditActivity
     *  picks up any additions or edits. */
    private fun refreshAircraftPicker() {
        val repo = AircraftRepository.get(filesDir)
        aircraftList = repo.aircraft.value
        val aircraftSpinner = findViewById<android.widget.Spinner>(R.id.aircraftSpinner)
        val profileSpinner = findViewById<android.widget.Spinner>(R.id.profileSpinner)

        if (aircraftList.isEmpty()) {
            profilesForSelected = emptyList()
            aircraftSpinner.adapter = ArrayAdapter(
                this, R.layout.spinner_item_compact, listOf("(none — tap Manage)")
            )
            aircraftSpinner.isEnabled = false
            profileSpinner.adapter = ArrayAdapter(
                this, R.layout.spinner_item_compact, listOf("(none)")
            )
            profileSpinner.isEnabled = false
            refreshAltitudeSpinner(null)
            // No-aircraft state never fires onItemSelected, so paint borders directly.
            refreshValidationBorders()
            return
        }

        aircraftSpinner.isEnabled = true
        profileSpinner.isEnabled = true
        val labels = aircraftList.map { it.general.tailNumber.ifBlank { "(no tail #)" } }
        val adapter = ArrayAdapter(this, R.layout.spinner_item_compact, labels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item)
        }
        aircraftSpinner.adapter = adapter

        // Restore selection. If the stored aircraft id has been deleted, fall
        // back to the first entry — beats showing a stale or empty selection.
        val store = AircraftSelectionStore(this)
        val selectedIdx = aircraftList.indexOfFirst { it.id == store.selectedAircraftId }
            .takeIf { it >= 0 } ?: 0
        aircraftSpinner.setSelection(selectedIdx)
        // The spinner's onItemSelectedListener will populate the profile
        // spinner via populateProfileSpinner.
    }

    /** Returns the cruise altitude currently selected by the user, or null when
     *  no aircraft is configured (so [buildFlightPlanFromText] falls back to
     *  the aircraft default or its hardcoded fallback). */
    private fun selectedCruiseAltitude(): Int? {
        val spinner = findViewById<android.widget.Spinner>(R.id.cruiseAltitudeSpinner)
        val idx = spinner.selectedItemPosition
        return altitudeOptions.getOrNull(idx)
    }

    /**
     * Rebuilds the altitude spinner for the given aircraft. Options are common
     * VFR cruise altitudes (every 2,000 ft from 1,500), filtered to the
     * aircraft's max ceiling. Pre-fills the selection from the saved pref or
     * the aircraft's defaultCruiseAltitude.
     */
    private fun refreshAltitudeSpinner(aircraft: Aircraft?) {
        val spinner = findViewById<android.widget.Spinner>(R.id.cruiseAltitudeSpinner)
        val ceiling = aircraft?.altitudes?.maxCeiling?.takeIf { it > 0 } ?: 18_000
        // Even/odd thousand + 500 covers both eastbound and westbound VFR.
        val baseAltitudes = generateSequence(1500) { it + 1000 }
            .takeWhile { it <= ceiling }
            .toList()
        altitudeOptions = baseAltitudes
        if (altitudeOptions.isEmpty()) {
            spinner.adapter = ArrayAdapter(
                this, R.layout.spinner_item_compact, listOf("(no altitudes)")
            )
            spinner.isEnabled = false
            return
        }
        spinner.isEnabled = true
        spinner.adapter = altitudeAdapter()

        val preferred = sharedPreferences.getInt("CRUISE_ALT", 0).takeIf { it > 0 }
            ?: aircraft?.altitudes?.defaultCruiseAltitude?.takeIf { it > 0 }
        val idx = preferred?.let { p -> altitudeOptions.indexOfFirst { it >= p } }
            ?.takeIf { it >= 0 }
            ?: altitudeOptions.indexOfFirst { it >= 5500 }.takeIf { it >= 0 }
            ?: 0
        spinner.setSelection(idx)

        // Kick off the wind annotation in the background. Doesn't block the UI;
        // when results come back, altitudeAdapter() is regenerated so each row
        // reads "5,500 ft · 240/15".
        lifecycleScope.launch { fetchAltitudeWinds() }

        // Selection just persists the chosen altitude. Wind is no longer
        // mirrored into a separate input — the spinner label itself shows the
        // resulting HW/TW, and Activate reads the wind for whichever altitude
        // is selected.
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val alt = altitudeOptions.getOrNull(position) ?: return
                sharedPreferences.edit().putInt("CRUISE_ALT", alt).apply()
                refreshPreview()
                refreshValidationBorders()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    /** Minimum cruise altitude margin above the highest route airport.
     *  1500 ft AGL clears most obstacles, terrain noise, and pattern
     *  altitudes without restricting low-and-slow planning more than
     *  necessary. */
    private val cruiseMinMarginFt = 1500

    /**
     * Refreshes the colored border on each of the three top-row spinners.
     * Green = the spinner's current value passes validation; red = needs
     * attention. Called whenever any input that affects validity changes
     * (aircraft, profile, altitude, waypoints).
     */
    private fun refreshValidationBorders() {
        val aircraftSpinner = findViewById<android.widget.Spinner>(R.id.aircraftSpinner)
        val profileSpinner = findViewById<android.widget.Spinner>(R.id.profileSpinner)
        val altSpinner = findViewById<android.widget.Spinner>(R.id.cruiseAltitudeSpinner)

        aircraftSpinner.background = drawableFor(aircraftList.isNotEmpty())
        profileSpinner.background = drawableFor(profilesForSelected.isNotEmpty())
        altSpinner.background = drawableFor(isCruiseAltitudeValid())
    }

    private fun drawableFor(valid: Boolean): android.graphics.drawable.Drawable? =
        androidx.core.content.ContextCompat.getDrawable(
            this,
            if (valid) R.drawable.spinner_field_valid else R.drawable.spinner_field,
        )

    /**
     * Minimum-safe cruise altitude for the currently-entered route, in feet
     * MSL. Defined as the highest known-airport elevation on the route plus
     * [cruiseMinMarginFt]. Returns 0 when we can't evaluate (no waypoints,
     * none resolved, or none are airports) — callers treat 0 as "don't
     * flag anything" so users aren't penalized before entering a route.
     *
     * Fixes/navaids are intentionally excluded: their stored elev is 0,
     * which would silently make every altitude pass the check.
     */
    private fun routeFloorFt(): Int {
        val rawText = binding.flightPlanEdit.text?.toString()?.trim().orEmpty()
        if (rawText.isBlank()) return 0
        val dbHelper = AirportDatabaseHelper(this)
        val resolved = rawText.split("\\s+".toRegex())
            .mapNotNull { dbHelper.lookupWaypoint(it.uppercase()) }
        val airportElevs = resolved
            .filter { it.type.equals("AIRPORT", ignoreCase = true) }
            .map { it.elev.toInt() }
        if (airportElevs.isEmpty()) return 0
        return airportElevs.max() + cruiseMinMarginFt
    }

    /**
     * True when the currently-selected cruise altitude is at least
     * [cruiseMinMarginFt] above the highest airport on the route, or
     * when we can't yet evaluate the route. Aircraft ceiling isn't
     * checked here — the spinner is already filtered to ≤ ceiling in
     * refreshAltitudeSpinner().
     */
    private fun isCruiseAltitudeValid(): Boolean {
        if (altitudeOptions.isEmpty()) return false
        val alt = selectedCruiseAltitude() ?: return false
        val floor = routeFloorFt()
        return floor == 0 || alt >= floor
    }

    /**
     * Starting fuel for the nav log. Default: the active aircraft's full
     * usable capacity ("full tanks"). When the W&B screen lands it will
     * supply a per-flight override; until then full tanks is the right
     * assumption for most flights.
     */
    private fun startingFuelFromAircraft(): Double {
        val selection = AircraftSelectionStore(this)
            .resolveSelection(AircraftRepository.get(filesDir))
        return selection?.aircraft?.fuel?.totalUsableCapacity ?: 0.0
    }

    /**
     * Builds a flight plan from current state — waypoints, aircraft, profile,
     * altitude, cached winds — and renders the totals strip. Cheap enough to
     * call on every keystroke; bails early when waypoints don't resolve.
     */
    private fun refreshPreview() {
        val view = findViewById<android.widget.TextView>(R.id.previewSummary) ?: return
        val rawText = binding.flightPlanEdit.text?.toString().orEmpty().trim()
        if (rawText.isBlank()) { view.text = ""; return }

        val alt = selectedCruiseAltitude()
        val wind = alt?.let { altitudeWinds[it] }
        val startingFuel = startingFuelFromAircraft()

        val plan = buildFlightPlanFromText(
            context = this,
            rawText = rawText,
            cruiseAltitude = alt,
            windDir = wind?.first ?: 0,
            windSpeed = wind?.second ?: 0,
            startingFuel = startingFuel,
            currentLocation = null,
        )
        if (plan == null || plan.legs.isEmpty()) { view.text = ""; return }

        val totalDist = plan.legs.sumOf { it.distanceNM }
        val totalFuel = plan.legs.sumOf { it.fuelUsed }
        val totalMin = plan.legs.sumOf {
            val parts = it.ete.split(":")
            if (parts.size == 2) parts[0].toInt() * 60 + parts[1].toInt()
            else parts[0].toIntOrNull() ?: 0
        }
        val timeText = "%d:%02d".format(totalMin / 60, totalMin % 60)
        // Trailing chevron hints that the strip is tappable (opens Nav Log).
        view.text = "%.1f nm  ·  %s  ·  %.1f gal   ›".format(totalDist, timeText, totalFuel)
    }

    /** Re-renders the cruise altitude spinner labels using the current
     *  waypoints + cached wind data. Cheap — just rebuilds the adapter. */
    private fun refreshAltitudeLabels() {
        if (altitudeOptions.isEmpty()) return
        val spinner = findViewById<android.widget.Spinner>(R.id.cruiseAltitudeSpinner)
        val keep = spinner.selectedItemPosition
        spinner.adapter = altitudeAdapter()
        if (keep in altitudeOptions.indices) spinner.setSelection(keep)
    }

    private fun altitudeAdapter(): ArrayAdapter<CharSequence> {
        // Resolve the route's course once per refresh so we can project each
        // altitude's wind onto it. Null when there aren't enough waypoints to
        // define a course — in that case we fall back to dir/speed annotations
        // so the data isn't lost.
        val course = computeRouteCourse()
        val floor = routeFloorFt()  // 0 → don't flag any altitude
        val redSpan = { ForegroundColorSpan(android.graphics.Color.RED) }
        val greenSpan = { ForegroundColorSpan(android.graphics.Color.parseColor("#66BB6A")) }

        val labels: List<CharSequence> = altitudeOptions.map { alt ->
            val tooLow = floor > 0 && alt < floor
            val sb = SpannableStringBuilder("%,d ft".format(alt))
            val wind = altitudeWinds[alt]
            if (wind != null) {
                val (dir, speed) = wind
                sb.append("  ·  ")
                when {
                    course == null || speed == 0 -> {
                        sb.append("${dir}°/${speed}")
                    }
                    else -> {
                        // Positive = headwind, negative = tailwind. The
                        // relative angle is "wind FROM minus course" because
                        // FB reports the direction the wind is coming FROM.
                        val relRad = Math.toRadians(((dir - course + 360) % 360))
                        val component = (speed * Math.cos(relRad)).roundToInt()
                        val start = sb.length
                        when {
                            component > 0 -> {
                                sb.append("$component kt HW")
                                // Skip the wind color when the row is going
                                // to be painted red end-to-end anyway —
                                // avoids a red-on-red span war and keeps the
                                // "row is invalid" signal clean.
                                if (!tooLow) {
                                    sb.setSpan(redSpan(), start, sb.length,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                            component < 0 -> {
                                sb.append("${-component} kt TW")
                                if (!tooLow) {
                                    sb.setSpan(greenSpan(), start, sb.length,
                                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                                }
                            }
                            else -> sb.append("pure x-wind")
                        }
                    }
                }
            }
            if (tooLow) {
                sb.setSpan(redSpan(), 0, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            sb
        }

        return ArrayAdapter<CharSequence>(this, R.layout.spinner_item_compact, labels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_item_alt)
        }
    }

    /**
     * Returns the route's great-circle initial course (first → last waypoint),
     * or null if the waypoints field doesn't resolve to two distinct points.
     * Used to compute headwind/tailwind for the altitude spinner annotations.
     */
    private fun computeRouteCourse(): Double? {
        val raw = binding.flightPlanEdit.text?.toString() ?: return null
        val dbHelper = com.airportweather.map.utils.AirportDatabaseHelper(this)
        val wps = raw.trim().split("\\s+".toRegex())
            .mapNotNull { dbHelper.lookupWaypoint(it.uppercase()) }
        if (wps.size < 2) return null
        val first = wps.first()
        val last = wps.last()
        if (first.lat == last.lat && first.lon == last.lon) return null
        return com.airportweather.map.utils.FlightPlanUtils.calculateTrueCourse(
            first.lat, first.lon, last.lat, last.lon,
        )
    }

    /**
     * Fetches the FB forecast once for whatever reference location we have,
     * interpolates wind at every spinner altitude, and re-renders the adapter.
     * Reference location prefers GPS; falls back to selected aircraft's home
     * airport so users can plan winds before they're sitting in the cockpit.
     */
    private suspend fun fetchAltitudeWinds() {
        if (altitudeOptions.isEmpty()) return
        val (lat, lon) = resolveReferenceLocation() ?: return
        val winds = WindsAloftRepository(filesDir)
            .forecastFor(this, lat, lon, altitudeOptions) ?: return
        altitudeWinds = winds.mapValues { (_, r) -> r.direction to r.speed }

        // Rebuild adapter so labels include wind. Preserve current selection.
        val spinner = findViewById<android.widget.Spinner>(R.id.cruiseAltitudeSpinner)
        val keep = spinner.selectedItemPosition
        spinner.adapter = altitudeAdapter()
        if (keep in altitudeOptions.indices) spinner.setSelection(keep)
        // Now that wind data is available for the selected altitude, the
        // plan totals can reflect real ground speed and fuel.
        refreshPreview()
    }

    /** Best-effort lat/lon for winds lookup: GPS if granted and recent,
     *  otherwise the selected aircraft's home airport. */
    private suspend fun resolveReferenceLocation(): Pair<Double, Double>? {
        val gps = try {
            kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { cont ->
                if (androidx.core.app.ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_FINE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED &&
                    androidx.core.app.ActivityCompat.checkSelfPermission(
                        this, Manifest.permission.ACCESS_COARSE_LOCATION
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resume(null) }
            }
        } catch (_: Exception) { null }
        if (gps != null) return gps.latitude to gps.longitude

        val homeCode = AircraftSelectionStore(this)
            .resolveSelection(AircraftRepository.get(filesDir))
            ?.aircraft?.general?.homeAirport
            ?.takeIf { it.isNotBlank() } ?: return null
        val wp = com.airportweather.map.utils.AirportDatabaseHelper(this)
            .lookupWaypoint(homeCode.uppercase()) ?: return null
        return wp.lat to wp.lon
    }

    private fun populateProfileSpinner(
        spinner: android.widget.Spinner,
        aircraft: Aircraft,
        preferredProfileId: String?,
    ) {
        profilesForSelected = aircraft.profiles
        if (profilesForSelected.isEmpty()) {
            spinner.adapter = ArrayAdapter(
                this, R.layout.spinner_item_compact, listOf("(none)")
            )
            spinner.isEnabled = false
            return
        }
        spinner.isEnabled = true
        val adapter = ArrayAdapter(
            this, R.layout.spinner_item_compact,
            profilesForSelected.map { it.name.ifBlank { "(unnamed)" } },
        ).apply { setDropDownViewResource(R.layout.spinner_dropdown_item) }
        spinner.adapter = adapter
        val idx = profilesForSelected.indexOfFirst { it.id == preferredProfileId }
            .takeIf { it >= 0 }
            ?: profilesForSelected.indexOfFirst { it.id == aircraft.defaultProfileId }
                .takeIf { it >= 0 }
            ?: 0
        spinner.setSelection(idx)
    }

    override fun onResume() {
        super.onResume()
        refreshAircraftPicker()
    }

    /**
     * Best-effort winds-aloft lookup for the route's midpoint at [altitudeFt]
     * (or the aircraft's default cruise altitude when [altitudeFt] is null).
     * Returns null on any failure (no aircraft, no FB cache, no station
     * nearby) so callers can fall back to (0, 0).
     */
    private suspend fun tryFetchWindsAloft(
        rawText: String,
        currentLocation: android.location.Location?,
        altitudeFt: Int? = null,
    ): Pair<Int, Int>? {
        val selection = AircraftSelectionStore(this)
            .resolveSelection(AircraftRepository.get(filesDir)) ?: return null
        val cruiseAlt = altitudeFt
            ?: selection.aircraft.altitudes.defaultCruiseAltitude.takeIf { it > 0 }
            ?: return null

        // Mid-route lat/lon. Cheap mean of declared waypoints; fall back to
        // current location if waypoints can't be resolved.
        val dbHelper = AirportDatabaseHelper(this)
        val waypoints = rawText.trim().split("\\s+".toRegex())
            .mapNotNull { dbHelper.lookupWaypoint(it.uppercase()) }
        val midLat: Double; val midLon: Double
        when {
            waypoints.size >= 2 -> {
                midLat = (waypoints.first().lat + waypoints.last().lat) / 2.0
                midLon = (waypoints.first().lon + waypoints.last().lon) / 2.0
            }
            waypoints.size == 1 && currentLocation != null -> {
                midLat = (waypoints.first().lat + currentLocation.latitude) / 2.0
                midLon = (waypoints.first().lon + currentLocation.longitude) / 2.0
            }
            currentLocation != null -> {
                midLat = currentLocation.latitude
                midLon = currentLocation.longitude
            }
            else -> return null
        }

        val repo = WindsAloftRepository(filesDir)
        val resolved = repo.forecastFor(this, midLat, midLon, cruiseAlt) ?: return null
        Toast.makeText(
            this,
            "Wind ${resolved.direction}°/${resolved.speed} kt from ${resolved.sourceStation}",
            Toast.LENGTH_SHORT,
        ).show()
        return resolved.direction to resolved.speed
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

    /**
     * Wire up the search box and results list under the flight plan editor.
     * Searches APT/NAV/FIX tables on a 250ms debounce while the user types, and
     * appends the chosen code to the flight plan when a result is tapped.
     */
    private fun setupWaypointSearch() {
        val dbHelper = AirportDatabaseHelper(this)
        searchAdapter = WaypointSearchAdapter { result ->
            // Append the code to the flight plan editor with a single space
            // separator. Trailing space leaves a clean spot for the next waypoint.
            val current = binding.flightPlanEdit.text.toString().trimEnd()
            val sep = if (current.isEmpty()) "" else " "
            val newText = "$current$sep${result.code} "
            binding.flightPlanEdit.setText(newText)
            binding.flightPlanEdit.setSelection(newText.length)

            // Clear the search box, hide the keyboard, and clear focus so the
            // user can read the plan they've built without the keyboard covering it.
            binding.searchInput.setText("")
            searchAdapter.update(emptyList())
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
            binding.searchInput.clearFocus()
            binding.flightPlanEdit.clearFocus()
        }

        binding.searchResults.layoutManager = LinearLayoutManager(this)
        binding.searchResults.adapter = searchAdapter

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchJob?.cancel()
                val query = s?.toString().orEmpty()
                if (query.trim().length < 2) {
                    searchAdapter.update(emptyList())
                    return
                }
                // 250 ms debounce, then run the (LIKE-based) query off the main thread.
                searchJob = lifecycleScope.launch {
                    delay(250)
                    val results = withContext(Dispatchers.IO) {
                        dbHelper.searchWaypoints(query, limit = 30)
                    }
                    searchAdapter.update(results)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // push to map
    private fun sendWaypointsToMap(waypoints: List<Waypoint>) {
        val intent = Intent(this, MainActivity::class.java)
        intent.putParcelableArrayListExtra("WAYPOINTS", ArrayList(waypoints))
        Log.d("FlightPlan", "Sending ${waypoints.size} waypoints")
        for (wp in waypoints) {
            Log.d("FlightPlan", "→ ${wp.name} (${wp.lat}, ${wp.lon})")
        }
        returnToMainActivity(intent)
    }

    /**
     * Return to the existing MainActivity instead of starting a fresh one on top.
     * Without these flags the back-stack ends up with two (or more) MainActivity
     * instances, each holding its own GoogleMap + cached SUA/METAR data, which
     * doubles heap usage and OOMs the next time we parse the 9 MB SUA GeoJSON.
     */
    private fun returnToMainActivity(intent: Intent = Intent(this, MainActivity::class.java)) {
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }
}
