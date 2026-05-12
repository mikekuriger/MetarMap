package com.airportweather.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val suaRepo = SuaRepository(application.filesDir)
    private val trafficRepo = TrafficRepository()
    private val chartCatalogRepo = ChartCatalogRepository(application.filesDir)
    private val seriesStore = ChartSeriesStore(application)

    val weather: StateFlow<WeatherSnapshot> = weatherRepo.snapshot
    val tfrs: StateFlow<List<TFRFeature>> = tfrRepo.tfrs
    val sua: StateFlow<List<SuaFeature>> = suaRepo.sua

    // Scaffolding for the planned traffic migration: MainActivity still drives
    // StratuxManager directly; once that's moved here the activity will collect
    // [traffic] and call [startTraffic]/[stopTraffic]/[pruneTraffic] instead of
    // managing the websocket itself.
    @Suppress("unused")
    val traffic: StateFlow<Map<String, TrafficTarget>> = trafficRepo.targets

    private val _staleChartCount = MutableStateFlow(0)
    /** Number of installed charts whose series is older than the latest catalog. */
    val staleChartCount: StateFlow<Int> = _staleChartCount.asStateFlow()

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

    fun refreshSua() = viewModelScope.launch {
        suaRepo.refresh()
    }

    /**
     * Fetches the chart catalog (24h-cached) and recomputes how many installed
     * charts are stale. The activity binds [staleChartCount] to a drawer badge.
     * A chart counts as stale when it's expired OR when the catalog has a newer
     * cycle than the one currently installed.
     */
    fun refreshChartCatalog() = viewModelScope.launch {
        chartCatalogRepo.refresh()
        val catalog = chartCatalogRepo.catalog.value ?: return@launch
        var stale = 0
        fun count(fileName: String, latestSeries: String, latestExpires: String?) {
            val installed = seriesStore.installedSeries(fileName) ?: return
            val storedExpires = seriesStore.installedExpires(fileName)
            // Same fallback chain SectionalChart.effectiveInstalledExpires uses,
            // so legacy installs (no stored expires) still get classified.
            val effective = storedExpires ?: when {
                installed == latestSeries -> latestExpires
                else -> latestSeries
            }
            val isExpired = expirationStatusFor(effective) == ExpirationStatus.EXPIRED
            if (isExpired || installed != latestSeries) stale++
        }
        for (c in catalog.sectional.charts) count(c.fileName, catalog.sectional.series, catalog.sectional.expires)
        for (c in catalog.terminal.charts) count(c.fileName, catalog.terminal.series, catalog.terminal.expires)
        for (c in catalog.enroute.charts) count(c.fileName + "_IFR", catalog.enroute.series, catalog.enroute.expires)
        _staleChartCount.value = stale
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

    @Suppress("unused") // Mirrors [startAutoRefresh]; will be wired when activity
    // shutdown moves through the ViewModel instead of MainActivity.onDestroy.
    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    // Planned-migration helpers — see comment on [traffic] above.
    @Suppress("unused") fun startTraffic() = trafficRepo.start()
    @Suppress("unused") fun stopTraffic() = trafficRepo.stop()
    @Suppress("unused") fun pruneTraffic(maxAgeMillis: Long) = trafficRepo.pruneOlderThan(maxAgeMillis)

    override fun onCleared() {
        super.onCleared()
        trafficRepo.stop()
    }
}
