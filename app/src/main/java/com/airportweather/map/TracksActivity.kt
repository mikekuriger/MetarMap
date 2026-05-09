package com.airportweather.map

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airportweather.map.databinding.ActivityTracksBinding
import java.io.File

//lateinit var sharedPrefs: SharedPreferences

class TracksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTracksBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityTracksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val trackDir = File(getExternalFilesDir(null), "tracks")
        val trackFiles = trackDir.listFiles { f -> f.extension == "kml" }?.toList() ?: emptyList()

        binding.trackList.layoutManager = LinearLayoutManager(this@TracksActivity)
        binding.trackList.adapter = TrackAdapter(trackFiles.toMutableList()) { file ->

        val resultIntent = Intent()
            resultIntent.putExtra("selectedTrackFile", file.absolutePath)
            setResult(RESULT_OK, resultIntent)
            finish()
        }
    }

class TrackAdapter(
    private val files: List<File>,
    private val onClick: (File) -> Unit
) : RecyclerView.Adapter<TrackAdapter.TrackViewHolder>() {

    inner class TrackViewHolder(val view: View) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(android.R.id.text1)

        init {
            view.setOnClickListener {
                onClick(files[adapterPosition])
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        holder.textView.text = files[position].name
    }

    override fun getItemCount() = files.size
}

}
