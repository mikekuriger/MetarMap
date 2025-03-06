package com.airportweather.map

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SectionalAdapter(private val sectionalList: List<SectionalChart>, private val activity: DownloadActivity) :
    RecyclerView.Adapter<SectionalAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.sectional_name)
        val size: TextView = itemView.findViewById(R.id.sectional_size)
        val downloadIcon: ImageView = itemView.findViewById(R.id.download_icon)
        val downloadedIcon: ImageView = itemView.findViewById(R.id.downloaded_icon)
        val downloadingIcon: ImageView = itemView.findViewById(R.id.downloading_icon)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        val statusText: TextView = itemView.findViewById(R.id.status_text)
        val sectionalSize: TextView = itemView.findViewById(R.id.sectional_size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.sectional_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chart = sectionalList[position]

        holder.name.text = chart.name
        //holder.size.text = "Size: ${chart.fileSize}"
        holder.size.text = chart.totalSize

        // Reset UI state first (to avoid reused views causing glitches)
        //holder.progressBar.visibility = View.GONE
        //holder.progressText.visibility = View.GONE
        //holder.downloadIcon.visibility = View.VISIBLE

        holder.downloadIcon.visibility = if (chart.isInstalled || chart.isDownloading) View.GONE else View.VISIBLE
        holder.downloadedIcon.visibility = if (chart.isInstalled) View.VISIBLE else View.GONE
        holder.downloadingIcon.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
        holder.progressBar.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
        holder.statusText.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
        holder.sectionalSize.visibility = if (chart.isDownloading) View.GONE else View.VISIBLE

        holder.downloadIcon.setOnClickListener {
            if (!chart.isDownloading) {    // Prevent multiple clicks triggering duplicate downloads
                chart.isDownloading = true
                holder.downloadIcon.visibility = View.GONE
                holder.progressBar.visibility = View.VISIBLE
                holder.downloadingIcon.visibility = View.VISIBLE
                holder.statusText.visibility = View.VISIBLE
                holder.sectionalSize.visibility = View.GONE
                activity.downloadSectional(
                    chart,
                    holder.progressBar,
                    holder.downloadIcon,
                    holder.downloadingIcon,
                    holder.statusText
                )
            }
        }
    }

    override fun getItemCount(): Int = sectionalList.size
}
