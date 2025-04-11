
package com.airportweather.map.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object DatabaseSyncUtils {

    suspend fun syncAirportDatabases(context: Context, prefs: SharedPreferences) {
        val dbKeys = getDatabaseKeysFromManifest()
        val manifest = getDatabaseManifest()

        if (manifest == null) {
            Log.e("DBSync", "Manifest not found, skipping sync.")
            return
        }

        for (key in dbKeys) {
            val dbInfo = manifest.optJSONObject(key) ?: continue
            val remoteVersion = dbInfo.optString("version", null)
            val localVersion = prefs.getString("${key}_version", null)

            if (remoteVersion == null) {
                Log.e("DBSync", "Missing version for $key")
                continue
            }

            if (localVersion == remoteVersion) {
                Log.d("DBSync", "$key is up to date (version $localVersion)")
                continue
            }

            Log.d("DBSync", "$key is outdated or missing, downloading...")
            val downloadSuccess = downloadDatabaseFileFromManifest(context, key, dbInfo)

            if (downloadSuccess) {
                prefs.edit()
                    .putString("${key}_version", remoteVersion)
                    .putString("${key}_sha256", dbInfo.optString("sha256"))
                    .apply()
            } else {
                Log.e("DBSync", "$key failed to download or verify")
            }
        }
    }

    private suspend fun getDatabaseManifest(): JSONObject? = withContext(Dispatchers.IO) {
        return@withContext try {
            val manifestUrl = "https://regiruk.netlify.app/sqlite/db_manifest.json"
            val conn = URL(manifestUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DBManifest", "HTTP ${conn.responseCode} while fetching manifest")
                null
            } else {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                JSONObject(response)
            }
        } catch (e: Exception) {
            Log.e("DBManifest", "Error fetching manifest: ${e.message}")
            null
        }
    }

    private suspend fun getDatabaseKeysFromManifest(): List<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            val manifestUrl = "https://regiruk.netlify.app/sqlite/db_manifest.json"
            val conn = URL(manifestUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("DBManifest", "Failed to fetch manifest: HTTP ${conn.responseCode}")
                emptyList()
            } else {
                val manifest = JSONObject(conn.inputStream.bufferedReader().readText())
                manifest.keys().asSequence().toList()
            }
        } catch (e: Exception) {
            Log.e("DBManifest", "Error loading keys: ${e.message}")
            emptyList()
        }
    }

    private suspend fun downloadDatabaseFileFromManifest(context: Context, key: String, dbInfo: JSONObject): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = dbInfo.getString("url")
                val expectedHash = dbInfo.getString("sha256")
                val fileName = "$key.db"
                val dbFile = context.getDatabasePath(fileName)

                if (dbFile.exists()) dbFile.delete()

                val success = downloadFile(url, dbFile)
                if (!success) return@withContext false

                val actualHash = getFileSha256(dbFile)
                val valid = actualHash == expectedHash

                if (!valid) {
                    Log.e("DBDownload", "Hash mismatch for $key: expected=$expectedHash actual=$actualHash")
                    dbFile.delete()
                }

                return@withContext valid
            } catch (e: Exception) {
                Log.e("DBDownload", "Exception while downloading $key: ${e.message}")
                false
            }
        }
    }

    private fun downloadFile(url: String, destination: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connect()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                conn.inputStream.use { input ->
                    destination.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d("DBDownload", "Downloaded ${destination.name}")
                true
            } else {
                Log.e("DBDownload", "HTTP ${conn.responseCode} for ${destination.name}")
                false
            }
        } catch (e: Exception) {
            Log.e("DBDownload", "Failed to download ${destination.name}: ${e.message}")
            false
        }
    }

    private fun getFileSha256(file: File): String {
        val buffer = ByteArray(1024)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            var read = fis.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = fis.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
