package com.airportweather.map

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class DownloadService : Service() {

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Download Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        const val CHANNEL_ID = "DownloadServiceChannel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_FILE_NAME = "fileName"
        const val EXTRA_DOWNLOAD_URL = "downloadUrl"
        const val EXTRA_TERMINAL_FILE_NAME = "terminalFileName"
        const val EXTRA_TERMINAL_DOWNLOAD_URL = "terminalDownloadUrl"
        const val EXTRA_FILE_SIZE = "fileSize"
        const val EXTRA_TERMINAL_FILE_SIZE = "terminalFileSize"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("DownloadDebug", "Download Service Started!")
        val fileName = intent?.getStringExtra(EXTRA_FILE_NAME) ?: return START_NOT_STICKY
        val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: return START_NOT_STICKY
        val terminalFileName = intent.getStringExtra(EXTRA_TERMINAL_FILE_NAME)
        val terminalDownloadUrl = intent.getStringExtra(EXTRA_TERMINAL_DOWNLOAD_URL)
        val fileSize = intent.getStringExtra(EXTRA_FILE_SIZE) ?: "0 MB"
        val terminalFileSize = intent.getStringExtra(EXTRA_TERMINAL_FILE_SIZE)

        startForeground(NOTIFICATION_ID, createNotification("Downloading $fileName"))
        lastNotificationProgress = 0

        serviceScope.launch {
            val sectionalDownloaded = downloadChart(
                fileName = fileName,
                url = downloadUrl,
                storageDir = "Sectional",
                fileSize = fileSize,
                terminalFileSize = terminalFileSize
            )
            if (sectionalDownloaded) {
                showCompletionNotification(fileName, "Sectional Download Complete")
            }

            if (terminalFileName != null && terminalDownloadUrl != null) {
                val terminalDownloaded = downloadChart(
                    fileName = terminalFileName,
                    url = terminalDownloadUrl,
                    storageDir = "Terminal",
                    fileSize = terminalFileSize ?: "0 MB",
                    terminalFileSize = null
                )
                if (terminalDownloaded) {
                    showCompletionNotification(terminalFileName, "Terminal Download Complete")
                }
            }

            stopSelf()  // ✅ Stop service when done
        }

        return START_STICKY
    }

    private fun downloadChart(
        fileName: String,
        url: String,
        storageDir: String,
        fileSize: String,
        terminalFileSize: String?
    ): Boolean {
        val file = File(getDownloadStorageDir(), fileName)
        var totalBytesRead = 0L
        //val totalSizeBytes: Long
        val sectionalSizeBytes = fileSize.replace(Regex(" MB.*"), "").toFloatOrNull()?.times(1048576)?.toLong() ?: 0L
        val terminalSizeBytes = terminalFileSize?.replace(Regex(" MB.*"), "")?.toFloatOrNull()?.times(1048576)?.toLong() ?: 0L
        val totalSizeBytes = sectionalSizeBytes + terminalSizeBytes

        val prefs = getSharedPreferences("download_status", MODE_PRIVATE)
        val editor = prefs.edit()

        try {
            createNotificationChannel() // ✅ Ensure notification channel exists
            showDownloadNotification(0, fileName) // ✅ Show initial progress notification

            val urlConnection = URL(url).openConnection() as HttpURLConnection
            urlConnection.connect()

            editor.putBoolean("downloading_$fileName", true).apply()
            sendProgressUpdate(fileName, "Downloading...", 0)

            file.outputStream().use { output ->
                urlConnection.inputStream.use { input ->
                    val buffer = ByteArray(4096)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        val progress = ((totalBytesRead.toFloat() / totalSizeBytes) * 100).toInt()
                        sendProgressUpdate(fileName, "Downloading...", progress)
                        showDownloadNotification(progress, fileName)
                    }
                }
            }

            sendProgressUpdate(fileName, "Installing...", 100)
            unzipFile(file, getTileStorageDir(storageDir))
            file.delete()

            sendProgressUpdate(fileName, "Download Complete!", 100)
            markSectionalAsInstalled(fileName)

            editor.remove("downloading_$fileName").apply()

            showCompletionNotification(fileName, "Download Complete")
            stopForeground(STOP_FOREGROUND_REMOVE)
            return true
        } catch (e: Exception) {
            sendProgressUpdate(fileName, "Download Failed", 0)
            editor.remove("downloading_$fileName").apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            return false
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
        }

        try {
            ZipInputStream(zipFile.inputStream()).use { zis ->
                var entry: ZipEntry?
                while (zis.nextEntry.also { entry = it } != null) {
                    val extractedFile = File(targetDirectory, entry!!.name)

                    if (entry!!.isDirectory) {
                        extractedFile.mkdirs()
                    } else {
                        extractedFile.parentFile?.mkdirs()
                        FileOutputStream(extractedFile).use { fos ->
                            zis.copyTo(fos)
                        }
                    }
                }
            }
            Log.d("Unzip", "Extraction complete!")
        } catch (e: Exception) {
            Log.e("Unzip", "Error during extraction", e)
        }
    }

    private fun markSectionalAsInstalled(fileName: String) {
        val prefs = getSharedPreferences("installed_sectionals", MODE_PRIVATE)
        val installedSet = prefs.getStringSet("sectionals", mutableSetOf())?.toMutableSet() ?: mutableSetOf()

        installedSet.add(fileName)
        prefs.edit().putStringSet("sectionals", installedSet).apply()

        Log.d("DownloadService", "Marked as installed: $installedSet")
    }

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Download Service")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showCompletionNotification(fileName: String, content: String) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content)
            .setContentText("$fileName downloaded successfully")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun showDownloadNotification(progress: Int, fileName: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)  // ✅ Uses existing CHANNEL_ID
            .setSmallIcon(android.R.drawable.stat_sys_download)  // ✅ Uses system download icon
            .setContentTitle("Downloading $fileName")
            .setContentText("Progress: $progress%")
            .setProgress(100, progress, false)
            .setOngoing(true)  // ✅ Prevents user from swiping it away
            .setPriority(NotificationCompat.PRIORITY_LOW)  // ✅ Avoids making noise
            .build()

        if (progress < lastNotificationProgress + 10 && progress != 100) return  // ✅ Update every 10%
        lastNotificationProgress = progress  // ✅ Track last progress update

        notificationManager.notify(NOTIFICATION_ID, notification)

    }

    private var lastProgressUpdateTime = 0L  // ✅ Track last update time
    private fun sendProgressUpdate(fileName: String, status: String, progress: Int) {
        val currentTime = System.currentTimeMillis()

        // ✅ Prevent excessive updates (limit to 500ms intervals)
        //if (currentTime - lastProgressUpdateTime < 50) return
        //lastProgressUpdateTime = currentTime

        Log.d("DownloadDebug", "🚀 Broadcasting progress update: $fileName, $status, $progress")

        val intent = Intent("DOWNLOAD_PROGRESS")
        intent.putExtra("fileName", fileName)
        intent.putExtra("status", status)
        intent.putExtra("progress", progress)

        //sendBroadcast(intent)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        //updateNotification(status, progress)

        Handler(Looper.getMainLooper()).post {
            DownloadActivity.instance?.updateUI(fileName, status, progress)
        }


        Log.d("DownloadDebug", "Sent progress update: $progress% - $fileName - $status")
    }

    private var lastNotificationProgress = -1  // ✅ Track last sent progress

    private fun updateNotification(content: String, progress: Int) {
        if (progress == lastNotificationProgress) return  // ✅ Prevent redundant updates
        lastNotificationProgress = progress

        val notificationManager = getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Downloading Charts")
            .setContentText("$content ($progress%)")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, false)  // ✅ Show progress in notification
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)  // ✅ Only updates when progress changes
    }


    override fun onBind(intent: Intent?): IBinder? = null
}
