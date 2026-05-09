package com.airportweather.map

import android.graphics.Color
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

/**
 * Builds METAR markers and caches the BitmapDescriptors so panning the map reuses
 * a single descriptor per visual style instead of re-rendering bitmaps per marker.
 *
 * Callers update `areMetarsVisible` when the user toggles METARs and the activity
 * re-renders. Callers must call [dispose] on activity destroy to release cached
 * native bitmaps.
 */
class MarkerFactory(
    private val map: GoogleMap,
    private val detailsFormatter: (METAR) -> String,
) {
    var areMetarsVisible: Boolean = true

    private data class DotKey(val size: Int, val fillColor: Int, val borderColor: Int, val borderWidth: Int)
    private data class TextKey(val text: String, val textColor: Int, val bgColor: Int, val size: Float)
    private data class BarbKey(val speed: Int, val dir: Int?)

    private val dotDescriptorCache = mutableMapOf<DotKey, BitmapDescriptor>()
    private val textDescriptorCache = mutableMapOf<TextKey, BitmapDescriptor>()
    private val barbDescriptorCache = mutableMapOf<BarbKey, BitmapDescriptor>()

    fun dispose() {
        dotDescriptorCache.clear()
        textDescriptorCache.clear()
        barbDescriptorCache.clear()
    }

    private fun dotDescriptor(size: Int, fillColor: Int, borderColor: Int, borderWidth: Int): BitmapDescriptor =
        dotDescriptorCache.getOrPut(DotKey(size, fillColor, borderColor, borderWidth)) {
            BitmapDescriptorFactory.fromBitmap(createDotBitmap(size, fillColor, borderColor, borderWidth))
        }

    private fun textDescriptor(text: String, textColor: Int, bgColor: Int = Color.TRANSPARENT, size: Float = 40F): BitmapDescriptor =
        textDescriptorCache.getOrPut(TextKey(text, textColor, bgColor, size)) {
            BitmapDescriptorFactory.fromBitmap(createTextBitmap(text, textColor, bgColor, size))
        }

    private fun barbDescriptor(speed: Int, dir: Int?): BitmapDescriptor? {
        val key = BarbKey(speed, dir)
        barbDescriptorCache[key]?.let { return it }
        val bmp = createWindBarbBitmap(speed, dir) ?: return null
        val desc = BitmapDescriptorFactory.fromBitmap(bmp)
        barbDescriptorCache[key] = desc
        return desc
    }

    private fun titleFor(metar: METAR, taf: TAF?): String =
        "${metar.stationId} - ${metar.flightCategory}" +
            (taf?.flightCategory?.takeIf { it != metar.flightCategory }
                ?.let { " (TAF = $it)" } ?: "")

    fun createDotDescriptor(style: MarkerStyle): BitmapDescriptor =
        dotDescriptor(style.size, style.fillColor, style.borderColor, style.borderWidth)

    private fun createDotMarker(metar: METAR, taf: TAF?, location: LatLng, style: MarkerStyle): Marker? {
        if (style.fillColor == Color.WHITE) return null

        return map.addMarker(
            MarkerOptions()
                .position(location)
                .icon(createDotDescriptor(style))
                .anchor(0.5f, 0.5f)
                .visible(areMetarsVisible)
                .title(titleFor(metar, taf))
                .snippet(detailsFormatter(metar))
        )?.apply {
            tag = MetarTafData(metar, taf)
        }
    }

    fun createFlightConditionMarker(metar: METAR, taf: TAF?, location: LatLng): Marker? {
        val style = MarkerStyle(
            size = 50,
            fillColor = flightCategoryColor(metar.flightCategory),
            borderColor = taf?.flightCategory?.let { flightCategoryColor(it) } ?: Color.WHITE,
            borderWidth = 13
        )
        return createDotMarker(metar, taf, location, style)
    }

    fun createMetarDotForWindLayer(metar: METAR, location: LatLng): Marker? {
        val style = MarkerStyle(
            size = 50,
            fillColor = flightCategoryColor(metar.flightCategory),
            borderColor = Color.WHITE,
            borderWidth = 13
        )
        return createDotMarker(metar, null, location, style)
    }

    fun createWindMarker(metar: METAR, taf: TAF?, location: LatLng): Marker? {
        val windSpeed = metar.windSpeedKt ?: 0
        val windDir = metar.windDirDegrees
        if (windSpeed <= 4) return null

        val descriptor = barbDescriptor(windSpeed, windDir) ?: return null
        return map.addMarker(
            MarkerOptions()
                .position(location)
                .icon(descriptor)
                .anchor(0.5f, 0.5f)
                .visible(areMetarsVisible)
                .title(titleFor(metar, taf))
                .snippet(detailsFormatter(metar))
        )?.apply {
            tag = MetarTafData(metar, taf)
        }
    }

    fun createTemperatureMarker(metar: METAR, location: LatLng): Marker? {
        val tempC = metar.tempC ?: return null
        val tempColor = when {
            tempC >= 32 -> Color.RED
            tempC <= 2 -> Color.argb(255, 50, 100, 255)
            else -> Color.WHITE
        }
        val text = "${"%.0f".format(tempC * 9 / 5 + 32)}°F"
        val descriptor = textDescriptor(text, tempColor, Color.BLACK)
        return map.addMarker(
            MarkerOptions()
                .position(location)
                .icon(descriptor)
                .visible(areMetarsVisible)
                .anchor(0.5f, 1.3f)
        )
    }

    fun createAltimeterMarker(metar: METAR, location: LatLng): Marker? {
        val alt = metar.altimeterInHg ?: return null
        val descriptor = textDescriptor("%.2f".format(alt), Color.WHITE, Color.BLACK)
        return map.addMarker(
            MarkerOptions()
                .position(location)
                .icon(descriptor)
                .visible(areMetarsVisible)
                .anchor(0.5f, 1.3f)
        )
    }

    fun createCloudMarker(metar: METAR, location: LatLng): Marker? {
        val cover = metar.skyCover1 ?: return null
        val descriptor = textDescriptor(cover, Color.WHITE, Color.BLACK)
        return map.addMarker(
            MarkerOptions()
                .position(location)
                .icon(descriptor)
                .visible(areMetarsVisible)
                .anchor(0.5f, 1.3f)
        )
    }

    fun createCeilingMarker(metar: METAR, location: LatLng): Marker? {
        val ceiling = metar.cloudBase1 ?: return null
        val descriptor = textDescriptor(ceiling.toString(), Color.WHITE, Color.BLACK)
        return map.addMarker(
            MarkerOptions()
                .position(location)
                .icon(descriptor)
                .visible(areMetarsVisible)
                .anchor(0.5f, 1.3f)
        )
    }
}
