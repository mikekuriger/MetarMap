package com.airportweather.map.aircraft

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.airportweather.map.R

/**
 * Adapter for the profile sub-list inside [AircraftEditActivity]. Each row
 * shows the profile name plus a one-line cruise summary. Tap opens the
 * profile editor; the star marks the default; the trash icon deletes.
 */
class ProfileListAdapter(
    private val onEdit: (PerformanceProfile) -> Unit,
    private val onStar: (PerformanceProfile) -> Unit,
    private val onDelete: (PerformanceProfile) -> Unit,
) : RecyclerView.Adapter<ProfileListAdapter.ViewHolder>() {

    private val items = mutableListOf<PerformanceProfile>()
    private var defaultId: String? = null

    fun update(profiles: List<PerformanceProfile>, defaultId: String?) {
        items.clear()
        items.addAll(profiles)
        this.defaultId = defaultId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_profile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val profile = items[position]
        holder.bind(profile, profile.id == defaultId, onEdit, onStar, onDelete)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.profileName)
        private val summary: TextView = view.findViewById(R.id.profileSummary)
        private val star: ImageView = view.findViewById(R.id.starButton)
        private val delete: ImageView = view.findViewById(R.id.deleteProfileButton)

        fun bind(
            profile: PerformanceProfile,
            isDefault: Boolean,
            onEdit: (PerformanceProfile) -> Unit,
            onStar: (PerformanceProfile) -> Unit,
            onDelete: (PerformanceProfile) -> Unit,
        ) {
            name.text = profile.name.ifBlank { "(unnamed)" }
            summary.text = "Cruise ${profile.cruiseTas}/${profile.cruiseGph} GPH · " +
                    "Climb ${profile.climbRate} fpm · Descent ${profile.descentRate} fpm"
            star.setImageResource(
                if (isDefault) android.R.drawable.btn_star_big_on
                else android.R.drawable.btn_star_big_off
            )
            star.setOnClickListener { onStar(profile) }
            delete.setOnClickListener { onDelete(profile) }
            itemView.setOnClickListener { onEdit(profile) }
        }
    }
}
