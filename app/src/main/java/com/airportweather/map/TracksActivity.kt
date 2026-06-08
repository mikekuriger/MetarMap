package com.airportweather.map

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airportweather.map.databinding.ActivityTracksBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists every recorded track KML with rich per-row metadata (date,
 * distance, point count) and a per-row overflow menu offering View on
 * Map / Share / Delete. Tapping a row returns to MainActivity with the
 * selected file path so the map can draw the track polyline.
 */
class TracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTracksBinding
    private lateinit var adapter: TrackAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        adapter = TrackAdapter(
            onView = { summary -> returnSelectedTrack(summary.file) },
            onShare = { summary -> shareTrack(summary.file) },
            onDelete = { summary -> confirmAndDelete(summary) },
        )
        binding.trackList.layoutManager = LinearLayoutManager(this)
        binding.trackList.adapter = adapter

        binding.clearFromMapButton.setOnClickListener {
            // Hand the request back to MainActivity via the activity result
            // — only that activity owns the map and the polyline list. The
            // user is taken back to the map with the green tracks wiped.
            val intent = Intent().putExtra("clearLoadedTracks", true)
            setResult(RESULT_OK, intent)
            finish()
        }

        loadTracks()
    }

    private fun loadTracks() {
        val trackDir = File(getExternalFilesDir(null), "tracks")
        lifecycleScope.launch {
            val summaries = withContext(Dispatchers.IO) {
                trackDir.listFiles { f -> f.extension == "kml" }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { TrackSummaryParser.summarize(it) }
                    ?: emptyList()
            }
            adapter.submit(summaries)
            binding.emptyText.visibility = if (summaries.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun returnSelectedTrack(file: File) {
        val resultIntent = Intent()
        resultIntent.putExtra("selectedTrackFile", file.absolutePath)
        setResult(RESULT_OK, resultIntent)
        finish()
    }

    private fun shareTrack(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.google-earth.kml+xml"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share track"))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmAndDelete(summary: TrackSummary) {
        AlertDialog.Builder(this)
            .setTitle("Delete track?")
            .setMessage("${summary.displayName} (${summary.dateText}) will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                if (summary.file.delete()) {
                    adapter.remove(summary)
                    if (adapter.itemCount == 0) {
                        binding.emptyText.visibility = View.VISIBLE
                    }
                } else {
                    Toast.makeText(this, "Couldn't delete file", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class TrackAdapter(
    private val onView: (TrackSummary) -> Unit,
    private val onShare: (TrackSummary) -> Unit,
    private val onDelete: (TrackSummary) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.VH>() {

    private val items = mutableListOf<TrackSummary>()

    fun submit(newItems: List<TrackSummary>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun remove(summary: TrackSummary) {
        val idx = items.indexOf(summary)
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_track, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.trackTitle)
        private val subtitle: TextView = view.findViewById(R.id.trackSubtitle)
        private val menu: ImageButton = view.findViewById(R.id.trackMenu)

        fun bind(summary: TrackSummary) {
            title.text = summary.displayName
            val parts = mutableListOf(summary.dateText)
            if (summary.isEmpty) {
                parts += "(empty — won't render)"
            } else {
                parts += "%.1f nm".format(summary.distanceNm)
                parts += "${summary.pointCount} pts"
            }
            subtitle.text = parts.joinToString("  ·  ")

            itemView.setOnClickListener {
                if (summary.isEmpty) {
                    // An empty file would just show "no track data" on the map;
                    // surface that earlier so the user knows the row is junk.
                    showJunkOptions(summary)
                } else {
                    onView(summary)
                }
            }
            menu.setOnClickListener { showMenuFor(summary, it) }
        }

        private fun showMenuFor(summary: TrackSummary, anchor: View) {
            val popup = PopupMenu(anchor.context, anchor)
            if (!summary.isEmpty) popup.menu.add(0, MENU_VIEW, 0, "View on map")
            popup.menu.add(0, MENU_SHARE, 1, "Share")
            popup.menu.add(0, MENU_DELETE, 2, "Delete")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_VIEW -> { onView(summary); true }
                    MENU_SHARE -> { onShare(summary); true }
                    MENU_DELETE -> { onDelete(summary); true }
                    else -> false
                }
            }
            popup.show()
        }

        /** Same dialog as the per-row menu, but tap-on-row routes here for
         *  empty/broken files so the user doesn't get the "no data" toast
         *  on a useless View attempt. */
        private fun showJunkOptions(summary: TrackSummary) {
            val popup = PopupMenu(itemView.context, menu)
            popup.menu.add(0, MENU_SHARE, 0, "Share (file as-is)")
            popup.menu.add(0, MENU_DELETE, 1, "Delete")
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_SHARE -> { onShare(summary); true }
                    MENU_DELETE -> { onDelete(summary); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    companion object {
        private const val MENU_VIEW = 1
        private const val MENU_SHARE = 2
        private const val MENU_DELETE = 3
    }
}
