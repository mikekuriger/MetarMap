package com.airportweather.map.aircraft

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.airportweather.map.R

/**
 * Edits a single [PerformanceProfile] inside its owning aircraft. The aircraft
 * is identified by [EXTRA_AIRCRAFT_ID] and the profile by [EXTRA_PROFILE_ID].
 * Save merges the edited profile back into the aircraft's profile list and
 * persists via [AircraftRepository].
 */
class ProfileEditActivity : AppCompatActivity() {

    private val repo by lazy { AircraftRepository.get(filesDir) }
    private lateinit var aircraftId: String
    private lateinit var profileId: String

    private lateinit var name: EditText
    private lateinit var climbTas: EditText
    private lateinit var climbGph: EditText
    private lateinit var climbRate: EditText
    private lateinit var cruiseTas: EditText
    private lateinit var cruiseGph: EditText
    private lateinit var descentTas: EditText
    private lateinit var descentGph: EditText
    private lateinit var descentRate: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_profile_edit)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        aircraftId = intent.getStringExtra(EXTRA_AIRCRAFT_ID) ?: run { finish(); return }
        profileId = intent.getStringExtra(EXTRA_PROFILE_ID) ?: run { finish(); return }

        bindViews()
        loadProfileIntoFields()

        findViewById<Button>(R.id.saveProfileButton).setOnClickListener { save() }
    }

    private fun bindViews() {
        name = findViewById(R.id.nameField)
        climbTas = findViewById(R.id.climbTasField)
        climbGph = findViewById(R.id.climbGphField)
        climbRate = findViewById(R.id.climbRateField)
        cruiseTas = findViewById(R.id.cruiseTasField)
        cruiseGph = findViewById(R.id.cruiseGphField)
        descentTas = findViewById(R.id.descentTasField)
        descentGph = findViewById(R.id.descentGphField)
        descentRate = findViewById(R.id.descentRateField)
    }

    private fun loadProfileIntoFields() {
        val profile = repo.byId(aircraftId)?.profiles?.firstOrNull { it.id == profileId }
            ?: PerformanceProfile(id = profileId)
        name.setText(profile.name)
        climbTas.setText(if (profile.climbTas == 0) "" else profile.climbTas.toString())
        climbGph.setText(if (profile.climbGph == 0.0) "" else profile.climbGph.toString())
        climbRate.setText(if (profile.climbRate == 0) "" else profile.climbRate.toString())
        cruiseTas.setText(if (profile.cruiseTas == 0) "" else profile.cruiseTas.toString())
        cruiseGph.setText(if (profile.cruiseGph == 0.0) "" else profile.cruiseGph.toString())
        descentTas.setText(if (profile.descentTas == 0) "" else profile.descentTas.toString())
        descentGph.setText(if (profile.descentGph == 0.0) "" else profile.descentGph.toString())
        descentRate.setText(if (profile.descentRate == 0) "" else profile.descentRate.toString())
    }

    private fun save() {
        val aircraft = repo.byId(aircraftId) ?: run { finish(); return }
        val updated = PerformanceProfile(
            id = profileId,
            name = name.text.toString().trim().ifBlank { "Default" },
            climbTas = climbTas.intOrZero(),
            climbGph = climbGph.doubleOrZero(),
            climbRate = climbRate.intOrZero(),
            cruiseTas = cruiseTas.intOrZero(),
            cruiseGph = cruiseGph.doubleOrZero(),
            descentTas = descentTas.intOrZero(),
            descentGph = descentGph.doubleOrZero(),
            descentRate = descentRate.intOrZero(),
        )

        // Catch the 13-vs-130 typo class before it manifests as a 23-hour
        // cross-country estimate. Each entry is permissive enough to fit
        // anything from a J-3 Cub to a fast piston single; values outside
        // these bands almost certainly mean the user dropped a digit.
        val warnings = validateProfile(updated)
        if (warnings.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("These values look unusual")
                .setMessage(
                    warnings.joinToString("\n• ", prefix = "• ") +
                        "\n\nSave anyway, or go back and fix?"
                )
                .setPositiveButton("Save anyway") { _, _ -> persist(aircraft, updated) }
                .setNegativeButton("Go back", null)
                .show()
            return
        }
        persist(aircraft, updated)
    }

    private fun persist(aircraft: Aircraft, updated: PerformanceProfile) {
        val next = aircraft.copy(
            profiles = aircraft.profiles.map { if (it.id == profileId) updated else it }
                // If the id didn't match anything (new profile added but not yet in list),
                // append. This handles the "add profile" flow in AircraftEditActivity.
                .let { merged -> if (merged.any { it.id == profileId }) merged else merged + updated },
        )
        repo.upsert(next)
        finish()
    }

    /**
     * Returns a list of human-readable warnings for fields whose value
     * is outside the sane range for a typical GA aircraft. Empty list
     * means the profile passes. A value of 0 is treated as "not entered"
     * and skipped — partial profiles are allowed.
     */
    private fun validateProfile(p: PerformanceProfile): List<String> {
        val warnings = mutableListOf<String>()
        fun checkInt(value: Int, range: IntRange, label: String, unit: String) {
            if (value != 0 && value !in range) {
                warnings += "$label of $value $unit is outside the typical " +
                    "${range.first}–${range.last} $unit range"
            }
        }
        fun checkDouble(value: Double, range: ClosedFloatingPointRange<Double>, label: String, unit: String) {
            if (value != 0.0 && value !in range) {
                warnings += "$label of $value $unit is outside the typical " +
                    "${range.start}–${range.endInclusive} $unit range"
            }
        }
        checkInt(p.cruiseTas, 60..300, "Cruise TAS", "kt")
        checkInt(p.climbTas, 50..150, "Climb TAS", "kt")
        checkInt(p.descentTas, 60..300, "Descent TAS", "kt")
        checkDouble(p.cruiseGph, 3.0..40.0, "Cruise GPH", "gph")
        checkDouble(p.climbGph, 4.0..50.0, "Climb GPH", "gph")
        checkDouble(p.descentGph, 2.0..30.0, "Descent GPH", "gph")
        checkInt(p.climbRate, 200..3000, "Climb rate", "fpm")
        checkInt(p.descentRate, 200..3000, "Descent rate", "fpm")
        return warnings
    }

    private fun EditText.intOrZero(): Int = text.toString().toIntOrNull() ?: 0
    private fun EditText.doubleOrZero(): Double = text.toString().toDoubleOrNull() ?: 0.0

    companion object {
        const val EXTRA_AIRCRAFT_ID = "aircraft_id"
        const val EXTRA_PROFILE_ID = "profile_id"
    }
}
