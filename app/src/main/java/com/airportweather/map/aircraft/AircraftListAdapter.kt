package com.airportweather.map.aircraft

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.airportweather.map.R

/**
 * RecyclerView adapter for the "My Aircraft" list. Each row shows the tail
 * number + type plus a checkmark when this aircraft is the currently-selected
 * one (the one the flight planner will use).
 */
class AircraftListAdapter(
    private val onClick: (Aircraft) -> Unit,
) : RecyclerView.Adapter<AircraftListAdapter.ViewHolder>() {

    private val items = mutableListOf<Aircraft>()
    private var selectedId: String? = null

    fun update(newItems: List<Aircraft>, selectedId: String?) {
        items.clear()
        items.addAll(newItems)
        this.selectedId = selectedId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_aircraft, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val aircraft = items[position]
        holder.bind(aircraft, aircraft.id == selectedId, onClick)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tail: TextView = view.findViewById(R.id.tailText)
        private val type: TextView = view.findViewById(R.id.typeText)
        private val profile: TextView = view.findViewById(R.id.profileText)
        private val selectedIcon: ImageView = view.findViewById(R.id.selectedIcon)

        fun bind(aircraft: Aircraft, isSelected: Boolean, onClick: (Aircraft) -> Unit) {
            tail.text = aircraft.general.tailNumber.ifBlank { "(no tail #)" }
            type.text = aircraft.general.aircraftType.ifBlank { "(no type)" }
            profile.text = aircraft.defaultProfile?.name?.let { "Default profile: $it" } ?: ""
            profile.visibility = if (profile.text.isNullOrBlank()) View.GONE else View.VISIBLE
            selectedIcon.visibility = if (isSelected) View.VISIBLE else View.GONE
            itemView.setOnClickListener { onClick(aircraft) }
        }
    }
}
