package com.airportweather.map.aircraft

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airportweather.map.R
import kotlinx.coroutines.launch

/**
 * Edits one Aircraft — general fields, list of profiles, glide, altitudes,
 * weights, fuel. The list activity creates a blank aircraft and routes here;
 * Save writes the merged aircraft back to disk. Delete drops the aircraft
 * (with a confirmation dialog) and finishes.
 *
 * Profiles are owned by this aircraft. Adding/deleting/editing a profile
 * mutates the aircraft's profile list and persists immediately, so the
 * profile editor can route back here without state-merging headaches.
 */
class AircraftEditActivity : AppCompatActivity() {

    private val repo by lazy { AircraftRepository.get(filesDir) }
    private val selection by lazy { AircraftSelectionStore(this) }
    private lateinit var profilesAdapter: ProfileListAdapter

    private lateinit var aircraftId: String

    // General
    private lateinit var tail: EditText
    private lateinit var serial: EditText
    private lateinit var type: EditText
    private lateinit var color1: EditText
    private lateinit var color2: EditText
    private lateinit var category: Spinner
    private lateinit var home: EditText
    private lateinit var airspeedUnits: Spinner

    // Glide
    private lateinit var glideSpeed: EditText
    private lateinit var glideRatio: EditText

    // Altitudes
    private lateinit var defaultAlt: EditText
    private lateinit var maxCeiling: EditText

    // Weights
    private lateinit var weightUnits: Spinner
    private lateinit var bew: EditText
    private lateinit var mzfw: EditText
    private lateinit var mrw: EditText
    private lateinit var mtow: EditText
    private lateinit var mlw: EditText

    // Fuel
    private lateinit var fuelType: Spinner
    private lateinit var fuelUnits: Spinner
    private lateinit var fuelCapacity: EditText
    private lateinit var startupFuel: EditText
    private lateinit var dryOpCost: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_aircraft_edit)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        aircraftId = intent.getStringExtra(EXTRA_AIRCRAFT_ID) ?: run {
            finish()
            return
        }

        bindViews()
        setupSpinners()
        setupProfilesList()
        loadAircraftIntoFields()

        findViewById<Button>(R.id.saveButton).setOnClickListener { save(close = true) }
        findViewById<Button>(R.id.deleteButton).setOnClickListener { confirmDelete() }
        findViewById<Button>(R.id.faaLookupButton).setOnClickListener { runFaaLookup() }
    }

    /**
     * Scrapes the FAA registry for whatever's in the tail field and merges
     * the response back into the form. Only fields the FAA actually returns
     * get touched — existing user input in other fields is preserved.
     */
    private fun runFaaLookup() {
        val input = tail.text.toString().trim()
        if (input.isEmpty()) {
            Toast.makeText(this, "Enter a tail number first", Toast.LENGTH_SHORT).show()
            return
        }
        val button = findViewById<Button>(R.id.faaLookupButton)
        button.isEnabled = false
        button.text = "Looking up…"
        lifecycleScope.launch {
            val result = FaaRegistryLookup.lookup(input)
            button.isEnabled = true
            button.text = "FAA Lookup"
            if (result == null) {
                Toast.makeText(
                    this@AircraftEditActivity,
                    "No FAA record for ${input.uppercase()}",
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            tail.setText(result.tailNumber)
            result.serialNumber?.let { serial.setText(it) }
            result.displayType().takeIf { it.isNotBlank() }?.let { type.setText(it) }
            result.guessCategory()?.let { cat ->
                category.setSelection(Category.entries.indexOf(cat))
            }
            Toast.makeText(
                this@AircraftEditActivity,
                "Found: ${result.displayType()}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Profile editor may have mutated the aircraft and routed back here.
        // Re-pull the latest profile list so we render fresh names/summaries.
        loadProfilesIntoAdapter()
    }

    private fun bindViews() {
        tail = findViewById(R.id.tailField)
        serial = findViewById(R.id.serialField)
        type = findViewById(R.id.typeField)
        color1 = findViewById(R.id.color1Field)
        color2 = findViewById(R.id.color2Field)
        category = findViewById(R.id.categorySpinner)
        home = findViewById(R.id.homeField)
        airspeedUnits = findViewById(R.id.airspeedUnitsSpinner)

        glideSpeed = findViewById(R.id.glideSpeedField)
        glideRatio = findViewById(R.id.glideRatioField)

        defaultAlt = findViewById(R.id.defaultAltField)
        maxCeiling = findViewById(R.id.maxCeilingField)

        weightUnits = findViewById(R.id.weightUnitsSpinner)
        bew = findViewById(R.id.bewField)
        mzfw = findViewById(R.id.mzfwField)
        mrw = findViewById(R.id.mrwField)
        mtow = findViewById(R.id.mtowField)
        mlw = findViewById(R.id.mlwField)

        fuelType = findViewById(R.id.fuelTypeSpinner)
        fuelUnits = findViewById(R.id.fuelUnitsSpinner)
        fuelCapacity = findViewById(R.id.fuelCapacityField)
        startupFuel = findViewById(R.id.startupFuelField)
        dryOpCost = findViewById(R.id.dryOpCostField)
    }

    private fun setupSpinners() {
        category.adapter = enumAdapter(Category.entries.map { it.label })
        airspeedUnits.adapter = enumAdapter(AirspeedUnits.entries.map { it.label })
        weightUnits.adapter = enumAdapter(WeightUnits.entries.map { it.label })
        fuelType.adapter = enumAdapter(FuelType.entries.map { it.label })
        fuelUnits.adapter = enumAdapter(FuelUnits.entries.map { it.label })
    }

    private fun setupProfilesList() {
        val recycler = findViewById<RecyclerView>(R.id.profilesRecyclerView)
        recycler.layoutManager = LinearLayoutManager(this)
        profilesAdapter = ProfileListAdapter(
            onEdit = { openProfileEditor(it.id) },
            onStar = { setDefaultProfile(it.id) },
            onDelete = { confirmDeleteProfile(it) },
        )
        recycler.adapter = profilesAdapter

        findViewById<Button>(R.id.addProfileButton).setOnClickListener {
            // Save current edits first so adding a profile doesn't lose typed
            // values, then mutate the persisted aircraft to attach the new
            // profile, then route into its editor.
            save(close = false)
            val current = repo.byId(aircraftId) ?: return@setOnClickListener
            val newProfile = PerformanceProfile()
            repo.upsert(current.copy(profiles = current.profiles + newProfile))
            openProfileEditor(newProfile.id)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.aircraft.collect { _ -> loadProfilesIntoAdapter() }
            }
        }
    }

    private fun loadProfilesIntoAdapter() {
        val current = repo.byId(aircraftId) ?: return
        profilesAdapter.update(current.profiles, current.defaultProfileId ?: current.profiles.firstOrNull()?.id)
    }

    private fun loadAircraftIntoFields() {
        val a = repo.byId(aircraftId) ?: Aircraft(id = aircraftId)
        tail.setText(a.general.tailNumber)
        serial.setText(a.general.serialNumber)
        type.setText(a.general.aircraftType)
        color1.setText(a.general.primaryColor)
        color2.setText(a.general.secondaryColor)
        category.setSelection(Category.entries.indexOf(a.general.category))
        home.setText(a.general.homeAirport)
        airspeedUnits.setSelection(AirspeedUnits.entries.indexOf(a.general.airspeedUnits))

        glideSpeed.setText(if (a.glide.bestGlideSpeed == 0) "" else a.glide.bestGlideSpeed.toString())
        glideRatio.setText(if (a.glide.bestGlideRatio == 0.0) "" else a.glide.bestGlideRatio.toString())

        defaultAlt.setText(if (a.altitudes.defaultCruiseAltitude == 0) "" else a.altitudes.defaultCruiseAltitude.toString())
        maxCeiling.setText(if (a.altitudes.maxCeiling == 0) "" else a.altitudes.maxCeiling.toString())

        weightUnits.setSelection(WeightUnits.entries.indexOf(a.weights.units))
        bew.setText(if (a.weights.basicEmptyWeight == 0.0) "" else a.weights.basicEmptyWeight.toString())
        mzfw.setText(if (a.weights.maxZeroFuelWeight == 0.0) "" else a.weights.maxZeroFuelWeight.toString())
        mrw.setText(if (a.weights.maxRampWeight == 0.0) "" else a.weights.maxRampWeight.toString())
        mtow.setText(if (a.weights.maxTakeoffWeight == 0.0) "" else a.weights.maxTakeoffWeight.toString())
        mlw.setText(if (a.weights.maxLandingWeight == 0.0) "" else a.weights.maxLandingWeight.toString())

        fuelType.setSelection(FuelType.entries.indexOf(a.fuel.type))
        fuelUnits.setSelection(FuelUnits.entries.indexOf(a.fuel.units))
        fuelCapacity.setText(if (a.fuel.totalUsableCapacity == 0.0) "" else a.fuel.totalUsableCapacity.toString())
        startupFuel.setText(if (a.fuel.startTaxiTakeoffFuel == 0.0) "" else a.fuel.startTaxiTakeoffFuel.toString())
        dryOpCost.setText(if (a.fuel.dryOpCostPerHr == 0.0) "" else a.fuel.dryOpCostPerHr.toString())
    }

    /**
     * Build a new Aircraft from current field values, merging in the existing
     * profiles list (profiles are mutated through their own editor, not via
     * this form).
     */
    private fun collect(): Aircraft {
        val existing = repo.byId(aircraftId) ?: Aircraft(id = aircraftId)
        return existing.copy(
            id = aircraftId,
            general = AircraftGeneral(
                tailNumber = tail.text.toString().trim(),
                serialNumber = serial.text.toString().trim(),
                aircraftType = type.text.toString().trim(),
                primaryColor = color1.text.toString().trim(),
                secondaryColor = color2.text.toString().trim(),
                category = Category.entries[category.selectedItemPosition],
                homeAirport = home.text.toString().trim().uppercase(),
                airspeedUnits = AirspeedUnits.entries[airspeedUnits.selectedItemPosition],
            ),
            glide = GlidePerformance(
                bestGlideSpeed = glideSpeed.intOrZero(),
                bestGlideRatio = glideRatio.doubleOrZero(),
            ),
            altitudes = Altitudes(
                defaultCruiseAltitude = defaultAlt.intOrZero(),
                maxCeiling = maxCeiling.intOrZero(),
            ),
            weights = Weights(
                units = WeightUnits.entries[weightUnits.selectedItemPosition],
                basicEmptyWeight = bew.doubleOrZero(),
                maxZeroFuelWeight = mzfw.doubleOrZero(),
                maxRampWeight = mrw.doubleOrZero(),
                maxTakeoffWeight = mtow.doubleOrZero(),
                maxLandingWeight = mlw.doubleOrZero(),
            ),
            fuel = Fuel(
                type = FuelType.entries[fuelType.selectedItemPosition],
                units = FuelUnits.entries[fuelUnits.selectedItemPosition],
                totalUsableCapacity = fuelCapacity.doubleOrZero(),
                startTaxiTakeoffFuel = startupFuel.doubleOrZero(),
                dryOpCostPerHr = dryOpCost.doubleOrZero(),
            ),
        )
    }

    private fun save(close: Boolean) {
        repo.upsert(collect())
        if (close) finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Delete aircraft?")
            .setMessage("This removes the aircraft and all its profiles. Cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                repo.delete(aircraftId)
                if (selection.selectedAircraftId == aircraftId) {
                    selection.selectedAircraftId = null
                    selection.selectedProfileId = null
                }
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteProfile(profile: PerformanceProfile) {
        val current = repo.byId(aircraftId) ?: return
        if (current.profiles.size <= 1) {
            AlertDialog.Builder(this)
                .setTitle("Can't delete")
                .setMessage("An aircraft needs at least one performance profile.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete profile?")
            .setMessage("Delete '${profile.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                val next = current.copy(
                    profiles = current.profiles.filterNot { it.id == profile.id },
                    // If we're deleting the default, clear so first-remaining
                    // becomes the implicit default.
                    defaultProfileId = current.defaultProfileId?.takeUnless { it == profile.id },
                )
                repo.upsert(next)
                if (selection.selectedProfileId == profile.id) {
                    selection.selectedProfileId = next.defaultProfile?.id
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setDefaultProfile(profileId: String) {
        val current = repo.byId(aircraftId) ?: return
        repo.upsert(current.copy(defaultProfileId = profileId))
    }

    private fun openProfileEditor(profileId: String) {
        startActivity(
            Intent(this, ProfileEditActivity::class.java)
                .putExtra(ProfileEditActivity.EXTRA_AIRCRAFT_ID, aircraftId)
                .putExtra(ProfileEditActivity.EXTRA_PROFILE_ID, profileId)
        )
    }

    private fun enumAdapter(labels: List<String>): ArrayAdapter<String> =
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

    private fun EditText.intOrZero(): Int = text.toString().toIntOrNull() ?: 0
    private fun EditText.doubleOrZero(): Double = text.toString().toDoubleOrNull() ?: 0.0

    companion object {
        const val EXTRA_AIRCRAFT_ID = "aircraft_id"
    }
}
