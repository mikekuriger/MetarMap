package com.airportweather.map.aircraft

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks the user's currently-selected aircraft and the profile within it that
 * the flight planner should use. Stored as IDs in SharedPreferences so the
 * choice survives app restarts and config changes; the actual data lives in
 * [AircraftRepository].
 */
class AircraftSelectionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var selectedAircraftId: String?
        get() = prefs.getString(KEY_AIRCRAFT, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_AIRCRAFT) else putString(KEY_AIRCRAFT, value)
            }.apply()
        }

    var selectedProfileId: String?
        get() = prefs.getString(KEY_PROFILE, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_PROFILE) else putString(KEY_PROFILE, value)
            }.apply()
        }

    /**
     * Convenience: resolve the currently-selected aircraft + profile against
     * [repo]. Profile falls back to the aircraft's default when nothing's
     * explicitly selected. Returns null if no aircraft is chosen or the
     * stored id has been deleted.
     */
    fun resolveSelection(repo: AircraftRepository): Selection? {
        val aircraft = selectedAircraftId?.let { repo.byId(it) } ?: return null
        val profile = aircraft.profiles.firstOrNull { it.id == selectedProfileId }
            ?: aircraft.defaultProfile
            ?: return null
        return Selection(aircraft, profile)
    }

    data class Selection(val aircraft: Aircraft, val profile: PerformanceProfile)

    companion object {
        private const val PREFS = "aircraft_selection"
        private const val KEY_AIRCRAFT = "aircraft_id"
        private const val KEY_PROFILE = "profile_id"
    }
}
