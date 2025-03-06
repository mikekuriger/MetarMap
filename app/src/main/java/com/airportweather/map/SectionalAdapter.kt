package com.airportweather.map

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.recyclerview.widget.RecyclerView

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SectionalAdapter(private val sectionalList: MutableList<SectionalChart>, private val activity: DownloadActivity) :
//class SectionalAdapter(private val sectionalList: List<SectionalChart>, private val activity: DownloadActivity) :
    RecyclerView.Adapter<SectionalAdapter.ViewHolder>() {

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val fileName = intent?.getStringExtra("fileName") ?: return
            val status = intent.getStringExtra("status") ?: "Downloading..."
            val progress = intent.getIntExtra("progress", 0)

            Log.d("DownloadDebug", "Received update -> fileName: $fileName, status: $status, progress: $progress")

            // Find the matching chart
            val index = sectionalList.indexOfFirst { it.fileName == fileName }
            if (index == -1) {
                Log.e("DownloadDebug", "No match found for $fileName")
                return
            }

            val chart = sectionalList[index]
            chart.isDownloading = progress < 100
            chart.progress = progress

            Handler(Looper.getMainLooper()).post {
                val holder = activity.findViewById<RecyclerView>(R.id.recyclerView)
                    .findViewHolderForAdapterPosition(index) as? ViewHolder

                holder?.bind(chart, this@SectionalAdapter) // ✅ Use bind() instead of manually setting UI elements
                notifyItemChanged(index) // ✅ Ensure RecyclerView refreshes the correct item
            }


            /*Handler(Looper.getMainLooper()).post {
                val holder = activity.findViewById<RecyclerView>(R.id.recyclerView)
                    .findViewHolderForAdapterPosition(index) as? ViewHolder

                holder?.let {
                    it.statusText.text = status
                    it.progressBar.progress = progress

                    if (progress == 100) {
                        it.progressBar.visibility = View.GONE
                        it.downloadIcon.visibility = View.GONE
                        it.downloadingIcon.visibility = View.GONE
                        it.downloadedIcon.visibility = View.VISIBLE
                    } else {
                        it.progressBar.visibility = View.VISIBLE
                    }
                }
            }*/
            Log.d("DownloadDebug", "Updated UI -> fileName: $fileName, status: $status, progress: $progress")
        }
    }

    init {
        val filter = IntentFilter("DOWNLOAD_PROGRESS")
        activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        activity.unregisterReceiver(downloadReceiver)
        Log.d("DownloadDebug", "DownloadReceiver registered in SectionalAdapter")
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(R.id.sectional_name)
        val size: TextView = itemView.findViewById(R.id.sectional_size)
        val downloadIcon: ImageView = itemView.findViewById(R.id.download_icon)
        val downloadedIcon: ImageView = itemView.findViewById(R.id.downloaded_icon)
        val downloadingIcon: ImageView = itemView.findViewById(R.id.downloading_icon)
        val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        val statusText: TextView = itemView.findViewById(R.id.status_text)
        val sectionalSize: TextView = itemView.findViewById(R.id.sectional_size)

        fun bind(chart: SectionalChart, adapter: SectionalAdapter) {
            name.text = chart.name
            size.text = chart.totalSize

            // ✅ Update status text based on progress stage
            statusText.text = when {
                chart.progress in 1..99 -> "Downloading..."
                chart.progress == 100 && chart.terminal != null -> "Installing..."
                else -> ""
            }

            // ✅ Only update UI components, avoid full refresh
            if (chart.progress in 1..99) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = chart.progress
                downloadingIcon.visibility = View.VISIBLE
                downloadIcon.visibility = View.GONE
                downloadedIcon.visibility = View.GONE
                sectionalSize.visibility = View.GONE
                statusText.visibility = View.VISIBLE
            } else if (chart.progress == 100) {
                progressBar.visibility = View.GONE
                downloadingIcon.visibility = View.GONE
                downloadIcon.visibility = View.GONE
                downloadedIcon.visibility = View.VISIBLE
                sectionalSize.visibility = View.VISIBLE
                statusText.visibility = View.GONE
            } else {
                progressBar.visibility = View.GONE
                downloadingIcon.visibility = View.GONE
                downloadIcon.visibility = View.VISIBLE
                sectionalSize.visibility = View.VISIBLE
                downloadedIcon.visibility = View.GONE
                sectionalSize.visibility = View.VISIBLE
                statusText.visibility = View.GONE
            }

            downloadIcon.setOnClickListener {
                if (!chart.isDownloading) {
                    chart.isDownloading = true
                    adapter.notifyItemChanged(adapterPosition) // ✅ Ensure UI updates only once
                    (itemView.context as? DownloadActivity)?.downloadSectional(
                        chart,
                        itemView.context,
                        progressBar,
                        downloadIcon,
                        downloadingIcon,
                        statusText
                    )
                    Log.d("DownloadDebug", "🔥 Download started for ${chart.fileName}")
                }
            }
        }


        /*fun bind(chart: SectionalChart, adapter: SectionalAdapter) {
            name.text = chart.name
            size.text = chart.totalSize
            statusText.text = if (chart.isDownloading) "Downloading..." else ""

            downloadIcon.visibility = if (chart.isInstalled || chart.isDownloading) View.GONE else View.VISIBLE
            downloadedIcon.visibility = if (chart.isInstalled) View.VISIBLE else View.GONE
            downloadingIcon.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
            progressBar.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
            statusText.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
            sectionalSize.visibility = if (chart.isDownloading) View.GONE else View.VISIBLE

            progressBar.progress = chart.progress // ✅ Ensure progress updates visually

            // ✅ Restore onClickListener inside bind()
            downloadIcon.setOnClickListener {
                if (!chart.isDownloading) {
                    chart.isDownloading = true
                    adapter.notifyItemChanged(adapterPosition) // ✅ Ensure UI updates
                    (itemView.context as? DownloadActivity)?.downloadSectional(
                        chart,
                        itemView.context,
                        progressBar,
                        downloadIcon,
                        downloadingIcon,
                        statusText
                    )
                    Log.d("DownloadDebug", "🔥 Download triggered for ${chart.fileName}")
                }
            }
        }*/
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chart = sectionalList[position]
        holder.bind(chart, this)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.sectional_item, parent, false)
        return ViewHolder(view)
    }



    /*override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val chart = sectionalList[position]

        holder.name.text = chart.name
        holder.size.text = chart.totalSize
        holder.statusText.text = if (chart.isDownloading) "Downloading..." else ""

        holder.downloadIcon.visibility = if (chart.isInstalled || chart.isDownloading) View.GONE else View.VISIBLE
        holder.downloadedIcon.visibility = if (chart.isInstalled) View.VISIBLE else View.GONE
        holder.downloadingIcon.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
        holder.progressBar.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
        holder.statusText.visibility = if (chart.isDownloading) View.VISIBLE else View.GONE
        holder.sectionalSize.visibility = if (chart.isDownloading) View.GONE else View.VISIBLE

        holder.downloadIcon.setOnClickListener {
            if (!chart.isDownloading) {
                chart.isDownloading = true
                notifyItemChanged(position) // ✅ Update UI immediately
                activity.downloadSectional(
                    chart,
                    holder.itemView.context,
                    holder.progressBar,
                    holder.downloadIcon,
                    holder.downloadingIcon,
                    holder.statusText
                )
            }
        }
    }*/


    override fun getItemCount(): Int = sectionalList.size
}
