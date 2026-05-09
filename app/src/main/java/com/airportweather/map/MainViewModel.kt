package com.airportweather.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds the data layer (Weather, TFR, Traffic repositories) across configuration
 * changes so rotating the device, switching themes, or re-creating the activity
 * doesn't trigger a re-download. The activity collects from the StateFlows here
 * to render markers; the ViewModel doesn't know anything about Google Maps.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val weatherRepo = WeatherRepository(application.filesDir)
    private val tfrRepo = TfrRepository(application.filesDir)
    private val trafficRepo = TrafficRepository()

    val weather: StateFlow<WeatherSnapshot> = weatherRepo.snapshot
    val tfrs: StateFlow<List<TFRFeature>> = tfrRepo.tfrs
    val traffic: StateFlow<Map<String, TrafficTarget>> = trafficRepo.targets

    private var autoRefreshJob: Job? = null

    fun loadCachedWeather() = viewModelScope.launch {
        weatherRepo.loadCached()
    }

    fun refreshWeather() = viewModelScope.launch {
        weatherRepo.refresh()
    }

    fun refreshTfrs() = viewModelScope.launch {
        tfrRepo.refresh()
    }

    fun startAutoRefresh(intervalMinutes: Long) {
        autoRefreshJob?.cancel()
        autoRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(intervalMinutes * 60 * 1000)
                if (!isActive) break
                weatherRepo.refresh()
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun startTraffic() = trafficRepo.start()
    fun stopTraffic() = trafficRepo.stop()
    fun pruneTraffic(maxAgeMillis: Long) = trafficRepo.pruneOlderThan(maxAgeMillis)

    override fun onCleared() {
        super.onCleared()
        trafficRepo.stop()
    }
}
