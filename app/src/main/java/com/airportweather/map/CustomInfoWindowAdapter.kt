package com.airportweather.map

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.Marker

/*class CustomInfoWindowAdapter(private val context: Context) : GoogleMap.InfoWindowAdapter {
    override fun getInfoContents(marker: Marker): View? {
        val view = LayoutInflater.from(context).inflate(R.layout.custom_info_window, null)

        val titleView = view.findViewById<TextView>(R.id.title)
        val snippetView = view.findViewById<TextView>(R.id.snippet)

        titleView.text = marker.title
        snippetView.text = marker.snippet

        // Ensure snippet resizes dynamically
        snippetView.maxLines = 10
        snippetView.ellipsize = null

        return view
    }

    override fun getInfoWindow(marker: Marker): View? {
        return null
    }
}*/

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
