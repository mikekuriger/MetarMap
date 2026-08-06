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

        findViewById<Button>(R.id.downloadRegionButton).setOnClickListener { showRegionPicker() }

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
        val terminalByNormalizedName = catalog.terminal.charts.associateBy { normalizeAreaName(it.name) }
        val out = mutableListOf<SectionalChart>()
        // Any TAC claimed by an overlapping sectional below is not also an
        // "orphan" terminal-only row further down.
        val claimedTerminalKeys = mutableSetOf<String>()

        for ((name, sec) in sectionalByName) {
            val overlapKeys = ChartRegions.overlappingTerminalKeys(name)
            val terms = overlapKeys.mapNotNull { terminalByNormalizedName[it] }
            claimedTerminalKeys += overlapKeys

            val secMb = sec.size.replace(" MB", "").toFloatOrNull()?.toInt() ?: 0
            val termMb = terms.sumOf { it.size.replace(" MB", "").toFloatOrNull()?.toInt() ?: 0 }
            val totalMb = secMb + termMb
            val type = when (terms.size) {
                0 -> "🟢 Sectional"
                1 -> "🟠 Sectional + TAC"
                else -> "🟠 Sectional + ${terms.size} TAC"
            }

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
                terminals = terms.map {
                    TerminalChart(
                        name = it.name,
                        url = it.url,
                        fileSize = it.size,
                        isInstalled = seriesStore.isInstalled(it.fileName),
                        fileName = it.fileName,
                    )
                },
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
            )
        }

        // Terminal-only entries: any TAC not claimed by an overlapping sectional
        // above (e.g. a metro whose TAC extent doesn't overlap a named sectional
        // area at all). Row has no sectional of its own -- url left empty and the
        // chart lives solely in `terminals`, so doDownload() installs it once,
        // into the Terminal folder, instead of double-extracting it.
        for (term in catalog.terminal.charts) {
            val key = normalizeAreaName(term.name)
            if (key in claimedTerminalKeys) continue
            val termMb = term.size.replace(" MB", "").toFloatOrNull()?.toInt() ?: 0
            out += SectionalChart(
                name = term.name,
                url = "",
                fileSize = "$termMb MB",
                totalSize = "$termMb MB - 🟤 VFR Aeronautical",
                installedSeries = seriesStore.installedSeries(term.fileName),
                installedExpires = seriesStore.installedExpires(term.fileName),
                latestSeries = catalog.terminal.series,
                latestExpires = catalog.terminal.expires,
                fileName = term.fileName,
                terminals = listOf(
                    TerminalChart(
                        name = term.name,
                        url = term.url,
                        fileSize = term.size,
                        isInstalled = seriesStore.isInstalled(term.fileName),
                        fileName = term.fileName,
                    )
                ),
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

    private fun showRegionPicker() {
        // No data for Caribbean yet (see ChartRegions) -- don't offer a region
        // that can never find anything to download.
        val regions = ChartRegion.entries.filter { it != ChartRegion.CARIBBEAN }
        val labels = regions.map { it.label }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle("Download Region")
            .setItems(labels) { _, which -> downloadRegion(regions[which]) }
            .show()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun downloadRegion(region: ChartRegion) {
        val targets = sectionalList.filter {
            ChartRegions.regionOf(it.name) == region && !it.isDownloading
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, "No charts found for ${region.label}", Toast.LENGTH_SHORT).show()
            return
        }
        if (!checkStorageOrWarn(targets)) return
        Toast.makeText(
            this,
            "Downloading ${targets.size} chart${if (targets.size == 1) "" else "s"} for ${region.label}…",
            Toast.LENGTH_SHORT,
        ).show()
        for (chart in targets) {
            chart.isDownloading = true
        }
        adapter.notifyDataSetChanged()
        lifecycleScope.launch(Dispatchers.IO) {
            for (chart in targets) {
                downloadSectionalSuspending(chart)
            }
            withContext(Dispatchers.Main) { refreshUpdateAllButton() }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun updateAllStale() {
        // If anything is stale, prefer updating just those (the typical case).
        // Otherwise, refresh everything currently installed — useful for testing
        // and for forcing a clean re-extract after corruption.
        val staleOnly = sectionalList.filter { it.status == InstallStatus.INSTALLED_STALE && !it.isDownloading }
        val targets = staleOnly.ifEmpty { sectionalList.filter { it.isInstalled && !it.isDownloading } }
        if (targets.isEmpty()) return
        if (!checkStorageOrWarn(targets)) return
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

    /**
     * Extracts [zipFile] into [targetDirectory]. Deliberately does NOT catch
     * extraction failures (e.g. disk full) -- previously every error here was
     * logged and swallowed, so a failed extraction (most commonly the device
     * running out of storage partway through a large chart) still fell
     * through to markInstalled() and showed as a normal successful install,
     * while the map silently fell back to fetching tiles over the network
     * because nothing had actually landed on disk. Letting exceptions
     * propagate means the existing "Download failed" handling in the callers
     * actually fires when something's really wrong.
     */
    private fun unzipFile(zipFile: File, targetDirectory: File) {
        Log.d("Unzip", "Extracting ${zipFile.absolutePath} to ${targetDirectory.absolutePath}")

        if (!targetDirectory.exists()) targetDirectory.mkdirs()

        // Resolve once so every entry can be checked against the canonical target root.
        // Defends against zip-slip: entries like "../../etc/passwd" would otherwise escape.
        val targetRoot = targetDirectory.canonicalFile
        val targetRootPath = targetRoot.path + File.separator

        var extractedCount = 0
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            while (true) {
                val entry: ZipEntry = zis.nextEntry ?: break
                val extracted = File(targetRoot, entry.name).canonicalFile
                if (extracted != targetRoot && !extracted.path.startsWith(targetRootPath)) {
                    Log.e("Unzip", "Skipping unsafe zip entry: ${entry.name}")
                    continue
                }
                if (entry.isDirectory) {
                    if (!extracted.exists()) extracted.mkdirs()
                } else {
                    extracted.parentFile?.mkdirs()
                    FileOutputStream(extracted).use { fos -> zis.copyTo(fos) }
                    extractedCount++
                }
            }
        }
        Log.d("Unzip", "Extracted $extractedCount files from ${zipFile.name}")
    }

    /** True if the volume holding app storage has at least [requiredBytes] free. */
    private fun hasEnoughStorage(requiredBytes: Long): Boolean {
        val stat = android.os.StatFs(getDownloadStorageDir().path)
        val availableBytes = stat.availableBytes
        Log.d("DownloadPage", "Storage check: need $requiredBytes, have $availableBytes")
        return availableBytes >= requiredBytes
    }

    /** Sum of every chart's total size (sectional + all terminals), in bytes. */
    private fun totalBytesOf(charts: List<SectionalChart>): Long {
        fun mbToBytes(mb: String): Long =
            mb.replace(Regex(" MB.*"), "").toFloatOrNull()?.toLong()?.times(1048576) ?: 0L
        return charts.sumOf { chart ->
            val sectionalBytes = if (chart.url.isNotEmpty()) mbToBytes(chart.fileSize) else 0L
            sectionalBytes + chart.terminals.sumOf { mbToBytes(it.fileSize) }
        }
    }

    /**
     * Fails fast with a clear message instead of silently running out of
     * space partway through a multi-chart batch. 2.5x the catalog-reported
     * size is a rough safety margin: the zip and its fully-extracted tiles
     * briefly coexist on disk (the zip is only deleted after extraction),
     * and extracted PNGs run somewhat larger than their compressed size.
     */
    private fun checkStorageOrWarn(targets: List<SectionalChart>): Boolean {
        val required = (totalBytesOf(targets) * 2.5).toLong()
        if (hasEnoughStorage(required)) return true
        val neededMb = required / 1048576
        Toast.makeText(
            this,
            "Not enough free storage for this download (need roughly $neededMb MB free). Free up space and try again.",
            Toast.LENGTH_LONG,
        ).show()
        return false
    }

    private suspend fun downloadChart(
        url: String,
        file: File,
        totalSizeBytes: Long,
        totalBytesReadSoFar: Long,
        onProgress: (suspend (Int) -> Unit)? = null,
    ) {
        var totalBytesRead = totalBytesReadSoFar
        val conn = withContext(Dispatchers.IO) {
            URL(url).openConnection()
        } as HttpURLConnection
        // Chart zips can be large; on slow/flaky Wi-Fi a brief stall is normal,
        // not a dead connection, so give it a lot more slack than the app's
        // other (small-payload) HTTP calls before giving up.
        conn.connectTimeout = 30_000
        conn.readTimeout = 180_000
        withContext(Dispatchers.IO) {
            conn.connect()
        }

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
            Log.e("DownloadPage", "Batch download failed for ${chart.name}: ${e.message}", e)
            withContext(Dispatchers.Main) {
                chart.isDownloading = false
                chart.downloadProgress = 0
                adapter.notifyItemChanged(sectionalList.indexOf(chart))
                Toast.makeText(this@DownloadActivity, "${chart.name}: ${friendlyErrorMessage(e)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Distinguishes "ran out of storage" from other failures for a message worth acting on. */
    private fun friendlyErrorMessage(e: Exception): String {
        val msg = e.message ?: ""
        return if (msg.contains("ENOSPC", ignoreCase = true) || msg.contains("No space left", ignoreCase = true)) {
            "ran out of storage space"
        } else {
            "update failed"
        }
    }

    @SuppressLint("NotifyDataSetChanged", "SetTextI18n")
    fun downloadSectional(
        chart: SectionalChart,
        progressBar: ProgressBar,
        downloadIcon: ImageView,
        downloadingIcon: ImageView,
        statusText: TextView,
    ) {
        if (!checkStorageOrWarn(listOf(chart))) return
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
                Log.e("DownloadPage", "Download failed for ${chart.name}: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    chart.isDownloading = false
                    downloadingIcon.visibility = View.GONE
                    downloadIcon.visibility = View.VISIBLE
                    progressBar.visibility = View.GONE
                    statusText.text = "Download Failed"
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this@DownloadActivity, friendlyErrorMessage(e).replaceFirstChar { it.uppercase() }, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Core download flow shared by the per-row click and the "Update stale charts"
     * button. Writes the catalog series into [ChartSeriesStore] on each successful
     * installation. Progress comes via [onProgress] (0–100, on Main).
     */
    @SuppressLint("SetTextI18n")
    private suspend fun doDownload(
        chart: SectionalChart,
        statusText: TextView? = null,
        onProgress: suspend (Int) -> Unit,
    ) {
        val hasSectional = chart.url.isNotEmpty()

        fun sizeBytesOf(mbString: String): Long =
            mbString.replace(Regex(" MB.*"), "").toFloatOrNull()?.toLong()?.times(1048576) ?: 0L

        val sectionalSizeBytes = if (hasSectional) sizeBytesOf(chart.fileSize) else 0L
        val terminalSizeBytes = chart.terminals.sumOf { sizeBytesOf(it.fileSize) }
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

        for (terminal in chart.terminals) {
            val label = if (chart.terminals.size > 1) "Downloading TAC (${terminal.name})" else "Downloading TAC"
            statusText?.let { withContext(Dispatchers.Main) { it.text = label } }
            val terminalFile = File(getDownloadStorageDir(), terminal.fileName)
            downloadChart(terminal.url, terminalFile, totalSizeBytes, totalBytesRead, onProgress)
            totalBytesRead += sizeBytesOf(terminal.fileSize)
            statusText?.let { withContext(Dispatchers.Main) { it.text = "Installing TAC" } }
            unzipFile(terminalFile, getTileStorageDir("Terminal"))
            terminalFile.delete()
            seriesStore.markInstalled(terminal.fileName, chart.latestSeries, chart.latestExpires)
        }
    }
}
