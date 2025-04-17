package com.airportweather.map

import android.content.Context

enum class MapStyle(val rawResId: Int) {
    APPLE(R.raw.map_style_apple),
    CLEAN(R.raw.map_style_clean),
    NOLABELS(R.raw.map_style_color_nolabels),
    MODEST(R.raw.map_style_modest),
    ASSASIN(R.raw.map_style_assasin),
    VOLCANO(R.raw.map_style_volcano),
    ELECTRIC(R.raw.map_style_dark_electric),
    XRAY(R.raw.map_style_xray),
}

object MapStyleManager {
    private const val PREF_NAME = "MapPrefs"
    private const val KEY_STYLE = "mapStyle"

    fun saveStyle(context: Context, style: MapStyle) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_STYLE, style.name).apply()
    }

    fun getStyle(context: Context): MapStyle {
        val name = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STYLE, MapStyle.APPLE.name) // default
        return MapStyle.valueOf(name!!)
    }
}
