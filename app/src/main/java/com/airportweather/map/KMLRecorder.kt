package com.airportweather.map

import android.content.ContentValues
import android.content.Context
import android.location.Location
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import com.airportweather.map.utils.FlightPlanHolder
import com.google.android.gms.maps.model.LatLng
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Records the aircraft's GPS track as KML for later replay/analysis.
 *
 * Writes a streamable KML `LineString` while the recorder is active. The
 * file is built in app-private external storage so writes are cheap and
 * permission-free; on stop the completed file is copied to public
 * `Documents/MetarMap/tracks/` (Android 10+) so pilots can see it in
 * Files/Drive/etc. and so it survives uninstall.
 *
 * State is owned by the recorder itself — auto-start, auto-stop, and the
 * "user paused, don't auto-restart" memory all live in here so the
 * Activity doesn't have to coordinate three timers across location fixes.
 */
class KMLRecorder(private val context: Context) {

    private enum class State { IDLE, ACTIVE }

    private var state: State = State.IDLE
    private var writer: BufferedWriter? = null
    private var workingFile: File? = null
    private var pointsRecorded = 0
    private var lastFlushAt = 0L
    private var lastRecordedLocation: Location? = null

    /** Live breadcrumb path used by the on-map polyline overlay. Reset
     *  at the start of each recording; survives a stop so the last
     *  flight's track stays visible until the next one begins. */
    private val livePath: MutableList<LatLng> = mutableListOf()

    /** First time speed/VS exceeded the takeoff thresholds in the current sustain window. */
    private var takeoffCandidateStart: Long? = null

    /** First time speed dropped below the landing threshold; cleared on any breach. */
    private var landingCandidateStart: Long? = null
    private var landingAltitudeAtStart: Double? = null

    /** After a manual stop, suppress auto-start until this wall-clock time. */
    private var userPauseUntilMs: Long = 0L

    val isRecording: Boolean get() = state == State.ACTIVE

    // -- public API --------------------------------------------------------

    /** User tapped the record button while idle. Begins a recording. */
    fun startManual() {
        if (state == State.ACTIVE) return
        startInternal()
    }

    /** User tapped the record button while recording. Stops and publishes. */
    fun stopManual(): File? {
        userPauseUntilMs = System.currentTimeMillis() + USER_PAUSE_MS
        return stopInternal()
    }

    /**
     * Feed every location update through this regardless of recording state.
     * Decides whether to auto-start based on real takeoff signals (speed
     * AND vertical speed sustained), respecting the user-pause cooldown.
     */
    fun maybeAutoStart(location: Location, verticalSpeedFpm: Double) {
        if (state == State.ACTIVE) return
        if (System.currentTimeMillis() < userPauseUntilMs) return

        val speedKt = location.speed * MPS_TO_KT
        val isTakingOff = speedKt > TAKEOFF_SPEED_KT && verticalSpeedFpm > TAKEOFF_VS_FPM
        if (isTakingOff) {
            val start = takeoffCandidateStart ?: location.time.also { takeoffCandidateStart = it }
            if (location.time - start >= TAKEOFF_SUSTAIN_MS) {
                startInternal()
                takeoffCandidateStart = null
            }
        } else {
            takeoffCandidateStart = null
        }
    }

    /**
     * Feed every location update through this while recording. Detects
     * landed-and-stopped via sustained slow speed AND unchanged altitude.
     * Returns the saved file when an auto-stop fired, null otherwise.
     */
    fun maybeAutoStop(location: Location): File? {
        if (state != State.ACTIVE) return null
        val speedKt = location.speed * MPS_TO_KT

        if (speedKt < LANDING_SPEED_KT) {
            if (landingCandidateStart == null) {
                landingCandidateStart = location.time
                landingAltitudeAtStart = location.altitude
                return null
            }
            val held = location.time - landingCandidateStart!!
            val altChangeM = abs(location.altitude - (landingAltitudeAtStart ?: location.altitude))
            // Altitude is reported in meters; 30 ft ≈ 9.14 m.
            if (held >= LANDING_SUSTAIN_MS && altChangeM < 9.14) {
                val saved = stopInternal()
                landingCandidateStart = null
                landingAltitudeAtStart = null
                return saved
            }
        } else {
            landingCandidateStart = null
            landingAltitudeAtStart = null
        }
        return null
    }

    /**
     * Stream one location into the current track. Filters out fixes with
     * poor accuracy (> 50 m) and applies time/heading/altitude decimation
     * so the file isn't dominated by ~1 Hz duplicate-position rows during
     * cruise. Flushes every [FLUSH_INTERVAL_MS] so a crash mid-flight
     * recovers most of the data.
     */
    fun logLocation(location: Location) {
        if (state != State.ACTIVE) return
        val w = writer ?: return
        if (location.hasAccuracy() && location.accuracy > 50f) return
        if (!shouldRecord(location)) return

        try {
            w.write(
                String.format(
                    Locale.US,
                    "%.6f,%.6f,%.1f\n",
                    location.longitude,
                    location.latitude,
                    location.altitude,
                ),
            )
            pointsRecorded++
            lastRecordedLocation = location
            livePath += LatLng(location.latitude, location.longitude)

            val now = System.currentTimeMillis()
            if (now - lastFlushAt > FLUSH_INTERVAL_MS) {
                w.flush()
                lastFlushAt = now
            }
        } catch (e: IOException) {
            Log.w(TAG, "Write failed: ${e.message}")
        }
    }

    /** Snapshot of the live breadcrumb path for the on-map overlay. */
    fun getLatLngPath(): List<LatLng> = livePath.toList()

    // -- internals ---------------------------------------------------------

    private fun shouldRecord(loc: Location): Boolean {
        val last = lastRecordedLocation ?: return true
        if (loc.time - last.time > MAX_TIME_GAP_MS) return true
        if (abs(loc.bearing - last.bearing) > BEARING_DELTA_DEG) return true
        // Altitude in meters; ~30 ft is the smallest change worth recording.
        if (abs(loc.altitude - last.altitude) > 9.14) return true
        return false
    }

    private fun startInternal() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val label = currentFlightLabel()
        val filename = if (label != null) "${timestamp}_$label.kml" else "$timestamp.kml"

        val dir = File(context.getExternalFilesDir(null), "tracks").apply { mkdirs() }
        val file = File(dir, filename)

        try {
            val w = BufferedWriter(FileWriter(file))
            w.write(KML_PREFIX)
            w.flush()
            writer = w
            workingFile = file
            pointsRecorded = 0
            lastRecordedLocation = null
            lastFlushAt = System.currentTimeMillis()
            livePath.clear()
            state = State.ACTIVE
            Log.d(TAG, "Recording started: ${file.absolutePath}")
        } catch (e: IOException) {
            writer = null
            workingFile = null
            Log.w(TAG, "Start failed: ${e.message}")
        }
    }

    private fun stopInternal(): File? {
        if (state != State.ACTIVE) return null
        val w = writer
        val file = workingFile

        try {
            w?.write(KML_SUFFIX)
            w?.flush()
            w?.close()
        } catch (e: IOException) {
            Log.w(TAG, "Stop failed: ${e.message}")
        }

        writer = null
        workingFile = null
        state = State.IDLE

        if (file == null) return null

        // Mirror to public Documents so the pilot can see/share/keep the
        // track outside the app. Failure is logged but doesn't break the
        // recording — the original in app-private storage is intact.
        publishToDocuments(file)

        Log.d(TAG, "Recording stopped: ${file.absolutePath} ($pointsRecorded points)")
        return file
    }

    private fun publishToDocuments(srcFile: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return  // pre-Q: stays in app storage

        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, srcFile.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.google-earth.kml+xml")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/MetarMap/tracks/")
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: run {
                Log.w(TAG, "MediaStore insert returned null")
                return
            }
            resolver.openOutputStream(uri).use { out ->
                if (out == null) return
                srcFile.inputStream().use { it.copyTo(out) }
            }
            Log.d(TAG, "Published track to Documents/MetarMap/tracks/${srcFile.name}")
        } catch (e: Exception) {
            Log.w(TAG, "Publish to Documents failed: ${e.message}")
        }
    }

    /**
     * Best-effort identifier for the current flight, used in filenames.
     * Returns e.g. "KFFZ-KSAN" when a multi-leg plan is active, or null
     * when there's no plan or the endpoints are synthetic / nameless.
     */
    private fun currentFlightLabel(): String? {
        val plan = FlightPlanHolder.currentPlan ?: return null
        val first = plan.legs.firstOrNull()?.from?.name ?: return null
        val last = plan.legs.lastOrNull()?.to?.name ?: return null
        if (first.isBlank() || first == "." || last.isBlank() || last == ".") return null
        return "$first-$last"
    }

    companion object {
        private const val TAG = "KMLRecorder"

        // Auto-start (real takeoff detection)
        private const val TAKEOFF_SPEED_KT = 40.0
        private const val TAKEOFF_VS_FPM = 200.0
        private const val TAKEOFF_SUSTAIN_MS = 5_000L

        // Auto-stop (sustained slow on the ground)
        private const val LANDING_SPEED_KT = 25.0
        private const val LANDING_SUSTAIN_MS = 60_000L

        // After manual stop, don't auto-start again for this long
        private const val USER_PAUSE_MS = 5 * 60_000L

        // Point-decimation while recording
        private const val MAX_TIME_GAP_MS = 30_000L
        private const val BEARING_DELTA_DEG = 15f

        // Disk flush cadence — balances data loss on crash vs IO load
        private const val FLUSH_INTERVAL_MS = 10_000L

        // Unit conversion
        private const val MPS_TO_KT = 1.94384

        private val KML_PREFIX = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2">
              <Document>
                <name>Flight Track</name>
                <Style id="flightPath">
                  <LineStyle>
                    <color>ff0000ff</color>
                    <width>4</width>
                  </LineStyle>
                </Style>
                <Placemark>
                  <styleUrl>#flightPath</styleUrl>
                  <LineString>
                    <extrude>1</extrude>
                    <altitudeMode>absolute</altitudeMode>
                    <coordinates>
            """.trimIndent() + "\n"

        private val KML_SUFFIX = "\n" + """
                    </coordinates>
                  </LineString>
                </Placemark>
              </Document>
            </kml>
            """.trimIndent()
    }
}
