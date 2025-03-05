package com.airportweather.map

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
                val jsonString = URL("https://regiruk.netlify.app/zips/sectionals.json").readText()
                val jsonArray = JSONArray(jsonString)

                val prefs = getSharedPreferences("installed_sectionals", MODE_PRIVATE)
                val installedSet = prefs.getStringSet("sectionals", emptySet()) ?: emptySet()

                val charts = mutableListOf<SectionalChart>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val fileName = obj.getString("fileName")

                    val chart = SectionalChart(
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        fileSize = obj.getString("size"),
                        isInstalled = installedSet.contains(fileName),
                        isDownloading = false,
                        fileName = fileName
                    )
                    charts.add(chart)
                }

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

    private fun getDownloadStorageDir(): File {
        return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
    }

    private fun getTileStorageDir(localFolder: String): File {
        return File(filesDir, "tiles/$localFolder")
    }

    private fun unzipFile(zipFile: File, targetDirectory: File) {
        Log.d("Unzip", "Starting extraction of: ${zipFile.absolutePath} to ${targetDirectory.absolutePath}")

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
                            Log.e("Unzip", "Failed to extract file: ${extractedFile.absolutePath}", e)
                        }
                    }
                }
            }
            Log.d("Unzip", "Extraction complete!")
        } catch (e: Exception) {
            Log.e("Unzip", "Error during extraction", e)
        }
    }

    @SuppressLint("MutatingSharedPrefs")
    private fun markSectionalAsInstalled(fileName: String) {
        val prefs = getSharedPreferences("installed_sectionals", MODE_PRIVATE)
        val installedSet = prefs.getStringSet("sectionals", mutableSetOf()) ?: mutableSetOf()

        // ✅ Check if filename is actually being added
        Log.d("INSTALL_MARK", "Before: $installedSet")

        installedSet.add(fileName) // Add to installed list

        Log.d("INSTALL_MARK", "After: $installedSet") // ✅ Log update

        prefs.edit().putStringSet("sectionals", installedSet).apply() // ✅ Save changes
    }



    @SuppressLint("NotifyDataSetChanged")
    //fun downloadSectional(chart: SectionalChart, progressBar: ProgressBar, progressText: TextView, downloadIcon: ImageView) {
    fun downloadSectional(chart: SectionalChart, progressBar: ProgressBar, downloadIcon: ImageView) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val localFolder = if (chart.fileName == "Terminal.zip") "Terminal" else "Sectional"
                val zipFile = File(getDownloadStorageDir(), chart.fileName)

                withContext(Dispatchers.Main) {
                    chart.isDownloading = true
                    downloadIcon.visibility = View.GONE
                    progressBar.visibility = View.VISIBLE
                    progressBar.max = 100  // ✅ Ensure progress is within 0-100
                    progressBar.progress = 0
                    //progressText.visibility = View.VISIBLE
                    //progressText.text = "0%"
                }

                // Open connection
                val urlConnection = URL(chart.url).openConnection() as HttpURLConnection
                urlConnection.connect()

                urlConnection.inputStream.use { input ->
                    zipFile.outputStream().use { output ->
                        val fileSize = chart.fileSize.replace(" MB", "").toFloat() * 1048576 // ✅ Convert MB to bytes
                        var totalBytesRead = 0
                        val buffer = ByteArray(4096)  // ✅ Larger buffer for smoother updates
                        var bytesRead: Int

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead

                            // Calculate progress percentage
                            //val progress = ((totalBytesRead.toFloat() / fileSize) * 100).toInt()
                            val progress = ((totalBytesRead.toFloat() / fileSize) * 100).coerceIn(0f, 100f).toInt()

                            // ✅ Log progress for debugging
                            //Log.d("DownloadProgress", "Downloaded: $totalBytesRead / $fileSize ($progress%)")

                            // ✅ Update UI on the main thread
                            withContext(Dispatchers.Main) {
                                progressBar.progress = progress
                              //  progressText.text = "$progress%"
                            }
                        }
                        //output.flush()
                    }
                }

                Log.d("DownloadPage", "ZIP downloaded to: ${zipFile.absolutePath}")

                // ✅ Unzip into tiles/Sectional or tiles/Terminal
                unzipFile(zipFile, getTileStorageDir(localFolder))

                // ✅ Optional: Delete ZIP after extraction
                zipFile.delete()

                // ✅ Mark as installed in SharedPreferences
                markSectionalAsInstalled(chart.fileName)

                withContext(Dispatchers.Main) {
                    chart.isInstalled = true
                    chart.isDownloading = false
                    progressBar.visibility = View.GONE
                   // progressText.visibility = View.GONE
                    adapter.notifyItemChanged(sectionalList.indexOf(chart))
                    //adapter.notifyDataSetChanged()
                    //Toast.makeText(this@DownloadActivity, "${chart.name} downloaded & extracted!", Toast.LENGTH_SHORT).show()
                }

                markSectionalAsInstalled(chart.fileName)

            } catch (e: Exception) {
                Log.e("DownloadPage", "Download failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    chart.isDownloading = false
                    progressBar.visibility = View.GONE
                   // progressText.visibility = View.GONE
                    downloadIcon.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this@DownloadActivity, "Download failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    fun deleteSectional(context: Context) {
        try {
            val sectionalStorageDir = File(context.filesDir, "tiles/Sectional")
            val terminalStorageDir = File(context.filesDir, "tiles/Terminal")

            if (sectionalStorageDir.exists()) {
                sectionalStorageDir.deleteRecursively()
                Log.d("DeleteSectionals", "All sectional charts deleted successfully.")
            } else {
                Log.d("DeleteSectionals", "No sectional charts found to delete.")
            }
            if (terminalStorageDir.exists()) {
                sectionalStorageDir.deleteRecursively()
                Log.d("DeleteSectionals", "All terminal charts deleted successfully.")
            } else {
                Log.d("DeleteSectionals", "No terminal charts found to delete.")
            }
        } catch (e: Exception) {
            Log.e("DeleteSectionals", "Error deleting terminals: ${e.message}")
        }
    }


}
