package com.airportweather.map

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Wraps StratuxManager's traffic stream into a StateFlow. Callers register once via
 * [start] and observe [targets]; the repository handles deduplication by ICAO hex
 * and pruning of stale targets.
 */
class TrafficRepository {

    private val _targets = MutableStateFlow<Map<String, TrafficTarget>>(emptyMap())
    val targets: StateFlow<Map<String, TrafficTarget>> = _targets.asStateFlow()

    private var started = false

    fun start() {
        if (started) return
        started = true
        StratuxManager.connectToTraffic { target ->
            _targets.update { current -> current + (target.hex to target) }
        }
    }

    fun stop() {
        if (!started) return
        started = false
        StratuxManager.disconnectTraffic()
        _targets.value = emptyMap()
    }

    /** Drop any target whose lastUpdated is older than the cutoff. */
    fun pruneOlderThan(maxAgeMillis: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        _targets.update { current -> current.filterValues { it.lastUpdated >= cutoff } }
    }

    fun snapshot(): Map<String, TrafficTarget> = _targets.value
}
