package com.airportweather.map.aircraft

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * In-memory store for the user's aircraft, persisted as JSON under filesDir.
 *
 * The list lives in a single file (aircraft.json) — small, easy to back up or
 * sideload, and the data volume is tiny (dozens of aircraft at most for the
 * power user case). The on-disk format intentionally accepts unknown fields so
 * adding new properties later won't break older saves.
 *
 * **Process-wide singleton.** Access via [AircraftRepository.get] rather than
 * the constructor — every screen that touches aircraft data needs to share one
 * in-memory state, otherwise writes from the editor never propagate to the
 * list, the Settings summary, or the flight planner.
 */
class AircraftRepository private constructor(filesDir: File) {

    private val file: File = File(filesDir, "aircraft.json")
    private val json: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _aircraft = MutableStateFlow<List<Aircraft>>(emptyList())
    val aircraft: StateFlow<List<Aircraft>> = _aircraft.asStateFlow()

    init {
        load()
    }

    private fun load() {
        if (!file.exists() || file.length() == 0L) {
            _aircraft.value = emptyList()
            return
        }
        _aircraft.value = try {
            json.decodeFromString<List<Aircraft>>(file.readText())
        } catch (e: Exception) {
            Log.e("AircraftRepo", "Failed to parse aircraft.json: ${e.message}", e)
            emptyList()
        }
    }

    private fun persist(next: List<Aircraft>) {
        try {
            file.writeText(json.encodeToString(next))
            _aircraft.value = next
        } catch (e: Exception) {
            Log.e("AircraftRepo", "Failed to write aircraft.json: ${e.message}", e)
        }
    }

    fun byId(id: String): Aircraft? = _aircraft.value.firstOrNull { it.id == id }

    /** Inserts a new aircraft or replaces the existing one with the same [Aircraft.id]. */
    fun upsert(aircraft: Aircraft) {
        val current = _aircraft.value
        val idx = current.indexOfFirst { it.id == aircraft.id }
        val next = if (idx >= 0) current.toMutableList().also { it[idx] = aircraft }
                   else current + aircraft
        persist(next)
    }

    fun delete(aircraftId: String) {
        persist(_aircraft.value.filterNot { it.id == aircraftId })
    }

    companion object {
        @Volatile
        private var instance: AircraftRepository? = null

        /**
         * Returns the singleton, constructing it on first use against [filesDir].
         * Subsequent calls return the same instance regardless of the argument —
         * we use the app's filesDir process-wide so this is fine in practice.
         */
        fun get(filesDir: File): AircraftRepository =
            instance ?: synchronized(this) {
                instance ?: AircraftRepository(filesDir).also { instance = it }
            }
    }
}
