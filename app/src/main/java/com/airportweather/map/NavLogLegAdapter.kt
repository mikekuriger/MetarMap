package com.airportweather.map

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class NavLogLegAdapter(private val legs: List<NavLogLeg>) :
    RecyclerView.Adapter<NavLogLegAdapter.NavLogViewHolder>() {

    class NavLogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.legTitle)
        val distance: TextView = view.findViewById(R.id.legDistance)
        val course: TextView = view.findViewById(R.id.legCourse)
        val altitude: TextView = view.findViewById(R.id.legAltitude)
        val tas: TextView = view.findViewById(R.id.legTAS)
        val gs: TextView = view.findViewById(R.id.legGroundspeed)
        val ete: TextView = view.findViewById(R.id.legETE)
        val windspeed: TextView = view.findViewById(R.id.legWindSpeed)
        val winddirection: TextView = view.findViewById(R.id.legWindDirection)
        val wca: TextView = view.findViewById(R.id.legWCA)
        val temp: TextView = view.findViewById(R.id.legTemp)
        val fuel: TextView = view.findViewById(R.id.legFuel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NavLogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nav_leg, parent, false)
        return NavLogViewHolder(view)
    }

    override fun onBindViewHolder(holder: NavLogViewHolder, position: Int) {
        val leg = legs[position]
        holder.title.text = "Leg ${position + 1}: ${leg.from} → ${leg.to}"
        holder.distance.text = "Distance: ${leg.distanceNM}NM"
        holder.course.text = "Course: TC ${leg.trueCourse}°, MC ${leg.magneticCourse}°"
        holder.altitude.text = "Altitude: ${leg.cruisingAltitude}ft"
        holder.tas.text = "TAS: ${leg.tas}kt"
        holder.gs.text = "Groundspeed: ${leg.groundspeed}kt"
        holder.ete.text = "ETE: ${leg.ete}"
        holder.windspeed.text = "Wind Speed: ${leg.windSpeed} kt"
        holder.winddirection.text = "Wind Direction: ${leg.windDirection}°"
        holder.wca.text = "Wind Correction: ${leg.wca}°"
        holder.temp.text = "Temp: ${leg.temp}°"
        holder.fuel.text = "Fuel used: ${leg.fuelUsed} gal"
    }

    override fun getItemCount(): Int = legs.size
}
