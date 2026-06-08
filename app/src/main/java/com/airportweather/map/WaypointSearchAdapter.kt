package com.airportweather.map

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.airportweather.map.utils.WaypointSearchResult

/**
 * Adapter for the flight-plan waypoint search results list. Tapping a row
 * inserts the waypoint's code into the flight plan via [onPick].
 */
class WaypointSearchAdapter(
    private val onPick: (WaypointSearchResult) -> Unit,
) : RecyclerView.Adapter<WaypointSearchAdapter.ViewHolder>() {

    private val items = mutableListOf<WaypointSearchResult>()

    fun update(newItems: List<WaypointSearchResult>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_waypoint_search, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position], onPick)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val typeIcon: ImageView = view.findViewById(R.id.typeIcon)
        private val codeText: TextView = view.findViewById(R.id.codeText)
        private val nameText: TextView = view.findViewById(R.id.nameText)
        private val locationText: TextView = view.findViewById(R.id.locationText)

        fun bind(result: WaypointSearchResult, onPick: (WaypointSearchResult) -> Unit) {
            codeText.text = result.code

            // Color + icon by type — matches the in-editor color scheme and
            // mirrors how the same waypoints are drawn on sectional charts
            // (plane for airports, VOR target for navaids, triangle for fixes).
            val (tint, iconRes) = when (result.type) {
                "AIRPORT" -> Color.parseColor("#66CC66") to R.drawable.ic_wp_airport
                "NAVAID" -> Color.parseColor("#66CCFF") to R.drawable.ic_wp_navaid
                "FIX" -> Color.parseColor("#FFCC66") to R.drawable.ic_wp_fix
                else -> Color.WHITE to R.drawable.ic_wp_fix
            }
            codeText.setTextColor(tint)
            typeIcon.setImageDrawable(ContextCompat.getDrawable(typeIcon.context, iconRes))
            typeIcon.setColorFilter(tint)

            nameText.text = result.name ?: ""
            nameText.visibility = if (result.name.isNullOrBlank()) View.GONE else View.VISIBLE
            locationText.text = result.location ?: ""
            locationText.visibility = if (result.location.isNullOrBlank()) View.GONE else View.VISIBLE
            itemView.setOnClickListener { onPick(result) }
        }
    }
}
