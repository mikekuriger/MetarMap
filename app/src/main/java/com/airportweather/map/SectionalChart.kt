package com.airportweather.map

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * UI model for a single row in the downloads list. Note that the install
 * status is derived from the installed chart's expiration and from the catalog's
 * latest series — not from a simple boolean.
 */
data class SectionalChart(
    val name: String,
    val url: String,
    val fileSize: String,
    val totalSize: String,
    /** Series this chart was installed at, or null if never installed. */
    var installedSeries: String?,
    /** Expiration string recorded at install time, or null if absent. */
    var installedExpires: String?,
    /** Latest series advertised by the catalog. */
    val latestSeries: String,
    /** End-of-validity date for the latest cycle (null if metadata unavailable). */
    val latestExpires: String?,
    var isDownloading: Boolean = false,
    /** 0-100 download progress, updated during active downloads. */
    var downloadProgress: Int = 0,
    val fileName: String,
    val terminal: TerminalChart?,
    val terminalFileName: String?,
    val hasTerminal: Boolean,
) {
    /**
     * Best-effort installed expiration. Prefers the recorded value, but for
     * legacy installs that pre-date expires tracking we derive it from the
     * catalog: same cycle as latest → use latestExpires; older cycle →
     * the new cycle's start date is exactly when the old one ended.
     */
    val effectiveInstalledExpires: String?
        get() = installedExpires
            ?: when {
                installedSeries == null -> null
                installedSeries == latestSeries -> latestExpires
                else -> latestSeries
            }

    /** Pilot-facing freshness: drives the row's text colour. */
    val expirationStatus: ExpirationStatus
        get() = expirationStatusFor(effectiveInstalledExpires)

    /**
     * What the row's primary action icon should be. A chart is "stale" — and so
     * gets the amber refresh icon — when it's already expired OR when a newer
     * cycle is published than the one currently installed. Expiration alone is
     * the most actionable signal; cycle-mismatch covers the case where the
     * server has new data even before yours runs out.
     */
    val status: InstallStatus
        get() = when {
            installedSeries == null -> InstallStatus.NOT_INSTALLED
            expirationStatus == ExpirationStatus.EXPIRED -> InstallStatus.INSTALLED_STALE
            installedSeries != latestSeries -> InstallStatus.INSTALLED_STALE
            else -> InstallStatus.INSTALLED_CURRENT
        }

    val isInstalled: Boolean get() = installedSeries != null
}

data class TerminalChart(
    val name: String,
    val url: String,
    val fileSize: String,
    var isInstalled: Boolean,
    var isDownloading: Boolean = false,
    val fileName: String,
)

enum class InstallStatus { NOT_INSTALLED, INSTALLED_CURRENT, INSTALLED_STALE }

/**
 * Bucketed time-to-expiry for visual coding. EXPIRING is the configurable
 * "warning" window — currently 7 days, matching the user's preference for a
 * week-ahead heads-up on the 56-day FAA cycle.
 */
enum class ExpirationStatus { UNKNOWN, GOOD, EXPIRING, EXPIRED }

private const val EXPIRING_SOON_DAYS = 7L

/**
 * Computes [ExpirationStatus] from a metadata-style date string (MM-dd-yyyy).
 * Returns UNKNOWN if the string is missing or unparseable — that case happens
 * for legacy installs that pre-date expires tracking, or when the metadata.json
 * fetch failed.
 */
fun expirationStatusFor(expires: String?, now: Date = Date()): ExpirationStatus {
    if (expires.isNullOrBlank()) return ExpirationStatus.UNKNOWN
    val parsed = try {
        SimpleDateFormat("MM-dd-yyyy", Locale.US).parse(expires)
    } catch (e: Exception) {
        null
    } ?: return ExpirationStatus.UNKNOWN

    val daysLeft = TimeUnit.MILLISECONDS.toDays(parsed.time - now.time)
    return when {
        daysLeft < 0 -> ExpirationStatus.EXPIRED
        daysLeft <= EXPIRING_SOON_DAYS -> ExpirationStatus.EXPIRING
        else -> ExpirationStatus.GOOD
    }
}
