package com.airportweather.map.aircraft

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airportweather.map.R
import kotlinx.coroutines.launch

/**
 * Shows the user's saved aircraft. Tapping a row opens the editor; the
 * checkmark marks whichever aircraft the flight planner is currently using.
 * The "Add Aircraft" button creates a blank Aircraft and opens it for editing.
 */
class AircraftListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var addButton: Button
    private lateinit var adapter: AircraftListAdapter

    private val repo by lazy { AircraftRepository.get(filesDir) }
    private val selection by lazy { AircraftSelectionStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_aircraft_list)

        recyclerView = findViewById(R.id.aircraftRecyclerView)
        emptyText = findViewById(R.id.emptyText)
        addButton = findViewById(R.id.addAircraftButton)

        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AircraftListAdapter { aircraft ->
            // Tapping a row both selects it as active AND opens it for editing.
            selection.selectedAircraftId = aircraft.id
            selection.selectedProfileId = aircraft.defaultProfile?.id
            openEditor(aircraft.id)
        }
        recyclerView.adapter = adapter

        addButton.setOnClickListener { showAddDialog() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.aircraft.collect { list ->
                    adapter.update(list, selection.selectedAircraftId)
                    emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun openEditor(aircraftId: String) {
        startActivity(
            Intent(this, AircraftEditActivity::class.java)
                .putExtra(AircraftEditActivity.EXTRA_AIRCRAFT_ID, aircraftId)
        )
    }

    /**
     * Two-step add: first ask "blank or template?", then if template, show
     * the list of common types. Either way, persist and open the editor so
     * the user can tweak (tail number, weights) before flying.
     */
    private fun showAddDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add aircraft")
            .setItems(arrayOf("Start blank", "Use a template")) { _, which ->
                when (which) {
                    0 -> createBlank()
                    1 -> showTemplatePicker()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTemplatePicker() {
        val templates = AircraftTemplates.all
        val names = templates.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pick a template")
            .setItems(names) { _, which ->
                val newAircraft = templates[which].build()
                repo.upsert(newAircraft)
                openEditor(newAircraft.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createBlank() {
        val blank = Aircraft()
        repo.upsert(blank)
        openEditor(blank.id)
    }
}
