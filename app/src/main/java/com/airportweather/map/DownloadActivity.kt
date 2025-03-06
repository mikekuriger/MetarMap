package com.airportweather.map

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

class DownloadActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    lateinit var adapter: SectionalAdapter
    private val sectionalList = mutableListOf<SectionalChart>()

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("DownloadDebug", "📢 onReceive TRIGGERED!")
            val fileName = intent?.getStringExtra("fileName") ?: return
            val status = intent.getStringExtra("status") ?: "Downloading..."
            val progress = intent.getIntExtra("progress", 0)

            Log.d("DownloadDebug", "Received update -> fileName: $fileName, status: $status, progress: $progress")

            // ✅ Find the matching chart in the list
            val index = sectionalList.indexOfFirst { it.fileName == fileName }
            if (index == -1) {
                Log.e("DownloadDebug", "No match found for $fileName")
                return
            }

            val chart = sectionalList[index]
            chart.isDownloading = progress < 100
            chart.progress = progress

            // ✅ Update UI on the main thread
            runOnUiThread {
                adapter.notifyItemChanged(index)
                Log.d("DownloadDebug", "notifyItemChanged triggered for index $index")
            }
        }
    }

    fun updateUI(fileName: String, status: String, progress: Int) {
        val index = sectionalList.indexOfFirst { it.fileName == fileName }
        if (index == -1) return  // No match found

        val chart = sectionalList[index]
        chart.isDownloading = progress < 100
        chart.progress = progress

        runOnUiThread {
            adapter.notifyItemChanged(index)
            Log.d("DownloadDebug", "✅ UI Updated -> $fileName, Progress: $progress%")
        }
    }

    companion object {
        var instance: DownloadActivity? = null
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SectionalAdapter(sectionalList, this)
        recyclerView.adapter = adapter

        loadSectionalList()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter("DOWNLOAD_PROGRESS")
        try {
            //registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            LocalBroadcastManager.getInstance(this).registerReceiver(downloadReceiver, filter)

            Log.d("DownloadDebug", "✅ DownloadReceiver registered in onResume()")
        } catch (e: Exception) {
            Log.e("DownloadDebug", "❌ Failed to register receiver: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        //unregisterReceiver(downloadReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver)
        Log.d("DownloadDebug", "❌ DownloadReceiver unregistered in onPause()")
    }


    override fun onDestroy() {
        super.onDestroy()
        //unregisterReceiver(downloadReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(downloadReceiver)
        Log.d("DownloadDebug", "DownloadReceiver unregistered")
    }

    @SuppressLint("NotifyDataSetChanged")
    fun loadSectionalList() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sectionalJsonString = URL("https://regiruk.netlify.app/zips/sectionals.json").readText()
                val sectionalArray = JSONArray(sectionalJsonString)

                val terminalJsonString = URL("https://regiruk.netlify.app/zips2/terminals.json").readText()
                val terminalArray = JSONArray(terminalJsonString)

                val sectionalMap = mutableMapOf<String, JSONObject>()
                val terminalMap = mutableMapOf<String, JSONObject>()

                for (i in 0 until sectionalArray.length()) {
                    val obj = sectionalArray.getJSONObject(i)
                    sectionalMap[obj.getString("name")] = obj
                }

                for (i in 0 until terminalArray.length()) {
                    val obj = terminalArray.getJSONObject(i)
                    terminalMap[obj.getString("name")] = obj
                }

                val prefs = getSharedPreferences("download_status", MODE_PRIVATE)
                val activeDownloads = prefs.getStringSet("activeDownloads", emptySet()) ?: emptySet()
                val installedPrefs = getSharedPreferences("installed_sectionals", MODE_PRIVATE)
                val installedSet = installedPrefs.getStringSet("sectionals", emptySet()) ?: emptySet()

                val charts = mutableListOf<SectionalChart>()

                for ((name, sectionalObj) in sectionalMap) {
                    val fileName = sectionalObj.getString("fileName")
                    val sectionalSize = sectionalObj.getString("size").replace(" MB", "").toFloatOrNull()?.toInt() ?: 0

                    val terminalObj = terminalMap[name]
                    val terminalSize = terminalObj?.getString("size")?.replace(" MB", "")?.toFloatOrNull()?.toInt() ?: 0
                    val totalSize = sectionalSize + terminalSize

                    val chartType = when {
                        terminalObj != null -> "🟠 Sectional + TAC"
                        else -> "🟢 Sectional"
                    }

                    val isDownloading = activeDownloads.contains(fileName)

                    val chart = SectionalChart(
                        name = name,
                        url = sectionalObj.getString("url"),
                        fileSize = "$sectionalSize MB",
                        totalSize = "$totalSize MB - $chartType",
                        isInstalled = installedSet.contains(fileName),
                        isDownloading = isDownloading,
                        fileName = fileName,
                        terminal = terminalObj?.let {
                            TerminalChart(
                                name = it.getString("name"),
                                url = it.getString("url"),
                                fileSize = it.getString("size"),
                                isInstalled = false,
                                isDownloading = false,
                                fileName = it.getString("fileName")
                            )
                        },
                        terminalFileName = terminalObj?.getString("fileName"),
                        hasTerminal = terminalObj?.getString("fileName") != null
                    )
                    charts.add(chart)
                }

                for ((name, terminalObj) in terminalMap) {
                    if (!sectionalMap.containsKey(name)) {
                        val terminalSize = terminalObj.getString("size").replace(" MB", "").toFloatOrNull()?.toInt() ?: 0
                        val isDownloading = activeDownloads.contains(terminalObj.getString("fileName"))


                        val chart = SectionalChart(
                            name = name,
                            url = terminalObj.getString("url"),
                            fileSize = "$terminalSize MB",
                            totalSize = "$terminalSize MB - 🔵 VFR Aeronautical",
                            isInstalled = installedSet.contains(terminalObj.getString("fileName")),
                            isDownloading = isDownloading,
                            fileName = terminalObj.getString("fileName"),
                            terminal = TerminalChart(
                                name = terminalObj.getString("name"),
                                url = terminalObj.getString("url"),
                                fileSize = terminalObj.getString("size"),
                                isInstalled = false,
                                isDownloading = false,
                                fileName = terminalObj.getString("fileName")
                            ),
                            terminalFileName = terminalObj.getString("fileName"),
                            hasTerminal = true
                        )
                        charts.add(chart)
                    }
                }

                Log.d("Debug", "Installed set from SharedPreferences: $installedSet")

                withContext(Dispatchers.Main) {
                    sectionalList.clear()
                    sectionalList.addAll(charts)
                    adapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                Log.e("DownloadPage", "Error fetching sectionals: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@DownloadActivity, "Failed to load sectionals", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun downloadSectional(
        chart: SectionalChart,
        context: Context,
        progressBar: ProgressBar,
        downloadIcon: ImageView,
        downloadingIcon: ImageView,
        statusText: TextView
    ) {
        val hasSectional = chart.url.isNotEmpty()
        val hasTerminal = chart.terminal != null

        Log.d("DownloadPage", "User selected: ${chart.name}")
        Log.d("DownloadPage", "Has Sectional: $hasSectional | Has Terminal: $hasTerminal")

        // ✅ Update UI before starting download
        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        downloadIcon.visibility = View.GONE
        downloadingIcon.visibility = View.VISIBLE
        statusText.visibility = View.VISIBLE
        statusText.text = "Preparing Download"

        // ✅ Start background download
        val intent = Intent(context, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_FILE_NAME, chart.fileName)
            putExtra(DownloadService.EXTRA_DOWNLOAD_URL, chart.url)
            putExtra(DownloadService.EXTRA_FILE_SIZE, chart.fileSize)

            if (chart.terminal != null) {
                putExtra(DownloadService.EXTRA_TERMINAL_FILE_NAME, chart.terminal.fileName)
                putExtra(DownloadService.EXTRA_TERMINAL_DOWNLOAD_URL, chart.terminal.url)
                putExtra(DownloadService.EXTRA_TERMINAL_FILE_SIZE, chart.terminal.fileSize)
            }
        }

        Log.d("DownloadDebug", "Starting DownloadService for ${chart.fileName}")

        context.startForegroundService(intent)
    }
}
