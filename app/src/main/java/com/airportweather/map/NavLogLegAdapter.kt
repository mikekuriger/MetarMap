package com.airportweather.map

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NavLogLegAdapter(private val legs: List<FlightLeg>) :
    RecyclerView.Adapter<NavLogLegAdapter.LegViewHolder>() {

    class LegViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val from: TextView = view.findViewById(R.id.fromWaypoint)
        val distance: TextView = view.findViewById(R.id.distance)
        val altitude: TextView = view.findViewById(R.id.altitude)
        val tas: TextView = view.findViewById(R.id.tas)
        val gs: TextView = view.findViewById(R.id.gs)
        val ete: TextView = view.findViewById(R.id.ete)
        val fuelUsed: TextView = view.findViewById(R.id.fuelUsed)
        val magneticCourse: TextView = view.findViewById(R.id.magneticCourse)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LegViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_navlog_leg, parent, false)
        return LegViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: LegViewHolder, position: Int) {
        val leg = legs[position]
        holder.from.text = "${leg.from.name} → ${leg.to.name}"
        holder.distance.text = leg.distanceNM.toString()
        holder.altitude.text = leg.cruisingAltitude.toString()
        holder.tas.text = leg.tas.toString()
        holder.gs.text = leg.groundspeed.toString()
        holder.ete.text = leg.ete
        holder.fuelUsed.text = leg.fuelUsed.toString()
        holder.magneticCourse.text = leg.magneticCourse.toString()
    }

    override fun getItemCount(): Int = legs.size
}
