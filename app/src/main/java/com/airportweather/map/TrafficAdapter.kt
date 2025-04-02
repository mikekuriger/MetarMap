package com.airportweather.map

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TrafficAdapter : RecyclerView.Adapter<TrafficAdapter.ViewHolder>() {

    private val aircraftList = mutableListOf<TrafficTarget>()

    fun updateData(newList: List<TrafficTarget>) {
        Log.d("Stratux", "Updating list with ${newList.size} aircraft")
        aircraftList.clear()
        aircraftList.addAll(newList)
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tail: TextView = view.findViewById(R.id.tail)
        //val hex: TextView = view.findViewById(R.id.hex)
        val dist: TextView = view.findViewById(R.id.dist)
        val bearing: TextView = view.findViewById(R.id.bearing)
        val alt: TextView = view.findViewById(R.id.alt)
        val speed: TextView = view.findViewById(R.id.speed)
        val course: TextView = view.findViewById(R.id.course)
        //val power: TextView = view.findViewById(R.id.power)
        val age: TextView = view.findViewById(R.id.age)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_traffic, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount() = aircraftList.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val a = aircraftList[position]
        holder.tail.text = a.tail
        //holder.hex.text = a.hex
        holder.dist.text = "${a.distanceNm} nm"
        holder.bearing.text = "${a.bearing}°"
        holder.alt.text = "${a.altitudeFt} ft"
        holder.speed.text = "${a.speedKts} kt"
        holder.course.text = "${a.course}°"
        //holder.power.text = "${a.signalStrength} dB"
        holder.age.text = "${a.ageSeconds}s"
    }
}
