package com.airportweather.map

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker

class CustomInfoWindowAdapter(context: Context) : GoogleMap.InfoWindowAdapter {

    private val inflater: LayoutInflater = LayoutInflater.from(context)

    override fun getInfoWindow(marker: Marker): View? {
        // Return null to let getInfoContents handle the content
        return null
    }

    override fun getInfoContents(marker: Marker): View {
        // Inflate your custom InfoWindow layout
        val view = inflater.inflate(R.layout.custom_info_window, null)

        // Bind data to the layout's TextViews
        val titleTextView: TextView = view.findViewById(R.id.title)
        val snippetTextView: TextView = view.findViewById(R.id.snippet)

        titleTextView.text = marker.title
        snippetTextView.text = marker.snippet

        return view
    }
}