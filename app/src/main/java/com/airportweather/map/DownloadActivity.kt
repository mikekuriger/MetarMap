package com.airportweather.map

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airportweather.map.utils.DatabaseSyncUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private lateinit var updateAllButton: Button
    private val sectionalList = mutableListOf<SectionalChart>()

    private val catalogRepo by lazy { ChartCatalogRepository(filesDir) }
    private val seriesStore by lazy { ChartSeriesStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)

        setContentView(R.layout.activity_downloads)

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SectionalAdapter(sectionalList, this)
        recyclerView.adapter = adapter

        findViewById<Button>(R.id.downloadDbButton).setOnClickListener {
            lifecycleScope.launch {
                DatabaseSyncUtils.syncAirportDatabases(
                    this@DownloadActivity,
                    getSharedPreferences("db_versions", MODE_PRIVATE)
                )
            }
        }

        updateAllButton = findViewById(R.id.updateAllButton)
        updateAllButton.setOnClickListener { updateAllStale() }
        updateAllButton.visibility = View.GONE

        loadSectionalList()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun loadSectionalList() {
        lifecycleScope.launch {
            // Force-refresh on open so a freshly-published series is reflected
            // immediately rather than waiting up to 24h for the cache to expire.
            val ok = catalogRepo.forceRefresh()
            val catalog = catalogRepo.catalog.value
            if (!ok || catalog == null) {
                Toast.makeText(this@DownloadActivity, "Failed to load charts", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val charts = buildSectionalRows(catalog)
            sectionalList.clear()
            sectionalList.addAll(charts)
            adapter.notifyDataSetChanged()
            refreshUpdateAllButton()
        }
    }

    private fun buildSectionalRows(catalog: ChartCatalog): List<SectionalChart> {
        val sectionalByName = catalog.sectional.charts.associateBy { it.name }
        val terminalByName = catalog.terminal.charts.associateBy { it.name }
        val out = mutableListOf<SectionalChart>()

        for ((name, sec) in sectionalByName) {
            val term = terminalByName[name]
            val secMb = sec.size.replace(" MB", "").toFloatOrNull()?.toInt() ?: 0
            val termMb = term?.size?.replace(" MB", "")?.toFloatOrNull()?.toInt() ?: 0
            val totalMb = secMb + termMb
            val type = if (term != null) "🟠 Sectional + TAC" else "🟢 Sectional"

            out += SectionalChart(
                name = name,
                url = sec.url,
                fileSize = "$secMb MB",
                totalSize = "$totalMb MB - $type",
                installedSeries = seriesStore.installedSeries(sec.fileName),
                installedExpires = seriesStore.installedExpires(sec.fileName),
                latestSeries = catalog.sectional.series,
                latestExpires = catalog.sectional.expires,
                fileName = sec.fileName,
                terminal = term?.let {
                    TerminalChart(
                        name = it.name,
                        url = it.url,
                        fileSize = it.size,
                        isInstalled = seriesStore.isInstalled(it.fileName),
                        fileName = it.fileName,
                    )
                },
                terminalFileName = term?.fileName,
                hasTerminal = term != null,
            )
        }

        // IFR / Enroute charts (no terminal pair).
        for (e in catalog.enroute.charts) {
            val fileName = e.fileName + "_IFR"
            val sizeMb = e.size.replace(" MB", "").toFloatOrNull()?.toInt() ?: 0
            out += SectionalChart(
                name = e.name,
                url = e.url,
                fileSize = "$sizeMb MB",
                totalSize = "$sizeMb MB - 🔵 IFR",
                installedSeries = seriesStore.installedSeries(fileName),
                installedExpires = seriesStore.installedExpires(fileName),
                latestSeries = catalog.enroute.series,
                latestExpires = catalog.enroute.expires,
                fileName = fileName,
                terminal = null,
                terminalFileName = null,
                hasTerminal = false,
            )
        }

        // Terminal-only entries (no matching sectional name).
        for ((name, term) in terminalByName) {
            if (sectionalByName.containsKey(name)) continue
            val termMb = term.size.replace(" MB", "").toFloatOrNull()?.toInt() ?: 0
            out += SectionalChart(
                name = name,
                url = term.url,
                fileSize = "$termMb MB",
                totalSize = "$termMb MB - 🟤 VFR Aeronautical",
                installedSeries = seriesStore.installedSeries(term.fileName),
                installedExpires = seriesStore.installedExpires(term.fileName),
                latestSeries = catalog.terminal.series,
                latestExpires = catalog.terminal.expires,
                fileName = term.fileName,
                terminal = TerminalChart(
                    name = term.name,
                    url = term.url,
                    fileSize = term.size,
                    isInstalled = seriesStore.isInstalled(term.fileName),
                    fileName = term.fileName,
                ),
                terminalFileName = term.fileName,
                hasTerminal = true,
            )
        }

        return out.sortedBy { it.name.lowercase() }
    }

    private fun refreshUpdateAllButton() {
        val installed = sectionalList.count { it.isInstalled }
        val stale = sectionalList.count { it.status == InstallStatus.INSTALLED_STALE }
        if (installed == 0) {
            updateAllButton.visibility = View.GONE
            return
        }
        updateAllButton.visibility = View.VISIBLE
        // Highlight when stale charts exist; otherwise it's a plain "redownload
        // everything I have installed" button (mostly useful for testing).
        updateAllButton.text = if (stale > 0) {
            "Update $stale expired chart${if (stale == 1) "" else "s"}"
        } else {
            "Update all $installed installed chart${if (installed == 1) "" else "s"}"
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun updateAllStale() {
        // If anything is stale, prefer updating just those (the typical case).
        // Otherwise, refresh everything currently installed — useful for testing
        // and for forcing a clean re-extract after corruption.
        val staleOnly = sectionalList.filter { it.status == InstallStatus.INSTALLED_STALE && !it.isDownloading }
        val targets = if (staleOnly.isNotEmpty()) staleOnly
                      else sectionalList.filter { it.isInstalled && !it.isDownloading }
        if (targets.isEmpty()) return
        Toast.makeText(this, "Updating ${targets.size} chart${if (targets.size == 1) "" else "s"}…", Toast.LENGTH_SHORT).show()
        for (chart in targets) {
            chart.isDownloading = true
        }
        adapter.notifyDataSetChanged()
        // Must dispatch on IO — doDownload opens HttpURLConnection synchronously
        // and would hit NetworkOnMainThreadException on the default Main dispatcher.
        lifecycleScope.launch(Dispatchers.IO) {
            for (chart in targets) {
                downloadSectionalSuspending(chart)
            }
            withContext(Dispatchers.Main) { refreshUpdateAllButton() }
        }
    }

    private fun getDownloadStorageDir(): File =
        getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir

    private fun getTileStorageDir(localFolder: String): File =
        File(filesDir, "tiles/$localFolder")

    private fun unzipFile(zipFile: File, targetDirectory: File) {
        Log.d("Unzip", "Extracting ${zipFile.absolutePath} to ${targetDirectory.absolutePath}")

        if (!targetDirectory.exists()) targetDirectory.mkdirs()

        // Resolve once so every entry can be checked against the canonical target root.
        // Defends against zip-slip: entries like "../../etc/passwd" would otherwise escape.
        val targetRoot = targetDirectory.canonicalFile
        val targetRootPath = targetRoot.path + File.separator

        try {
            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val extracted = File(targetRoot, entry!!.name).canonicalFile
                    if (extracted != targetRoot && !extracted.path.startsWith(targetRootPath)) {
                        Log.e("Unzip", "Skipping unsafe zip entry: ${entry!!.name}")
                        continue
                    }
                    if (entry!!.isDirectory) {
                        if (!extracted.exists()) extracted.mkdirs()
                    } else {
                        extracted.parentFile?.mkdirs()
                        try {
                            FileOutputStream(extracted).use { fos -> zis.copyTo(fos) }
                        } catch (e: Exception) {
                            Log.e("Unzip", "Failed to extract ${extracted.absolutePath}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Unzip", "Error during extraction", e)
        }
    }

    private suspend fun downloadChart(
        url: String,
        file: File,
        totalSizeBytes: Long,
        totalBytesReadSoFar: Long,
        onProgress: (suspend (Int) -> Unit)? = null,
    ) {
        var totalBytesRead = totalBytesReadSoFar
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.connect()

        conn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buffer = ByteArray(4096)
                var n: Int
                var lastReported = -1
                while (input.read(buffer).also { n = it } != -1) {
                    output.write(buffer, 0, n)
                    totalBytesRead += n
                    val progress = ((totalBytesRead.toFloat() / totalSizeBytes) * 100)
                        .coerceIn(0f, 100f).toInt()
                    // Only post to Main when the integer progress actually
                    // changes — avoids hammering the UI thread on every 4KB read.
                    if (progress != lastReported && onProgress != null) {
                        lastReported = progress
                        withContext(Dispatchers.Main) { onProgress(progress) }
                    }
                }
            }
        }
    }

    /**
     * Variant of [downloadSectional] used by the "Update stale charts" button.
     * Routes progress through the chart model so any visible row reflects the
     * download even after the user scrolls or as the active chart changes.
     */
    private suspend fun downloadSectionalSuspending(chart: SectionalChart) {
        try {
            doDownload(chart) { progress ->
                chart.downloadProgress = progress
                val pos = sectionalList.indexOf(chart)
                val vh = recyclerView.findViewHolderForAdapterPosition(pos)
                        as? SectionalAdapter.ViewHolder
                vh?.progressBar?.progress = progress
            }
            withContext(Dispatchers.Main) {
                chart.installedSeries = chart.latestSeries
                chart.installedExpires = chart.latestExpires
                chart.isDownloading = false
                chart.downloadProgress = 0
                adapter.notifyItemChanged(sectionalList.indexOf(chart))
            }
        } catch (e: Exception) {
            Log.e("DownloadPage", "Batch download failed: ${e.message}")
            withContext(Dispatchers.Main) {
                chart.isDownloading = false
                chart.downloadProgress = 0
                adapter.notifyItemChanged(sectionalList.indexOf(chart))
                Toast.makeText(this@DownloadActivity, "Update failed for ${chart.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun downloadSectional(
        chart: SectionalChart,
        progressBar: ProgressBar,
        downloadIcon: ImageView,
        downloadingIcon: ImageView,
        statusText: TextView,
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    chart.isDownloading = true
                    downloadIcon.visibility = View.GONE
                    downloadingIcon.visibility = View.VISIBLE
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = 0
                    statusText.visibility = View.VISIBLE
                }

                doDownload(chart, statusText) { progress ->
                    chart.downloadProgress = progress
                    progressBar.progress = progress
                }

                withContext(Dispatchers.Main) {
                    chart.installedSeries = chart.latestSeries
                    chart.installedExpires = chart.latestExpires
                    chart.isDownloading = false
                    chart.downloadProgress = 0
                    downloadingIcon.visibility = View.GONE
                    downloadIcon.visibility = View.GONE
                    progressBar.visibility = View.GONE
                    statusText.visibility = View.GONE
                    adapter.notifyItemChanged(sectionalList.indexOf(chart))
                    refreshUpdateAllButton()
                }
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

    /**
     * Core download flow shared by the per-row click and the "Update stale charts"
     * button. Writes the catalog series into [ChartSeriesStore] on each successful
     * install. Progress comes via [onProgress] (0–100, on Main).
     */
    private suspend fun doDownload(
        chart: SectionalChart,
        statusText: TextView? = null,
        onProgress: suspend (Int) -> Unit,
    ) {
        val hasSectional = chart.url.isNotEmpty()
        val hasTerminal = chart.terminal != null

        val sectionalSizeBytes = if (hasSectional) {
            chart.fileSize.replace(Regex(" MB.*"), "").toFloatOrNull()?.toLong()
                ?.times(1048576) ?: 0L
        } else 0L
        val terminalSizeBytes = if (hasTerminal) {
            chart.terminal!!.fileSize.replace(Regex(" MB.*"), "").toFloatOrNull()?.toLong()
                ?.times(1048576) ?: 0L
        } else 0L
        val totalSizeBytes = sectionalSizeBytes + terminalSizeBytes
        if (totalSizeBytes <= 0L) {
            Log.e("DownloadPage", "Bad size for ${chart.name}")
            return
        }

        var totalBytesRead = 0L

        if (hasSectional) {
            val label = if (chart.fileName.endsWith("_IFR")) "Downloading IFR Chart" else "Downloading VFR Chart"
            statusText?.let { withContext(Dispatchers.Main) { it.text = label } }

            val sectionalFile = File(getDownloadStorageDir(), chart.fileName)
            downloadChart(chart.url, sectionalFile, totalSizeBytes, totalBytesRead, onProgress)
            totalBytesRead += sectionalSizeBytes

            val installLabel = if (chart.fileName.endsWith("_IFR")) "Installing IFR Chart" else "Installing VFR Chart"
            statusText?.let { withContext(Dispatchers.Main) { it.text = installLabel } }
            val targetDir = if (chart.fileName.endsWith("_IFR")) "IFR" else "Sectional"
            unzipFile(sectionalFile, getTileStorageDir(targetDir))
            sectionalFile.delete()
            seriesStore.markInstalled(chart.fileName, chart.latestSeries, chart.latestExpires)
        }

        if (hasTerminal) {
            statusText?.let { withContext(Dispatchers.Main) { it.text = "Downloading TAC" } }
            val terminalFile = File(getDownloadStorageDir(), chart.terminal!!.fileName)
            downloadChart(chart.terminal.url, terminalFile, totalSizeBytes, totalBytesRead, onProgress)
            statusText?.let { withContext(Dispatchers.Main) { it.text = "Installing TAC" } }
            unzipFile(terminalFile, getTileStorageDir("Terminal"))
            terminalFile.delete()
            seriesStore.markInstalled(chart.terminal.fileName, chart.latestSeries, chart.latestExpires)
        }
    }
}
