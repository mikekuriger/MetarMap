package com.airportweather.map

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class DownloadActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SectionalAdapter
    private val sectionalList = mutableListOf<SectionalChart>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_downloads)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SectionalAdapter(sectionalList, this)
        recyclerView.adapter = adapter

        loadSectionalList()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun loadSectionalList() {

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sectionalJsonString =
                    URL("https://regiruk.netlify.app/zips/sectionals.json").readText()
                val sectionalArray = JSONArray(sectionalJsonString)

                val terminalJsonString =
                    URL("https://regiruk.netlify.app/zips2/terminals.json").readText()
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

                val prefs = getSharedPreferences("installed_sectionals", MODE_PRIVATE)
                val installedSet = prefs.getStringSet("sectionals", emptySet()) ?: emptySet()

                val charts = mutableListOf<SectionalChart>()

                // ✅ Process Sectionals, Adding Terminal Info if Available
                for ((name, sectionalObj) in sectionalMap) {
                    val fileName = sectionalObj.getString("fileName")
//                    val sectionalSize = sectionalObj.getString("size").replace(" MB", "").toFloat()
                    val sectionalSize = sectionalObj.getString("size").replace(" MB", "").toFloatOrNull()?.toInt() ?: 0

                    val terminalObj = terminalMap[name]
//                    val terminalSize = terminalObj?.getString("size")?.replace(" MB", "")?.toFloatOrNull() ?: 0f
                    val terminalSize = terminalObj?.getString("size")?.replace(" MB", "")?.toFloatOrNull()?.toInt() ?: 0
                    val totalSize = sectionalSize + terminalSize

                    val chartType = when {
                        terminalObj != null -> "🟠 Sectional + TAC"
                        else -> "🟢 Sectional"
                    }

                    val chart = SectionalChart(
                        name = name,
                        url = sectionalObj.getString("url"),
                        fileSize = "${sectionalSize} MB",
                        totalSize = "${totalSize} MB - $chartType",
                        isInstalled = installedSet.contains(fileName),
                        isDownloading = false,
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
                        hasTerminal = terminalObj != null
                    )
                    charts.add(chart)
                }

                // ✅ Add Terminal-Only Charts Correctly
                for ((name, terminalObj) in terminalMap) {
                    if (!sectionalMap.containsKey(name)) {  // ✅ Terminal-only charts
                        val terminalSize =
                            terminalObj.getString("size").replace(" MB", "").toFloat()

                        val chart = SectionalChart(
                            name = name,
                            url = terminalObj.getString("url"),
                            fileSize = "${terminalSize} MB",
                            totalSize = "${terminalSize} MB - 🔵 VFR Aeronautical",
                            isInstalled = installedSet.contains(terminalObj.getString("fileName")),
                            isDownloading = false,
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
                    Toast.makeText(
                        this@DownloadActivity,
                        "Failed to load sectionals",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    @SuppressLint("MutatingSharedPrefs")
    private fun markSectionalAsInstalled(fileName: String) {
        val prefs = getSharedPreferences("installed_sectionals", MODE_PRIVATE)

        // ✅ Always create a NEW mutable set instead of modifying the reference
        val installedSet = prefs.getStringSet("sectionals", emptySet())?.toMutableSet() ?: mutableSetOf()
        installedSet.add(fileName)

        prefs.edit().putStringSet("sectionals", installedSet).apply()

        Log.d("INSTALL_MARK", "Updated installed list: $installedSet")
    }


    private fun getDownloadStorageDir(): File {
        return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
    }
    private fun getTileStorageDir(localFolder: String): File {
        return File(filesDir, "tiles/$localFolder")
    }
    private fun unzipFile(zipFile: File, targetDirectory: File) {
        Log.d(
            "Unzip",
            "Starting extraction of: ${zipFile.absolutePath} to ${targetDirectory.absolutePath}"
        )

        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs()
            Log.d("Unzip", "Created directory: ${targetDirectory.absolutePath}")
        }

        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val extractedFile = File(targetDirectory, entry!!.name)
                    //Log.d("Unzip", "Extracting: ${entry!!.name} -> ${extractedFile.absolutePath}")

                    if (entry!!.isDirectory) {
                        if (!extractedFile.exists()) {
                            extractedFile.mkdirs()
                            Log.d("Unzip", "Created directory: ${extractedFile.absolutePath}")
                        }
                    } else {
                        // Ensure parent directories exist
                        extractedFile.parentFile?.mkdirs()

                        try {
                            FileOutputStream(extractedFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            //Log.d("Unzip", "Successfully extracted: ${extractedFile.absolutePath}")
                        } catch (e: Exception) {
                            Log.e(
                                "Unzip",
                                "Failed to extract file: ${extractedFile.absolutePath}",
                                e
                            )
                        }
                    }
                }
            }
            Log.d("Unzip", "Extraction complete!")
        } catch (e: Exception) {
            Log.e("Unzip", "Error during extraction", e)
        }
    }
    private suspend fun downloadChart(
        url: String,
        file: File,
        fileSizeBytes: Long,
        progressBar: ProgressBar,
        totalSizeBytes: Long,
        totalBytesReadSoFar: Long
    ) {
        var totalBytesRead = totalBytesReadSoFar

        Log.d("DownloadPage", "Downloading: $url")
        Log.d("DownloadPage", "Saving to: ${file.absolutePath}")
        Log.d("DownloadPage", "File Size: ${fileSizeBytes / 1048576} MB")

        val urlConnection = URL(url).openConnection() as HttpURLConnection
        urlConnection.connect()

        urlConnection.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(4096)
                var bytesRead: Int

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // ✅ Progress carries over between sectional & terminal downloads
                    val progress = ((totalBytesRead.toFloat() / totalSizeBytes) * 100).coerceIn(0f, 100f).toInt()
                    withContext(Dispatchers.Main) { progressBar.progress = progress }
                }
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun downloadSectional(
        chart: SectionalChart,
        progressBar: ProgressBar,
        downloadIcon: ImageView,
        downloadingIcon: ImageView,
        statusText: TextView
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val hasSectional = chart.url.isNotEmpty()
                val hasTerminal = chart.terminal != null

                Log.d("DownloadPage", "User selected: ${chart.name}")
                Log.d("DownloadPage", "Has Sectional: $hasSectional | Has Terminal: $hasTerminal")

                withContext(Dispatchers.Main) {
                    chart.isDownloading = true
                    downloadIcon.visibility = View.GONE
                    downloadingIcon.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                    statusText.visibility = View.VISIBLE
//                    statusText.text = "Starting Download..."
                }

                var totalBytesRead = 0L
                var totalSizeBytes = 0L

                val sectionalSizeBytes = if (hasSectional) {
                    chart.fileSize.replace(Regex(" MB.*"), "").toFloatOrNull()?.toLong()?.times(1048576) ?: 0L
                } else 0L

                val terminalSizeBytes = if (hasTerminal) {
                    chart.terminal!!.fileSize.replace(Regex(" MB.*"), "").toFloatOrNull()?.toLong()?.times(1048576) ?: 0L
                } else 0L

                totalSizeBytes = sectionalSizeBytes + terminalSizeBytes

                Log.d("DownloadPage", "Total size for ${chart.name}: ${totalSizeBytes / 1048576} MB")

                if (totalSizeBytes <= 0L) {
                    Log.e("DownloadPage", "Error: Could not determine file size for ${chart.name} from JSON")
                    return@launch
                }

                // ✅ Download Sectional Chart (if available)
                if (hasSectional) {
                    withContext(Dispatchers.Main) { statusText.text = "Downloading Sectional" }
                    Log.d("DownloadPage", "Starting download: Sectional ${chart.fileName}")

                    val sectionalFile = File(getDownloadStorageDir(), chart.fileName)
                    downloadChart(chart.url, sectionalFile, sectionalSizeBytes, progressBar, totalSizeBytes, totalBytesRead)

                    totalBytesRead += sectionalSizeBytes  // ✅ Preserve progress for terminal download

                    withContext(Dispatchers.Main) { statusText.text = "Installing Sectional" }
                    unzipFile(sectionalFile, getTileStorageDir("Sectional"))
                    sectionalFile.delete()
                    markSectionalAsInstalled(chart.fileName)
                }

                // ✅ Download Terminal Chart (if available)
                if (hasTerminal) {
                    withContext(Dispatchers.Main) { statusText.text = "Downloading TAC" }
                    Log.d("DownloadPage", "Starting download: Terminal ${chart.terminal!!.fileName}")

                    val terminalFile = File(getDownloadStorageDir(), chart.terminal!!.fileName)
                    downloadChart(chart.terminal!!.url, terminalFile, terminalSizeBytes, progressBar, totalSizeBytes, totalBytesRead)

                    withContext(Dispatchers.Main) { statusText.text = "Installing TAC" }
                    unzipFile(terminalFile, getTileStorageDir("Terminal"))
                    terminalFile.delete()
                    markSectionalAsInstalled(chart.terminal!!.fileName)
                }

                // ✅ Update UI after downloads complete
                withContext(Dispatchers.Main) {
                    chart.isInstalled = true
                    chart.isDownloading = false
                    downloadingIcon.visibility = View.GONE
                    downloadIcon.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    //statusText.text = "Download Complete!"
                    //delay(1500)  // Keep message visible for 1.5 seconds
                    statusText.visibility = View.GONE
                    adapter.notifyItemChanged(sectionalList.indexOf(chart))
                }

                Log.d("DownloadPage", "Download complete for: ${chart.name}")

            } catch (e: Exception) {
                Log.e("DownloadPage", "Download failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    chart.isDownloading = false
                    downloadingIcon.visibility = View.GONE
                    downloadIcon.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    statusText.text = "Download Failed"
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this@DownloadActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
