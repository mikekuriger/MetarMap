package com.airportweather.map

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class SectionalAdapter(
    private val sectionalList: List<SectionalChart>,
    private val activity: DownloadActivity,
) : RecyclerView.Adapter<SectionalAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.sectional_name)
        val size: TextView = itemView.findViewById(R.id.sectional_size)
        val downloadIcon: ImageView = itemView.findViewById(R.id.download_icon)
        val downloadedIcon: ImageView = itemView.findViewById(R.id.downloaded_icon)
        val downloadingIcon: ImageView = itemView.findViewById(R.id.downloading_icon)
        val updateIcon: ImageView = itemView.findViewById(R.id.update_icon)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        val statusText: TextView = itemView.findViewById(R.id.status_text)
        val seriesText: TextView = itemView.findViewById(R.id.series_text)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.sectional_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chart = sectionalList[position]
        val status = chart.status

        holder.name.text = chart.name
        holder.size.text = chart.totalSize

        // Reset everything; only the icon matching the current state is shown.
        holder.downloadIcon.visibility = View.GONE
        holder.downloadedIcon.visibility = View.GONE
        holder.downloadingIcon.visibility = View.GONE
        holder.updateIcon.visibility = View.GONE
        holder.progressBar.visibility = View.GONE
        holder.statusText.visibility = View.GONE
        holder.seriesText.visibility = View.GONE
        holder.size.visibility = View.VISIBLE

        when {
            chart.isDownloading -> {
                holder.downloadingIcon.visibility = View.VISIBLE
                holder.progressBar.visibility = View.VISIBLE
                holder.progressBar.progress = chart.downloadProgress
                holder.statusText.visibility = View.VISIBLE
                holder.size.visibility = View.GONE
            }
            status == InstallStatus.NOT_INSTALLED -> {
                holder.downloadIcon.visibility = View.VISIBLE
            }
            status == InstallStatus.INSTALLED_CURRENT -> {
                holder.downloadedIcon.visibility = View.VISIBLE
                bindExpiresText(holder, chart, newerCycleAvailable = false)
            }
            status == InstallStatus.INSTALLED_STALE -> {
                holder.updateIcon.visibility = View.VISIBLE
                val newerCycle = chart.installedSeries != chart.latestSeries
                bindExpiresText(holder, chart, newerCycleAvailable = newerCycle)
            }
        }

        // Both icons kick off the same install flow. The download icon is for
        // a fresh install; the update icon means "you've got a stale copy —
        // replace it." Visually different signal, identical action.
        val startDownload = View.OnClickListener {
            if (chart.isDownloading) return@OnClickListener
            chart.isDownloading = true
            holder.downloadIcon.visibility = View.GONE
            holder.downloadedIcon.visibility = View.GONE
            holder.updateIcon.visibility = View.GONE
            holder.downloadingIcon.visibility = View.VISIBLE
            holder.progressBar.visibility = View.VISIBLE
            holder.statusText.visibility = View.VISIBLE
            holder.size.visibility = View.GONE
            activity.downloadSectional(
                chart,
                holder.progressBar,
                holder.downloadIcon,
                holder.downloadingIcon,
                holder.statusText,
            )
        }
        holder.downloadIcon.setOnClickListener(startDownload)
        holder.updateIcon.setOnClickListener(startDownload)
    }

    /**
     * Renders the expiration line, choosing colour by [ExpirationStatus]:
     *   GOOD     → green   "Expires 01-22-2026"
     *   EXPIRING → amber   "Expires 01-22-2026 (≤7 days)"
     *   EXPIRED  → red     "Expired 01-22-2026"
     *   UNKNOWN  → gray    "Expiration unknown" / fallback to cycle date
     * When [newerCycleAvailable] is true, appends a hint about the new cycle.
     */
    private fun bindExpiresText(
        holder: ViewHolder,
        chart: SectionalChart,
        newerCycleAvailable: Boolean,
    ) {
        val ctx = holder.itemView.context
        holder.seriesText.visibility = View.VISIBLE
        val state = chart.expirationStatus
        val expires = chart.effectiveInstalledExpires
        val base = when (state) {
            ExpirationStatus.GOOD -> "Expires $expires"
            ExpirationStatus.EXPIRING -> "Expires $expires (soon)"
            ExpirationStatus.EXPIRED -> "Expired $expires"
            ExpirationStatus.UNKNOWN -> expires?.let { "Expires $it" }
                ?: chart.installedSeries?.let { "Cycle $it" }
                ?: ""
        }
        val suffix = if (newerCycleAvailable) "  ·  new cycle ${chart.latestSeries} available" else ""
        holder.seriesText.text = base + suffix

        val colorRes = when (state) {
            ExpirationStatus.GOOD -> R.color.expires_good
            ExpirationStatus.EXPIRING -> R.color.expires_warn
            ExpirationStatus.EXPIRED -> R.color.expires_bad
            // Unknown expiration but a newer cycle is out → amber, since
            // the user has a clear reason to act. Otherwise neutral gray.
            ExpirationStatus.UNKNOWN ->
                if (newerCycleAvailable) R.color.expires_warn else R.color.expires_unknown
        }
        holder.seriesText.setTextColor(ContextCompat.getColor(ctx, colorRes))
    }

    override fun getItemCount(): Int = sectionalList.size
}
