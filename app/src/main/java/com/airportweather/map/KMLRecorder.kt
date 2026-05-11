package com.airportweather.map

import android.location.Location
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import java.io.IOException
import java.util.Locale
import kotlin.math.abs

class KMLRecorder(context: android.content.Context) {
    private var isRecordingFlag = false
    private var writer: java.io.BufferedWriter? = null
    private var fileUri: java.net.URI? = null
    private val outputDir: java.io.File = java.io.File(context.getExternalFilesDir(null), "tracks")
    private val positionHistory: java.util.LinkedList<Location> =
        java.util.LinkedList<Location>()
    private var lastLocation: Location? = null

    init {
        if (!outputDir.exists()) outputDir.mkdirs()
    }

    val isRecording: Boolean
        get() = isRecordingFlag

    fun start() {
        Log.d("KMLRecorder.kt", "start() called")

        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(java.util.Date())
        val outFile: java.io.File = java.io.File(outputDir, "$timestamp.kml")

        try {
            writer = java.io.BufferedWriter(java.io.FileWriter(outFile))
            writer!!.write(FILE_PREFIX)
            writer!!.flush()
            fileUri = outFile.toURI()
            positionHistory.clear()
            lastLocation = null
            isRecordingFlag = true
        } catch (e: IOException) {
            writer = null
            isRecordingFlag = false
            e.printStackTrace()
        }
    }

    fun stop(): java.net.URI? {
        Log.d("KMLRecorder.kt", "stop() called")
        if (writer != null) {
            try {
                // ✅ Write the individual placemarks BEFORE closing the document
                val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")

                for ((index, loc) in positionHistory.withIndex()) {
                    val timestamp = dateFormat.format(java.util.Date(loc.time))
                    val placemark = """
                    <Placemark>
                        <name>${index + 1}</name>
                        <description><![CDATA[
                            Time: $timestamp
                            Altitude: ${loc.altitude}
                            Bearing: ${loc.bearing}
                            Speed: ${loc.speed}
                            Long: ${loc.longitude}
                            Lat: ${loc.latitude}]]>
                        </description>
                        <styleUrl>#dot</styleUrl>
                        <Point>
                            <altitudeMode>absolute</altitudeMode>
                            <coordinates>${loc.longitude},${loc.latitude},${loc.altitude}</coordinates>
                        </Point>
                    </Placemark>
                """.trimIndent()

                    writer?.write(placemark)
                }

                // ✅ Now write the suffix to close the KML document properly
                writer!!.write(FILE_SUFFIX)
                writer!!.flush()
                writer!!.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            writer = null
        }
        isRecordingFlag = false
        return fileUri
    }


    fun logLocation(loc: Location?) {
        if (loc != null) {
            Log.d("KMLRecorder", "logLocation called: ${loc.latitude}, ${loc.longitude}, ${loc.altitude}")
        }

        if (writer == null || loc == null) {
            Log.d("KMLRecorder", "Aborting logLocation — writer or loc is null")
            return
        }

        // Only record if:
        // - Speed > 3 m/s (~6 knots)
        // - Significant change (altitude, bearing, or 30s timeout)
        if (loc.speed < 1.5f) return

        var shouldRecord = false

        if (lastLocation == null) {     // record a point if this is the first point
            shouldRecord = true
        } else {
            if (abs(loc.altitude - lastLocation!!.altitude) > 30) shouldRecord = true
            if (abs(loc.bearing - lastLocation!!.bearing) > 15) shouldRecord = true
            if ((loc.time - lastLocation!!.time) > 30000) shouldRecord = true
        }

        if (!shouldRecord) return

        try {
            writer?.write(
                String.format(
                    Locale.US,
                    "          %.6f,%.6f,%.1f\n",
                    loc.longitude,
                    loc.latitude,
                    loc.altitude
                )
            )
            positionHistory.add(loc)
            lastLocation = loc
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun getLatLngPath(): List<LatLng> {
        return positionHistory.map { LatLng(it.latitude, it.longitude) }
    }

    companion object {
        val FILE_PREFIX: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2">
          <Document>
            <name>Flight Path</name>
            <Style id="flightPath">
              <LineStyle>
                <color>ff0000ff</color>
                <width>4</width>
              </LineStyle>
            </Style>
            <Style id="dot">
              <IconStyle>
                <color>FDFFFFFF</color>
                <scale>0.5</scale>
                <Icon>
                  <href>root://icons/palette-4.png</href>
                </Icon>
              </IconStyle>
            </Style>
            <Placemark>
              <styleUrl>#flightPath</styleUrl>
              <LineString>
                <extrude>1</extrude>
                <altitudeMode>absolute</altitudeMode>
                <coordinates>
    """.trimIndent()

        val FILE_SUFFIX: String = """
                </coordinates>
              </LineString>
            </Placemark>
          </Document>
        </kml>
    """.trimIndent()
    }
}