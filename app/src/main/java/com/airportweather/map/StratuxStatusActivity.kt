package com.airportweather.map

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import org.json.JSONObject

class StratuxStatusActivity : AppCompatActivity() {

    private lateinit var gpsText: TextView
    private lateinit var weatherText: TextView
    private lateinit var trafficAdapter: TrafficAdapter
    private val trafficMap = mutableMapOf<String, TrafficTarget>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_stratux_status)

        // keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gpsText = findViewById(R.id.gpsText)
        weatherText = findViewById(R.id.weatherText)

        // Traffic RecyclerView setup
        trafficAdapter = TrafficAdapter()
        val trafficRecycler = findViewById<RecyclerView>(R.id.trafficRecycler)
        trafficRecycler.layoutManager = LinearLayoutManager(this)
        trafficRecycler.adapter = trafficAdapter

        // GPS
        StratuxManager.connectToGps { gps ->
            runOnUiThread {
                gpsText.text = """
                    Latitude: ${gps.latitude}
                    Longitude: ${gps.longitude}
                    Altitude: ${gps.altitudeFt} ft
                    Speed: ${gps.speedKnots} kt
                    Heading: ${gps.heading}
                """.trimIndent()
            }
        }

        // Traffic: subscribe AFTER adapter is ready
        StratuxManager.connectToTraffic { target ->
            runOnUiThread {
                trafficMap[target.hex] = target
                trafficAdapter.updateData(trafficMap.values.toList())
            }
        }

        // Show cached traffic immediately (after adapter is ready)
        trafficMap.putAll(StratuxManager.getAllTraffic().associateBy { it.hex })
        trafficAdapter.updateData(trafficMap.values.toList())

        // Weather (still raw WebSocket)
        connectWeather()
    }

    private var weatherSocket: WebSocket? = null

    private fun connectWeather() {
        val client = OkHttpClient()
        val request = Request.Builder().url("ws://192.168.10.1/weather").build()

        weatherSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    try {
                        val formatted = JSONObject(text).toString(2)
                        weatherText.text = formatted
                    } catch (e: Exception) {
                        weatherText.text = text
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Stratux", "Weather socket error: ${t.message}")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        weatherSocket?.cancel()
        StratuxManager.disconnectGps()
    }
}
