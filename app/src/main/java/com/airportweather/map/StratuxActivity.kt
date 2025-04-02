package com.airportweather.map

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.Executors

class StratuxActivity : AppCompatActivity() {

    private lateinit var gpsTextView: TextView
    private val client = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gpsTextView = TextView(this)
        gpsTextView.textSize = 18f
        gpsTextView.setPadding(32, 32, 32, 32)
        setContentView(gpsTextView)

        fetchGpsData()
    }

    private fun fetchGpsData() {
        executor.execute {
            try {
                val request = Request.Builder()
                    .url("http://192.168.10.1/getSituation")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)

                    val lat = json.optDouble("GPSLatitude")
                    val lon = json.optDouble("GPSLongitude")
                    val alt = json.optDouble("GPSAltitudeMSL").toInt()
                    val speed = json.optDouble("GPSGroundSpeed").toInt()
                    val vspeed = json.optDouble("GPSVerticalSpeed").toInt()
                    val quality = json.optDouble("GPSFixQuality")
                    val barotemp = json.optDouble("BaroTemperature")
                    val barotempF = (barotemp * 9/5 + 32).toInt()
                    val baropressurealt = json.optDouble("BaroPressureAltitude").toInt()
                    val time = json.optString("GPSTime")

                    val gpsText = """
                        Time: $time
                        GPS Quality: $quality
                        Stratux Temperature: $barotempF
                        LatLong: $lat, $lon
                        Altitude MSL: ${alt} ft
                        Pressure Altitude: $baropressurealt
                        Speed: ${speed}
                        Verticle Speed: ${vspeed} ft/min
                    """.trimIndent()

                    runOnUiThread {
                        gpsTextView.text = gpsText
                    }
                } else {
                    showError("HTTP error: ${response.code}")
                }

            } catch (e: Exception) {
                showError("Connection error:\n${e.message}")
            }
        }
    }

    private fun showError(message: String?) {
        runOnUiThread {
            gpsTextView.text = "Failed to get GPS data:\n$message"
        }
    }
}
