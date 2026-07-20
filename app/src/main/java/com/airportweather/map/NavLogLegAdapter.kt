package com.airportweather.map

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Renders the nav log. Each row is one [FlightLeg]; TOC and TOD show up as
 * synthetic waypoints (with name "TOC" / "TOD") in the from→to text. Row
 * background is tinted by phase so the pilot can see at a glance which
 * portions are climb and descent.
 */
class NavLogLegAdapter(private val rows: List<NavLogRow>) :
    RecyclerView.Adapter<NavLogLegAdapter.RowViewHolder>() {

    class RowViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: View = view
        val from: TextView = view.findViewById(R.id.fromWaypoint)
        val distance: TextView = view.findViewById(R.id.distance)
        val altitude: TextView = view.findViewById(R.id.altitude)
        val tas: TextView = view.findViewById(R.id.tas)
        val gs: TextView = view.findViewById(R.id.gs)
        val ete: TextView = view.findViewById(R.id.ete)
        val fuel: TextView = view.findViewById(R.id.fuelUsed)
        val heading: TextView = view.findViewById(R.id.magneticCourse)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_navlog_leg, parent, false)
        return RowViewHolder(view)
    }

    @SuppressLint("SetTextI18n")
    override fun onBindViewHolder(holder: RowViewHolder, position: Int) {
        val row = rows[position]
        val leg = row.leg
        holder.from.text = "${leg.from.name} → ${leg.to.name}"
        holder.distance.text = "%.1f".format(leg.distanceNM)
        holder.altitude.text = leg.cruisingAltitude.toString()
        holder.tas.text = leg.tas.toString()
        holder.gs.text = leg.groundspeed.toString()
        holder.ete.text = leg.ete
        holder.fuel.text = "%.1f".format(row.leg.fuelUsed)
        holder.heading.text = leg.magneticHeading.toString()

        // Subtle phase tint — pilot can see climb (cool blue) and descent
        // (warm amber) at a glance without needing extra columns.
        holder.container.setBackgroundColor(
            when (leg.phase) {
                FlightPhase.CLIMB -> Color.argb(40, 60, 140, 255)
                FlightPhase.DESCENT -> Color.argb(40, 255, 180, 50)
                FlightPhase.CRUISE -> Color.TRANSPARENT
            }
        )
    }

    override fun getItemCount(): Int = rows.size
}
