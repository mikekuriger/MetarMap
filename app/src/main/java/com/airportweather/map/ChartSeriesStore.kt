package com.airportweather.map

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks which FAA chart cycle (series) each downloaded zip came from. The catalog
 * publishes a `series` date per section (e.g. "11-27-2025"); when the user downloads
 * a chart we record that string here so we can later detect when a newer cycle is
 * out and prompt for a refresh.
 *
 * Older builds stored just the set of installed filenames under
 * "installed_sectionals"/"sectionals" with no series info. On first read we
 * migrate those entries to series=[UNKNOWN_SERIES], which is treated as stale —
 * so they show up as "update available" until the user re-downloads them.
 */
class ChartSeriesStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateLegacyIfNeeded()
    }

    /** Returns the series string the named file was installed at, or null if not installed. */
    fun installedSeries(fileName: String): String? =
        prefs.getString(seriesKey(fileName), null)

    /** Returns the expiration string recorded at install time, or null if absent. */
    fun installedExpires(fileName: String): String? =
        prefs.getString(expiresKey(fileName), null)

    fun isInstalled(fileName: String): Boolean = installedSeries(fileName) != null

    /**
     * Records a successful install/refresh of [fileName]. [expires] is the
     * cycle end date from metadata.json at the moment of install — stored so
     * we can show freshness/expiry status without re-fetching historical data.
     */
    fun markInstalled(fileName: String, series: String, expires: String?) {
        val editor = prefs.edit()
        editor.putString(seriesKey(fileName), series)
        if (expires.isNullOrBlank()) editor.remove(expiresKey(fileName))
        else editor.putString(expiresKey(fileName), expires)
        editor.apply()
    }

    fun markUninstalled(fileName: String) {
        prefs.edit()
            .remove(seriesKey(fileName))
            .remove(expiresKey(fileName))
            .apply()
    }

    private fun migrateLegacyIfNeeded() {
        if (prefs.getBoolean(MIGRATED_FLAG, false)) return
        val legacy = prefs.getStringSet(LEGACY_SET_KEY, emptySet()) ?: emptySet()
        val editor = prefs.edit()
        for (fileName in legacy) {
            if (!prefs.contains(seriesKey(fileName))) {
                editor.putString(seriesKey(fileName), UNKNOWN_SERIES)
            }
        }
        editor.putBoolean(MIGRATED_FLAG, true)
        editor.apply()
    }

    companion object {
        const val UNKNOWN_SERIES = "unknown"

        private const val PREFS_NAME = "installed_sectionals"
        private const val LEGACY_SET_KEY = "sectionals"
        private const val MIGRATED_FLAG = "migrated_to_per_chart_series_v1"

        private fun seriesKey(fileName: String): String = "series_$fileName"
        private fun expiresKey(fileName: String): String = "expires_$fileName"
    }
}
