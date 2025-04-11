package com.airportweather.map

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

object StratuxManager {

    private val client = OkHttpClient()
    private var gpsSocket: WebSocket? = null
    private var trafficSocket: WebSocket? = null

    // === GPS === (for display only right now - need to build a module to use this)
    fun connectToGps(onUpdate: (GpsData) -> Unit) {
        Log.d("Stratux", "📡 GPS connectToGps triggered")
        val request = Request.Builder()
            .url("ws://192.168.10.1/situation")
            .build()

        gpsSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("Stratux", "📡 GPS Raw: $text")  // 🔍 Log raw message first

                try {
                    val json = JSONObject(text)
                    val gps = GpsData(
                        latitude = json.optDouble("GPSLatitude"),
                        longitude = json.optDouble("GPSLongitude"),
                        altitudeFt = json.optDouble("GPSAltitudeMSL"),
                        speedKnots = json.optDouble("GPSGroundSpeed"),
                        heading = json.optDouble("GPSTrueCourse"),
                        fixQuality = json.optDouble("GPSFixQuality"),
                        satellites = json.optDouble("GPSSatellites"),
                        satellitesTracked = json.optDouble("GPSSatellitesTracked"),
                        satellitesSeen = json.optDouble("GPSSatellitesSeen"),
                        horizontalAccuracy = json.optDouble("GPSHorizontalAccuracy"),
                        verticalAccuracy = json.optDouble("GPSVerticalAccuracy"),
                        temperature = json.optDouble("BaroTemperature"),
                        pressureAltitude = json.optDouble("BaroPressureAltitude"),
                        verticalSpeed = json.optDouble("BaroVerticalSpeed")
                    )
                    Log.d("Stratux", "✅ Parsed GPS: $gps")
                    onUpdate(gps)
                } catch (e: Exception) {
                    Log.e("Stratux", "GPS parse error: ${e.message}")
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("Stratux", "✅ GPS WebSocket connected")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Stratux", "GPS socket failure: ${t.message}")
                reconnectGps(onUpdate)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w("Stratux", "GPS socket closed: $reason")
                reconnectGps(onUpdate)
            }
        })
    }
    fun reconnectGps(onUpdate: (GpsData) -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({
            connectToGps(onUpdate)
        }, 2000)
    }
    fun disconnectGps() {
        gpsSocket?.close(1000, "User exit")
        gpsSocket = null
    }

    // === TRAFFIC ===
    private val trafficSubscribers = mutableListOf<(TrafficTarget) -> Unit>()
    private val trafficMap = mutableMapOf<String, TrafficTarget>()
    private var trafficConnected = false
    fun connectToTraffic(onUpdate: (TrafficTarget) -> Unit) {
        Log.d("Stratux", "📡 Traffic connectToTraffic triggered")

        trafficSubscribers += onUpdate

        // 🔥 Immediately send current list to the new subscriber
        trafficMap.values.forEach { target ->
            Handler(Looper.getMainLooper()).post {
                onUpdate(target)
            }
        }

        if (trafficConnected) return  // Already connected

        val url = "ws://192.168.10.1/traffic"
        val request = Request.Builder().url(url).build()

        trafficSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i("Stratux", "WebSocket opened to $url")
                trafficConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (!text.trim().startsWith("{")) return

                    val obj = JSONObject(text)
                    val icao = obj.optString("Icao_addr")
                    val posValid = obj.optBoolean("Position_valid", false)
                    if (!posValid) return

                    val now = System.currentTimeMillis()
                    val target = TrafficTarget(
                        hex = icao,
                        tail = obj.optString("Tail", "--"),
                        squawk = obj.optString("Squawk", "--"),
                        lat = obj.optDouble("Lat"),
                        lon = obj.optDouble("Lng"),
                        distanceNm = BigDecimal(obj.optDouble("Distance") / 1852.0).setScale(1, RoundingMode.HALF_UP).toDouble(),
                        bearing = obj.optDouble("Bearing", 0.0).toInt(),
                        altitudeFt = obj.optInt("Alt"),
                        verticalSpeed = obj.optInt("Vvel"),
                        speedKts = obj.optInt("Speed"),
                        course = obj.optInt("Track"),
                        signalStrength = obj.optDouble("SignalLevel"),
                        ageSeconds = obj.optDouble("Age"),
                        positionValid = posValid,
                        lastUpdated = now
                    )

                    trafficMap[icao] = target

                    Handler(Looper.getMainLooper()).post {
                        onUpdate(target)
                    }

                } catch (e: Exception) {
                    Log.e("Stratux", "Traffic parse error: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Stratux", "Traffic socket failure: ${t.message}")
                trafficConnected = false
                reconnectTraffic(onUpdate)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w("Stratux", "Traffic socket closed: $reason")
                trafficConnected = false
                reconnectTraffic(onUpdate)
            }
        })
    }
    fun reconnectTraffic(onUpdate: (TrafficTarget) -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({
            connectToTraffic(onUpdate)
        }, 2000)
    }
    fun getAllTraffic(): List<TrafficTarget> = trafficMap.values.toList()
    fun disconnectTraffic() {
        trafficSocket?.close(1000, "User exit")
        trafficSocket = null
    }

    // === CLEANUP ===
    fun disconnectAll() {
        disconnectGps()
        disconnectTraffic()
        // Add weather later
    }
}
