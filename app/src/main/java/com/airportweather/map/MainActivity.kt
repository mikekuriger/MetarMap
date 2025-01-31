package com.airportweather.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolygonOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.maps.model.Polygon
import kotlinx.serialization.*
import java.io.IOException
import java.net.HttpURLConnection
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

@Serializable
data class METAR(
    val stationId: String,       // Example: "KBUR"
    val observationTime: String, // Example: "2025-01-18T00:53:00Z"
    val latitude: Double,        // Example: 34.1996
    val longitude: Double,       // Example: -118.365
    val tempC: Double?,          // Example: 13.3
    val dewpointC: Double?,      // Example: 6.1
    val windDirDegrees: Int?,    // Example: 190
    val windSpeedKt: Int?,       // Example: 6
    val windGustKt: Int?,        // Example: null
    val visibility: String?,     // Example: "10+"
    val altimeterInHg: Double?,  // Example: 30.14
    val wxString: String?,      // Example: +SN FZFG
    val skyCover1: String?,            // Example: CLR
    val cloudBase1: Int?,       // Example: 1000
    val skyCover2: String?,            // Example: CLR
    val cloudBase2: Int?,       // Example: 1000
    val skyCover3: String?,            // Example: CLR
    val cloudBase3: Int?,       // Example: 1000
    val skyCover4: String?,            // Example: CLR
    val cloudBase4: Int?,       // Example: 1000
    val flightCategory: String?, // Example: "VFR"
    val metarType: String?,      // Example: "METAR"
    val elevationM: Int?         // Example: 221
)
@Serializable
data class TAF(
    val stationId: String,
    val visibility: String?, // Example: "6+"
    val skyCover: List<String?>, // Example: ["BKN", "SCT"]
    val cloudBase: List<Int?>, // Example: [25000, 30000]
    var flightCategory: String? = null
)
data class TFRGeometry(
    val type: String,
    val coordinates: List<List<List<Double>>>
)
data class TFRProperties(
    val notam: String,
    val type: String,
    val description: String,
    val fullDescription: String,
    val altitudeMin: String,
    val altitudeMax: String,
    val startTime: String,
    val endTime: String
)
data class TFRFeature(
    val properties: TFRProperties,
    val geometry: TFRGeometry
)

// METAR
suspend fun downloadAndUnzipMetarData(filesDir: File): File {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://aviationweather.gov/data/cache/metars.cache.csv.gz")
            val connection = url.openConnection()
            val inputStream: InputStream = GZIPInputStream(connection.getInputStream())

            val outputFile = File(filesDir, "metars.cache.csv")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                // Log success
                println("Downloaded METAR data to ${outputFile.absolutePath}, size: ${outputFile.length()} bytes")
            } else {
                throw Exception("Downloaded METAR file is empty or missing")
            }

            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Error downloading or unzipping METAR data: ${e.message}")
        }
    }
}
fun parseMetarCsv(file: File): List<METAR> {
    return file.bufferedReader().useLines { lines ->
        lines.dropWhile { it.isBlank() || !it.startsWith("raw_text") } // Skip header
            .drop(1) // Skip the "raw_text" line
            .mapNotNull { line ->
                try {
                    parseCsvLineToMETAR(line)
                } catch (e: Exception) {
                    println("Error parsing line: $line")
                    e.printStackTrace()
                    null
                }
            }
            .toList()
    }
}
fun parseCsvLineToMETAR(line: String): METAR? {
    val fields = line.split(",")
    return try {
        METAR(
            stationId = fields[1],
            observationTime = fields[2],
            latitude = fields[3].toDouble(),
            longitude = fields[4].toDouble(),
            tempC = fields[5].toDoubleOrNull(),
            dewpointC = fields[6].toDoubleOrNull(),
            windDirDegrees = fields[7].toIntOrNull(),
            windSpeedKt = fields[8].toIntOrNull(),
            windGustKt = fields[9].toIntOrNull(),
            visibility = fields[10],
            altimeterInHg = fields[11].toDoubleOrNull(),
            wxString = fields[21],
            skyCover1 = fields.getOrNull(22),
            cloudBase1 = fields.getOrNull(23)?.toIntOrNull(),
            skyCover2 = fields.getOrNull(24),
            cloudBase2 = fields.getOrNull(25)?.toIntOrNull(),
            skyCover3 = fields.getOrNull(26),
            cloudBase3 = fields.getOrNull(27)?.toIntOrNull(),
            skyCover4 = fields.getOrNull(28),
            cloudBase4 = fields.getOrNull(29)?.toIntOrNull(),
            flightCategory = fields[30],
            metarType = fields[42],
            elevationM = fields[43].toIntOrNull()
        )
    } catch (e: Exception) {
        println("Failed to parse line: $line")
        e.printStackTrace()
        null
    }
}
fun calculateMetarAge(observationTime: String?): Int? {
    if (observationTime == null) return null

    // Updated date format to handle ISO 8601 format with 'T' and 'Z'
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    dateFormat.timeZone = TimeZone.getTimeZone("UTC") // Assuming observationTime is in UTC

    return try {
        val observationDate = dateFormat.parse(observationTime)
        val currentTime = Date()

        // Calculate difference in minutes
        val differenceInMillis = currentTime.time - (observationDate?.time ?: 0)
        (differenceInMillis / (1000 * 60)).toInt()
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
// TAF
suspend fun downloadAndUnzipTafData(filesDir: File): File {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://aviationweather.gov/data/cache/tafs.cache.csv.gz")
            val connection = url.openConnection()
            //val inputStream: InputStream = connection.getInputStream()
            val inputStream: InputStream = GZIPInputStream(connection.getInputStream())

            val outputFile = File(filesDir, "tafs.cache.csv")
            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            if (outputFile.exists() && outputFile.length() > 0) {
                // Log success
                println("Downloaded TAF data to ${outputFile.absolutePath}, size: ${outputFile.length()} bytes")
            } else {
                throw Exception("Downloaded TAF file is empty or missing")
            }

            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            throw Exception("Error downloading or unzipping TAF data: ${e.message}")
        }
    }
}
fun parseTAFCsv(file: File): List<TAF> {
    return file.bufferedReader().useLines { lines ->
        lines.dropWhile { it.isBlank() || !it.startsWith("raw_text") } // Skip header
            .drop(1) // Skip the "raw_text" line
            .mapNotNull { line ->
                val fields = line.split(",")
                try {
                    val taf = TAF(
                        stationId = fields[1],                   // KBUR
                        visibility = fields.getOrNull(21),       // 6+
                        skyCover = listOf(
                            fields.getOrNull(26),
                            fields.getOrNull(29),
                            fields.getOrNull(32)
                        ),
                        cloudBase = listOf(
                            fields.getOrNull(27)?.toIntOrNull(),
                            fields.getOrNull(30)?.toIntOrNull(),
                            fields.getOrNull(33)?.toIntOrNull()
                        ),
                    )
                    taf.flightCategory = determineTAFConditions(taf)
                    taf
                } catch (e: Exception) {
                    println("Error parsing TAF line: $line")
                    e.printStackTrace()
                    null
                }
            }
            .toList()
    }
}
fun determineTAFConditions(forecast: TAF): String {
    // Parse visibility (convert "6+" to a double)
    val visibilityMiles = forecast.visibility?.replace("+", "")?.toDoubleOrNull() ?: 0.0

    // Determine the ceiling from the lowest cloud base
    val relevantCloudBases = forecast.cloudBase.filterNotNull() // Remove nulls
    val ceiling = if (relevantCloudBases.isNotEmpty()) {
        relevantCloudBases.minOrNull() ?: Int.MAX_VALUE
    } else {
        Int.MAX_VALUE // Clear skies
    }

    return when {
        ceiling > 3000 && visibilityMiles > 5 -> "VFR"
        ceiling in 1000..3000 || (visibilityMiles in 3.0..5.0) -> "MVFR"
        ceiling in 500..999 || (visibilityMiles in 1.0..2.9) -> "IFR"
        ceiling < 500 || visibilityMiles < 1.0 -> "LIFR"
        else -> "Unknown"
    }
}
// TFR
// step 1
fun copyGeoJsonFromAssets(context: Context) {
    val fileName = "tfrs.geojson"
    val destinationFile = File(context.filesDir, fileName)

    //if (!destinationFile.exists()) {
        try {
            context.assets.open(fileName).use { inputStream ->
                FileOutputStream(destinationFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            println("✅ GeoJSON file copied from assets to: ${destinationFile.absolutePath}")
        } catch (e: IOException) {
            e.printStackTrace()
            println("❌ Failed to copy GeoJSON file from assets: ${e.message}")
        }
    //} else {
       // println("✅ GeoJSON file already exists at: ${destinationFile.absolutePath}")
    //}
}

suspend fun downloadAndProcessTfrs(filesDir: File) {
    val jsonFile = downloadTfrData(filesDir)
    val xmlCacheDir = File(filesDir, "xml")
    //val geoJsonCacheDir = File(filesDir, "geojson")

    xmlCacheDir.mkdirs()
    //geoJsonCacheDir.mkdirs()

    processTfrData(jsonFile, xmlCacheDir)
}
suspend fun downloadTfrData(filesDir: File): File {
    return withContext(Dispatchers.IO) {
        try {
            val url = URL("https://raw.githubusercontent.com/airframesio/data/refs/heads/master/json/faa/tfrs.json")
//            val url = URL("https://raw.githubusercontent.com/mikekuriger/projext/refs/heads/master/tfrs.disney.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: ${connection.responseCode}")
            }

            val inputStream: InputStream = connection.inputStream
            val outputFile = File(filesDir, "tfrs.json")

            FileOutputStream(outputFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            println("Downloaded TFR JSON to ${outputFile.absolutePath}, size: ${outputFile.length()} bytes")
            outputFile
        } catch (e: Exception) {
            e.printStackTrace()
            throw IOException("Error downloading TFR JSON: ${e.message}")
        }
    }
}
suspend fun processTfrData(jsonFile: File, cacheDir: File) {
    withContext(Dispatchers.IO) {
        try {
            val jsonString = jsonFile.readText()
            val tfrsJson = JSONObject(jsonString)
            val tfrList = tfrsJson.getJSONArray("tfrs")

            println("🔍 Found ${tfrList.length()} TFRs in JSON")

            val processedTfrs = mutableListOf<JSONObject>()  // Collect all processed TFRs

            for (i in 0 until tfrList.length()) {
                val tfr = tfrList.getJSONObject(i)
                val notamId = tfr.getString("notam")
                val xmlUrl = tfr.getJSONObject("links").optString("xml", "")

                if (xmlUrl.isEmpty()) {
                    println("⚠️ Skipping $notamId: No XML link")
                    continue
                }

                val xmlFile = fetchAndCacheXml(xmlUrl, cacheDir, notamId)

                // Process circles
                val circleTfrs = convertXmlToGeoJson(xmlFile, tfr)
                processedTfrs.addAll(circleTfrs)

                // Process polygons
                val polygonTfrs = parsePolygonTFRs(xmlFile, tfr)
                processedTfrs.addAll(polygonTfrs)
            }

            if (processedTfrs.isNotEmpty()) {
                generateGeoJson(processedTfrs, cacheDir)  // Generate the final GeoJSON file
            } else {
                println("❌ No valid TFRs were processed")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            println("❌ Error processing TFR data: ${e.message}")
        }
    }
}


fun convertXmlToGeoJson(xmlFile: File, tfr: JSONObject): List<JSONObject> {
    val geoJsonList = mutableListOf<JSONObject>()
    try {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        doc.documentElement.normalize()

        val lowerBound = doc.getElementsByTagName("valDistVerLower").item(0)?.textContent ?: "0"
        val lowerUnit = doc.getElementsByTagName("uomDistVerLower").item(0)?.textContent ?: "FT"
        val upperBound = doc.getElementsByTagName("valDistVerUpper").item(0)?.textContent ?: "999"
        val upperUnit = doc.getElementsByTagName("uomDistVerUpper").item(0)?.textContent ?: "FL"

        val lowerAltitude = formatAltitude(lowerBound, lowerUnit)
        val upperAltitude = formatAltitude(upperBound, upperUnit)

        val circles = doc.getElementsByTagName("Avx")
        for (i in 0 until circles.length) {
            val node = circles.item(i) as Element
            if (node.getElementsByTagName("codeType").item(0)?.textContent == "CIR") {
                val latStr = node.getElementsByTagName("geoLat").item(0)?.textContent ?: "0"
                val lonStr = node.getElementsByTagName("geoLong").item(0)?.textContent ?: "0"
                val radiusStr = node.getElementsByTagName("valRadiusArc").item(0)?.textContent ?: "0"
                val radiusUnit = node.getElementsByTagName("uomRadiusArc").item(0)?.textContent ?: "NM"

                val lat = convertCoordinate(latStr)
                val lon = convertCoordinate(lonStr)
                val radius = convertRadius(radiusStr, radiusUnit) // Convert NM to meters

                if (lat != 0.0 && lon != 0.0 && radius > 0) {
                    val circlePolygon = generateCircle(lon, lat, radius)

                    val geoJson = JSONObject().apply {
                        put("type", "Feature")
                        put("properties", JSONObject().apply {
                            put("notam", tfr.getString("notam").replace("\\/", "/"))
                            put("facility", tfr.getString("facility"))
                            put("state", tfr.getString("state"))
                            put("type", tfr.getString("type"))
                            put("description", tfr.getString("short_description").replace("\r\n New", "").trim())
                            put("altitude", JSONObject().apply {
                                put("min", lowerAltitude)
                                put("max", upperAltitude)
                            })
                            put("start_time", tfr.optString("date_issued", ""))
                            put("end_time", tfr.optString("end_time", "Ongoing"))
                        })
                        put("geometry", JSONObject().apply {
                            put("type", "Polygon")
                            put("coordinates", JSONArray().put(circlePolygon))
                        })
                    }
                    geoJsonList.add(geoJson)
                }
            }
        }

    } catch (e: Exception) {
        e.printStackTrace()
        println("❌ Error processing XML for TFR: ${tfr.getString("notam")}")
    }
    return geoJsonList
}
fun generateCircle(lon: Double, lat: Double, radius: Double, numPoints: Int = 64): JSONArray {
    val earthRadius = 6371000.0
    val polygon = JSONArray()
    val latRad = Math.toRadians(lat)
    val lonRad = Math.toRadians(lon)

    for (i in 0..numPoints) {
        val angle = 2 * Math.PI * i / numPoints
        val latOffset = radius / earthRadius * Math.sin(angle)
        val lonOffset = radius / (earthRadius * Math.cos(latRad)) * Math.cos(angle)

        val newLat = Math.toDegrees(latRad + latOffset)
        val newLon = Math.toDegrees(lonRad + lonOffset)
        polygon.put(JSONArray().put(newLon).put(newLat))
    }
    polygon.put(polygon.get(0)) // Close the polygon
    return polygon
}
fun formatAltitude(value: String, unit: String): String {
    return when (unit) {
        "FL" -> if (value == "999") "Unlimited" else "${value.toInt() * 100}"
        "FT" -> value
        else -> value
    }
}
fun convertCoordinate(coord: String): Double {
    val degrees = coord.substring(0, coord.length - 1).toDouble()
    return if (coord.last() == 'S' || coord.last() == 'W') -degrees else degrees
}
fun convertRadius(value: String, unit: String): Double {
    val radius = value.toDoubleOrNull() ?: 0.0
    return if (unit == "NM") radius * 1852 else radius
}
fun parsePolygonTFRs(xmlFile: File, tfr: JSONObject): List<JSONObject> {
    val geoJsonList = mutableListOf<JSONObject>()

    try {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xmlFile)
        doc.documentElement.normalize()

        val lowerBound = doc.getElementsByTagName("valDistVerLower").item(0)?.textContent ?: "0"
        val lowerUnit = doc.getElementsByTagName("uomDistVerLower").item(0)?.textContent ?: "FT"
        val upperBound = doc.getElementsByTagName("valDistVerUpper").item(0)?.textContent ?: "999"
        val upperUnit = doc.getElementsByTagName("uomDistVerUpper").item(0)?.textContent ?: "FL"

        val lowerAltitude = formatAltitude(lowerBound, lowerUnit)
        val upperAltitude = formatAltitude(upperBound, upperUnit)

        val polygonsList = mutableListOf<MutableList<List<Double>>>()
        var currentPolygon = mutableListOf<List<Double>>()

        val points = doc.getElementsByTagName("Avx")
        for (j in 0 until points.length) {
            val node = points.item(j) as Element

            // Ignore circles since they are handled separately
            if (node.getElementsByTagName("codeType").item(0)?.textContent != "CIR") {
                val latStr = node.getElementsByTagName("geoLat").item(0)?.textContent ?: "0"
                val lonStr = node.getElementsByTagName("geoLong").item(0)?.textContent ?: "0"

                val lat = convertCoordinate(latStr)
                val lon = convertCoordinate(lonStr)

                if (lat != 0.0 && lon != 0.0) {
                    currentPolygon.add(listOf(lon, lat))
                }
            }

            // If this point starts a new polygon, save the previous one
            if (j > 0 && node.getElementsByTagName("geoLat").length == 0) {
                if (currentPolygon.isNotEmpty()) {
                    polygonsList.add(currentPolygon)
                    currentPolygon = mutableListOf()  // Start a new polygon
                }
            }
        }

        // Add the last polygon if not empty
        if (currentPolygon.isNotEmpty()) {
            polygonsList.add(currentPolygon)
        }

        // Ensure valid GeoJSON formatting
        for (polygon in polygonsList) {
            if (polygon.isNotEmpty()) {
                val geoJson = JSONObject().apply {
                    put("type", "Feature")
                    put("properties", JSONObject().apply {
                        put("notam", tfr.getString("notam").replace("\\/", "/"))
                        put("facility", tfr.getString("facility"))
                        put("state", tfr.getString("state"))
                        put("type", tfr.getString("type"))
                        put("description", tfr.getString("short_description").replace("\r\n New", "").trim())
                        put("altitude", JSONObject().apply {
                            put("min", lowerAltitude)
                            put("max", upperAltitude)
                        })
                        put("start_time", tfr.optString("date_issued", ""))
                        put("end_time", tfr.optString("end_time", "Ongoing"))
                    })
                    put("geometry", JSONObject().apply {
                        put("type", "Polygon")
                        put("coordinates", JSONArray().put(JSONArray(polygon))) // Properly formats as Polygon
                    })
                }
                geoJsonList.add(geoJson)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        println("❌ Error processing XML for TFR: ${tfr.getString("notam")}")
    }
    return geoJsonList
}

fun convertJSONArrayToList(jsonArray: JSONArray): List<List<Double>> {
    val list = mutableListOf<List<Double>>()
    for (i in 0 until jsonArray.length()) {
        val pointArray = jsonArray.getJSONArray(i)
        val point = listOf(pointArray.getDouble(0), pointArray.getDouble(1))  // Ensure Lon, Lat format
        list.add(point)
    }
    return list
}


suspend fun fetchAndCacheXml(xmlUrl: String, cacheDir: File, notamId: String): File {
    return withContext(Dispatchers.IO) {
        try {
            val safeNotamId = notamId.replace("/", "_")  // Replace slashes in file name
            val xmlFile = File(cacheDir, "$safeNotamId.xml")

            if (xmlFile.exists()) {
                println("Using cached XML for $notamId")
                return@withContext xmlFile
            }

            val url = URL(xmlUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: ${connection.responseCode}")
            }

            val inputStream: InputStream = connection.inputStream
            FileOutputStream(xmlFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }

            println("Downloaded XML for $notamId to ${xmlFile.absolutePath}")
            xmlFile
        } catch (e: Exception) {
            e.printStackTrace()
            throw IOException("Error downloading XML for $notamId: ${e.message}")
        }
    }
}
fun generateGeoJson(processedTfrs: List<JSONObject>, cacheDir: File): File {
    val geoJsonFile = File(cacheDir, "tfr_display.geojson")

    try {
        val geoJsonObject = JSONObject().apply {
            put("type", "FeatureCollection")
            put("features", JSONArray(processedTfrs))
        }

        geoJsonFile.writeText(geoJsonObject.toString(4))  // Pretty-print JSON
        println("✅ Saved TFR Display GeoJSON to ${geoJsonFile.absolutePath}")

    } catch (e: Exception) {
        e.printStackTrace()
        println("❌ Error generating GeoJSON: ${e.message}")
    }

    return geoJsonFile
}


// step 2
fun loadGeoJsonFile(filesDir: File): File? {
    //val geoJsonFile = File(filesDir, "xml/tfr_display.geojson")   // generated
    val geoJsonFile = File(filesDir, "tfrs.geojson")                // manually uploaded this is for drawing the polygons

    if (!geoJsonFile.exists()) {
        println("🚨 GeoJSON file does NOT exist at ${geoJsonFile.absolutePath}")
        return null
    }

    if (geoJsonFile.length() == 0L) {
        println("🚨 GeoJSON file is EMPTY at ${geoJsonFile.absolutePath}")
        return null
    }

    println("✅ Loaded cached GeoJSON: ${geoJsonFile.absolutePath}, size: ${geoJsonFile.length()} bytes")
    return geoJsonFile
}
fun parseTFRGeoJson(file: File): List<TFRFeature> {
    val tfrFeatures = mutableListOf<TFRFeature>()

    try {
        // Read the GeoJSON file content
        val geoJsonString = file.bufferedReader().use { it.readText() }
        val geoJsonObject = JSONObject(geoJsonString)
        val features = geoJsonObject.getJSONArray("features")

        // Iterate through features in GeoJSON
        for (i in 0 until features.length()) {
            val feature = features.getJSONObject(i)
            val geometry = feature.getJSONObject("geometry")
            val properties = feature.getJSONObject("properties")
            val type = geometry.getString("type")
            val coordinates = geometry.getJSONArray("coordinates")

            if (type == "Polygon") {
                // Parse the coordinates
                val parsedCoordinates = mutableListOf<List<List<Double>>>()
                for (j in 0 until coordinates.length()) {
                    val ring = coordinates.getJSONArray(j)
                    val parsedRing = mutableListOf<List<Double>>()

                    for (k in 0 until ring.length()) {
                        val point = ring.getJSONArray(k)
                        val lng = point.getDouble(0)
                        val lat = point.getDouble(1)
                        parsedRing.add(listOf(lng, lat))
                    }
                    parsedCoordinates.add(parsedRing)
                }

                // Create a TFRGeometry object
                val tfrGeometry = TFRGeometry(type, parsedCoordinates)

                // Extract updated properties
                val notam = properties.optString("notam", "Unknown")
                val tfrType = properties.optString("type", "Unknown")
                val description = properties.optString("description", "No description available")
                val fullDescription = properties.optString("full_description", "No details available")
                val altitudeMin = properties.optJSONObject("altitude")?.optString("min", "N/A") ?: "N/A"
                val altitudeMax = properties.optJSONObject("altitude")?.optString("max", "N/A") ?: "N/A"
                val startTime = properties.optString("start_time", "Ongoing")
                val endTime = properties.optString("end_time", "Ongoing")

                // Create TFRProperties object
                val tfrProperties = TFRProperties(
                    notam, tfrType, description, fullDescription, altitudeMin, altitudeMax, startTime, endTime
                )

                // Add to list
                tfrFeatures.add(TFRFeature(tfrProperties, tfrGeometry))
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        println("Error parsing TFR GeoJSON: ${e.message}")
    }

    return tfrFeatures
}

// Other functions...
fun getCurrentTimeLocalFormat(): String {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy h:mm a", Locale.getDefault())
    dateFormat.timeZone = TimeZone.getDefault() // Use the device's default timezone
    return dateFormat.format(Date())
}
fun calculateDensityAltitude(elevation: Int?, tempC: Double?, altimeter: Double = 29.92, dewpointC: Double? = null): Int {
    if (elevation == null || tempC == null) return 0
    // Step 1: Calculate pressure altitude
    val pressureAlt = elevation + ((29.92 - altimeter) * 1000)
    // Step 2: Calculate standard temperature at elevation (ISA)
    val standardTemp = 15.0 - (elevation * 2.0 / 1000.0)
    // Step 3: Calculate temperature deviation
    val tempDeviation = tempC - standardTemp
    // Step 4: Calculate density altitude adjustment for temperature
    val tempAdjustment = (120 * tempDeviation)
    // Step 5: Calculate humidity effect if dewpoint is provided
    val humidityAdjustment = if (dewpointC != null) {
        val actualVaporPressure = 6.11 * 10.0.pow((7.5 * dewpointC) / (237.7 + dewpointC))
        val vaporPressureEffect = actualVaporPressure * 0.125
        -vaporPressureEffect
    } else {
        0.0
    }
    // Step 6: Calculate final density altitude
    return (pressureAlt + tempAdjustment + humidityAdjustment).toInt()
}
fun calculateHumidity(tempC: Double?, dewpointC: Double?): Int {
    if (tempC == null || dewpointC == null) return 0
    val humidity = 100 * exp(
        (17.625 * dewpointC) / (dewpointC + 243.04) -
                (17.625 * tempC) / (tempC + 243.04)
    )
    return humidity.toInt()
}
fun formatClouds(metars: METAR): String {
    val cloudLayers = listOfNotNull(
        metars.skyCover1?.let { cover -> if (metars.cloudBase1 != null) "$cover ${metars.cloudBase1}'" else null },
        metars.skyCover2?.let { cover -> if (metars.cloudBase2 != null) "$cover ${metars.cloudBase2}'" else null },
        metars.skyCover3?.let { cover -> if (metars.cloudBase3 != null) "$cover ${metars.cloudBase3}'" else null },
        metars.skyCover4?.let { cover -> if (metars.cloudBase4 != null) "$cover ${metars.cloudBase4}'" else null }
    )

    return if (cloudLayers.isEmpty()) {
        "Clear skies"
    } else {
        cloudLayers.joinToString(", ")
    }
}
fun metersToFeet(meters: Int): Int = (meters * 3.28084).roundToInt()
@SuppressLint("DefaultLocale")
fun celsiusToFahrenheit(celsius: Double): Double = String.format("%.1f", celsius * 9 / 5 + 32).toDouble()

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var mMap: GoogleMap
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private var areTFRsVisible = true
    private val tfrPolygons = mutableListOf<Polygon>()
    private var areAirspacesVisible = true
    private val airspacePolygons = mutableListOf<Polygon>()

    companion object {
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showBottomProgressBar("Copying Mike's tfr data...")
        copyGeoJsonFromAssets(this)  // for manually uploading the GeoJSON file
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Initialize the map
        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    @SuppressLint("PotentialBehaviorOverride")
    override fun onMapReady(googleMap: GoogleMap) {
        mMap = googleMap
        mMap.mapType = GoogleMap.MAP_TYPE_TERRAIN // Options: NORMAL, SATELLITE, TERRAIN, HYBRID
        mMap.uiSettings.isRotateGesturesEnabled = false

        mMap.setInfoWindowAdapter(CustomInfoWindowAdapter(this))
        checkLocationPermission()
        moveToCurrentLocation()

        // **Load TFR GeoJSON**
        lifecycleScope.launch {
            try {
                // Step 1: Download and process TFRs (only new or updated ones)
                //showBottomProgressBar("Downloading tfr data...")
                //downloadAndProcessTfrs(filesDir)

                // Step 2: Load the cached GeoJSON file
                //showBottomProgressBar("Loading cached tfr data...")
                val tfrFile = loadGeoJsonFile(filesDir)

                if (tfrFile != null) {
                    //showBottomProgressBar("Parsing cached tfr data...")
                    println("✅ Parsing cached tfr data...")

                    val tfrFeatures = parseTFRGeoJson(tfrFile)
                    val jsonContent = tfrFile.readText()
                    println("✅ GeoJSON Output:\n$jsonContent")

                    drawTFRPolygons(mMap, tfrFeatures)
                } else {
                    showBottomProgressBar("No TFR data available")
                    println("🚨 No TFR data available")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
//            val tfrFile = downloadTfrData(filesDir)
//            val tfrFeatures = parseTFRGeoJson(tfrFile)
//            drawTFRPolygons(mMap, tfrFeatures)
        }

        // **Load Airspace Boundaries**
        loadAndDrawAirspace(mMap, this)

        // Initialize the toggle button
        val airspaceButton = findViewById<Button>(R.id.toggle_airspace_button)
        var isAirspaceVisible = true
        airspaceButton.setOnClickListener {
            isAirspaceVisible = !isAirspaceVisible
            toggleAirspace()
            updateButtonState(airspaceButton, isAirspaceVisible)
        }

        val tfrButton = findViewById<Button>(R.id.toggle_tfr_button)
        var isTFRVisible = true
        tfrButton.setOnClickListener {
            isTFRVisible = !isTFRVisible
            toggleTFRVisibility()
            updateButtonState(tfrButton, isTFRVisible)
        }

        mMap.setOnMarkerClickListener { marker ->
            // Move the camera to center the marker on the screen
            val cameraUpdate = CameraUpdateFactory.newLatLng(marker.position)
            mMap.animateCamera(cameraUpdate)

            // Show the info window for the clicked marker
            marker.showInfoWindow()

            // Return true to indicate that the click event is consumed
            true
        }

        // Fetch METAR data in a coroutine
//        lifecycleScope.launch {
//            try {
//                val metarFile = downloadAndUnzipMetarData(filesDir)
//                //val metarFile = downloadMetarData(filesDir)
//                val metars = parseMetarCsv(metarFile)
//                val tafFile = downloadAndUnzipTafData(filesDir)
//                val tafs = parseTAFCsv(tafFile)
//
//                if (metars.isEmpty()) {
//                    Toast.makeText(this@MainActivity, "No airports found in METAR data", Toast.LENGTH_LONG).show()
//                } else {
//                    // Update markers based on visible map area
//                    mMap.setOnCameraIdleListener {
//                        updateVisibleMarkers(metars, tafs)
//                    }
//                    // Initial rendering of markers based on the current visible map area
//                    updateVisibleMarkers(metars, tafs)
//                }
//            } catch (e: Exception) {
//                e.printStackTrace()
//                Toast.makeText(
//                    this@MainActivity,
//                    "Error fetching METAR data: ${e.message}",
//                    Toast.LENGTH_LONG
//                ).show()
//            }
//        }
    }

    private fun checkLocationPermission() {
        if (::mMap.isInitialized) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    LOCATION_PERMISSION_REQUEST_CODE
                )
            } else {
                enableMyLocation()
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                enableMyLocation()
            }
        }
    }
    private fun enableMyLocation() {
        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                mMap.isMyLocationEnabled = true
                moveToCurrentLocation()
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }
    private fun moveToCurrentLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                if (location != null) {
                    val currentLatLng = LatLng(location.latitude, location.longitude)
                    mMap.moveCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 10f))
                }
            }
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun createDotBitmap(size: Int, fillColor: Int, borderColor: Int, borderWidth: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.isAntiAlias = true

        // Draw the border
        paint.color = Color.BLACK
        if (borderColor == Color.WHITE || borderColor == fillColor) {
            canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - borderWidth + 1, paint) // small
        } else {
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint) // big
        }

        // Draw the TAF (outer circle)
        if (borderColor != Color.WHITE && borderColor != fillColor) {
            paint.color = borderColor
            canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - 1, paint)
        }

        // Draw the METAR (filled inner circle)
        paint.color = fillColor
        canvas.drawCircle(size / 2f, size / 2f, (size / 2f) - borderWidth, paint)

        return bitmap
    }
    private fun createTransparentCircle(size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.isAntiAlias = true
        paint.color = Color.TRANSPARENT
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bitmap
    }

    // Update markers based on visible map area
    private fun updateVisibleMarkers(metars: List<METAR>, tafs: List<TAF>) {
        val visibleBounds = mMap.projection.visibleRegion.latLngBounds
        //mMap.clear()

        for (metar in metars) {
            val location = LatLng(metar.latitude, metar.longitude)
            if (visibleBounds.contains(location)) {
                val dotSize = 60
                val borderWidth = 13
                // Determine the METAR circle color based on flight category
                val circleColor = when (metar.flightCategory) {
                    "VFR" -> Color.GREEN
                    "MVFR" -> Color.parseColor("#0080FF") // BLUE
                    "IFR" -> Color.RED
                    "LIFR" -> Color.parseColor("#FF00FF") // PURPLE
                    else -> Color.WHITE
                }

                // Find the corresponding TAF for this METAR station
                val taf = tafs.find { it.stationId == metar.stationId }

                // Determine the TAF border color based on forecast flight condition
                val borderColor = when (taf?.flightCategory) {
                    "VFR" -> Color.GREEN
                    "MVFR" -> Color.parseColor("#0080FF") // Blue
                    "IFR" -> Color.RED
                    "LIFR" -> Color.parseColor("#FF00FF") // Purple
                    else -> Color.WHITE // circleColor
                }

                if (circleColor == Color.WHITE) continue
                val dotBitmap = createDotBitmap(dotSize, circleColor, borderColor, borderWidth)

                // Add a visible marker with the dot icon
                mMap.addMarker(
                    MarkerOptions()
                        .position(location)
                        .icon(BitmapDescriptorFactory.fromBitmap(dotBitmap))
                        .anchor(0.5f, 0.5f)
                )
                // Add an invisible marker with a larger clickable area
                mMap.addMarker(
                    MarkerOptions()
                        .position(location)
                        .icon(BitmapDescriptorFactory.fromBitmap(createTransparentCircle(dotSize + 20)))
                        .anchor(0.5f, 0.5f) // Center the larger circle
                        .title(
                            metar.stationId + " - " + metar.flightCategory +
                                    (if (taf != null && taf.flightCategory != metar.flightCategory) " (TAF = ${taf.flightCategory})" else "")
                        )
                        .snippet(formatAirportDetails(metar))
                )
            }
        }
    }

    // Format the airport details for the marker snippet
    private fun formatAirportDetails(metars: METAR): String {
        val ageInMinutes = calculateMetarAge(metars.observationTime)
        return """
        ${getCurrentTimeLocalFormat()} (${ageInMinutes} minutes old)
        ${if (metars.windSpeedKt == 0 || metars.windSpeedKt == null) {
            "Wind: Calm"
        } else {
            "Wind: ${metars.windDirDegrees ?: "VRB"}° @ ${metars.windSpeedKt} kt" +
                    (if (metars.windGustKt != null && metars.windGustKt > 0) ", Gust ${metars.windGustKt} kt" else "")
        }}
        Visibility: ${metars.visibility ?: "N/A"} sm
        Clouds: ${formatClouds(metars)}
        Temperature: ${metars.tempC ?: 0.0}°C (${celsiusToFahrenheit(metars.tempC ?: 0.0)}°F)
        Dewpoint: ${metars.dewpointC ?: 0.0}°C (${celsiusToFahrenheit(metars.dewpointC ?: 0.0)}°F)
        Altimeter: ${metars.altimeterInHg ?: 0.0}
        Humidity: ${calculateHumidity(metars.tempC, metars.dewpointC)}%
        DA: ${
            if (metars.elevationM != null && metars.tempC != null && metars.altimeterInHg != null && metars.dewpointC != null) {
                val elevationFeet = metersToFeet(metars.elevationM)
                val humidity = calculateHumidity(metars.tempC, metars.dewpointC)
                calculateDensityAltitude(elevationFeet, metars.tempC, metars.altimeterInHg, humidity.toDouble())
            } else {
                "N/A"
            }
        }
    """.trimIndent()

    }

    private fun showBottomProgressBar(message: String) {
        val progressBar = findViewById<LinearLayout>(R.id.progress_bottom_bar)
        val progressOverlay = findViewById<FrameLayout>(R.id.progress_overlay)
        val progressMessage = findViewById<TextView>(R.id.progress_message_bottom)

        if (progressBar == null || progressMessage == null) {
            Log.e("ProgressBar", "Progress bar or message view not found!")
            return
        }

        progressMessage.text = message
        progressBar.visibility = View.VISIBLE
        progressOverlay.visibility = View.VISIBLE
    }
    private fun hideBottomProgressBar() {
        val progressBar = findViewById<LinearLayout>(R.id.progress_bottom_bar)
        val progressOverlay = findViewById<FrameLayout>(R.id.progress_overlay)
        progressBar.visibility = View.GONE
        progressOverlay.visibility = View.GONE
    }
    private fun updateButtonState(button: Button, isActive: Boolean) {
        // Change button color based on state
        if (isActive) {
            //button.setBackgroundColor(Color.DKGRAY) // Active state
            button.setBackgroundColor(Color.parseColor("#90000000")) // Active state
            button.setTextColor(Color.WHITE)
        } else {
            button.setBackgroundColor(Color.parseColor("#90000000")) // Inactive state
            button.setTextColor(Color.BLACK)
        }
    }

    private fun drawTFRPolygons(map: GoogleMap, tfrFeatures: List<TFRFeature>) {
        val dateFormat = SimpleDateFormat("EEEE, MMMM dd, yyyy", Locale.US)
        dateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val currentDate = Date()

        // Clear existing TFR polygons to avoid duplicates
        if (tfrPolygons.isNotEmpty()) {
            tfrPolygons.forEach { it.remove() }
            tfrPolygons.clear()
        }

        // Add new polygons based on the TFR features
        for (feature in tfrFeatures) {
            val coordinatesList = feature.geometry.coordinates

            // Convert coordinates to a list of LatLng objects
            for (polygon in coordinatesList) {
                //val latLngList = polygon.map { LatLng(it[1], it[0]) }
                var latLngList = polygon.map { LatLng(it[1], it[0]) }.toMutableList()
                val name = feature.properties.description

                // Deduplicate points (remove unnecessary repeats)
                val deduplicatedLatLngList = latLngList.distinct()

                // Ensure the polygon is closed
                if (deduplicatedLatLngList.isNotEmpty() && deduplicatedLatLngList.first() != deduplicatedLatLngList.last()) {
                    latLngList = deduplicatedLatLngList.toMutableList()
                    latLngList.add(deduplicatedLatLngList.first()) // Close the polygon
                }


                // Extract dates from the name field
                val regex = Regex("""(\w+, \w+ \d{1,2}, \d{4}) through (\w+, \w+ \d{1,2}, \d{4})""")
                val match = regex.find(name)
                val strokeColor: Int
                val fillColor: Int

                if (match != null) {
                    val (startDateString) = match.destructured
                    val startDate = dateFormat.parse(startDateString)
                    when {
                        currentDate.before(startDate) -> { // Future TFR
                            strokeColor = Color.argb(255, 255, 128, 0)
                            fillColor = Color.argb(64, 255, 128, 0)
                        }
                        else -> { // Current TFR
                            strokeColor = Color.argb(255, 255, 0, 0)
                            fillColor = Color.argb(64, 255, 0, 0)
                        }
                    }
                } else {
                    // No date found, assume TFR is active
                    strokeColor = Color.argb(255, 255, 0, 0)
                    fillColor = Color.argb(64, 255, 0, 0)
                }
                // Add the TFR to the map
                val mapPolygon = map.addPolygon(
                    PolygonOptions()
                        .addAll(latLngList)
                        .strokeColor(strokeColor)
                        .fillColor(fillColor)
                        .strokeWidth(2f)
                        //.strokePattern(listOf(Dash(20f), Gap(5f)))
                        .visible(areTFRsVisible)
                )
                tfrPolygons.add(mapPolygon)
            }
        }
    }
    private fun toggleTFRVisibility() {
        areTFRsVisible = !areTFRsVisible
        tfrPolygons.forEach { it.isVisible = areTFRsVisible }
        Log.d("ToggleTFR", "TFR visibility set to $areTFRsVisible")
    }
    private fun loadAndDrawAirspace(map: GoogleMap, context: Context) {
        if (airspacePolygons.isNotEmpty()) return // Skip if already loaded

        //showBottomProgressBar("Loading airspace data...")

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.resources.openRawResource(R.raw.airspace)
                val geoJsonString = inputStream.bufferedReader().use { it.readText() }
                val geoJsonObject = JSONObject(geoJsonString)
                val features = geoJsonObject.getJSONArray("features")

                val newPolygons = mutableListOf<Polygon>() // Temporary storage for polygons
                for (i in 0 until features.length()) {
                    val feature = features.getJSONObject(i)
                    val geometry = feature.getJSONObject("geometry")
                    val properties = feature.getJSONObject("properties")
                    val type = geometry.getString("type")

                    if (type == "Polygon") {
                        val coordinates = extractPolygonCoordinates(geometry.getJSONArray("coordinates"))
                        val polygonOptions = PolygonOptions().addAll(coordinates)

                        // Customize polygon style
                        val airspaceTypeCode = properties.optString("TYPE_CODE", "Unknown")
                        if (airspaceTypeCode == "MODE-C") {
                            polygonOptions.strokeColor(Color.argb(90, 200, 63, 200))
                                .strokeWidth(2f)
                        } else if (airspaceTypeCode == "CLASS") {
                            when (val airspaceClass = properties.optString("CLASS", "Unknown")) {
                                "B" -> polygonOptions.strokeColor(Color.argb(128, 0, 64, 255))
                                    //.fillColor(Color.argb(0, 0, 64, 255))
                                    .strokeWidth(8f)
                                "C" -> polygonOptions.strokeColor(Color.MAGENTA)
                                    //.fillColor(Color.argb(0, 150, 63, 150))
                                    .strokeWidth(4f)
                                "D", "E" -> polygonOptions.strokeColor(
                                    if (airspaceClass == "D") Color.parseColor("#0080FF") else Color.parseColor(
                                        "#863F67"
                                    )
                                ).strokeWidth(4f)
                                    .strokePattern(listOf(Dash(20f), Gap(10f)))
                                else -> polygonOptions.strokeColor(Color.TRANSPARENT)
                                    .fillColor(Color.argb(0, 0, 0, 0))
                                    .strokeWidth(1f)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            val polygon = map.addPolygon(polygonOptions)
                            polygon.isVisible = areAirspacesVisible
                            newPolygons.add(polygon)
                        }
                    }
                }

                airspacePolygons.addAll(newPolygons) // Add polygons to global list
            } catch (e: Exception) {
                Log.e("GeoJSON", "Error loading boundaries: ${e.localizedMessage}")
            } finally {
                // Dismiss the progress dialog
                withContext(Dispatchers.Main) {
                    hideBottomProgressBar()
                }
            }
        }
    }
    private fun toggleAirspace() {
        areAirspacesVisible = !areAirspacesVisible
//        airspacePolygons.forEach { polygon ->
//            polygon.isVisible = areAirspacesVisible
//        }
        airspacePolygons.forEach { it.isVisible = areAirspacesVisible }
        
        Log.d("ToggleAirspace", "Airspace visibility set to $areAirspacesVisible")
    }
    private fun extractPolygonCoordinates(coordinatesArray: JSONArray): List<LatLng> {
        val points = mutableListOf<LatLng>()
        for (j in 0 until coordinatesArray.length()) {
            val ring = coordinatesArray.getJSONArray(j)
            for (k in 0 until ring.length()) {
                val coord = ring.getJSONArray(k)
                points.add(LatLng(coord.getDouble(1), coord.getDouble(0)))
            }
        }
        return points
    }

}
