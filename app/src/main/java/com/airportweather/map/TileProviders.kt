package com.airportweather.map

import android.content.Context
import android.util.Log
import com.google.android.gms.maps.model.Tile
import com.google.android.gms.maps.model.TileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

abstract class BaseTileProvider(
    protected val context: Context,
    protected val baseUrl: String,
    private val localFolder: String
) : TileProvider {
    private val tileSize = 256

    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val localFile = File(context.filesDir, "tiles/$localFolder/$zoom/$x/$y.png")

        return when {
            localFile.exists() -> loadTileFromFile(localFile)
            //MRK checkTileExists(baseUrl, zoom, x, y) -> loadTileFromURL(zoom, x, y, localFile)
            else -> null
        }
    }

    // base tiles (wall tiles) included in the app assets
    protected fun loadTileFromAssets(filePath: String): Tile? {
        return try {
            val inputStream = context.assets.open(filePath)
            val tileData = inputStream.use { it.readBytes() }
            Tile(tileSize, tileSize, tileData)
        } catch (e: Exception) {
            Log.e("TileProvider", "Tile not found in assets: $filePath")
            null
        }
    }

    // sectional tiles added by downloading
    protected fun loadTileFromFile(file: File): Tile? {
        return try {
            val tileData = file.readBytes()
            Tile(tileSize, tileSize, tileData)
        } catch (e: Exception) {
            Log.e("TileProvider", "Error loading tile from file: ${e.message}")
            null
        }
    }

    protected fun loadTileFromURL(zoom: Int, x: Int, y: Int, saveToFile: File): Tile? {
        val tileUrl = "$baseUrl/$zoom/$x/$y.png"
        return try {
            val connection = URL(tileUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val tileData = connection.inputStream.use { it.readBytes() }

                // Cache the tile locally for offline use
                saveToFile.parentFile?.mkdirs()
                saveToFile.writeBytes(tileData)

                Tile(tileSize, tileSize, tileData)
            } else {
                Log.e("TileProvider", "Tile not found at URL: $tileUrl")
                null
            }
        } catch (e: Exception) {
            Log.e("TileProvider", "Error loading tile from URL: ${e.message}")
            null
        }
    }

    protected fun checkTileExists(baseUrl: String, zoom: Int, x: Int, y: Int): Boolean {
        val tileUrl = "$baseUrl/$zoom/$x/$y.png"
        return try {
            val connection = URL(tileUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.connect()
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            false
        }
    }
}

class SectionalTileProvider(context: Context) : BaseTileProvider(
    context,
    baseUrl = Endpoints.SECTIONAL_TILES,
    localFolder = "Sectional"
) {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val wallFilePath = "tiles/$zoom/$x/$y.png"
        val sectionalFile = File(context.filesDir, "tiles/Sectional/$zoom/$x/$y.png")

        return when {
            zoom in 4..7 -> loadTileFromAssets(wallFilePath)
            zoom in 8..12 && sectionalFile.exists() -> loadTileFromFile(sectionalFile)
            else -> null
        }
    }
}

class TerminalTileProvider(context: Context) : BaseTileProvider(
    context,
    baseUrl = Endpoints.TERMINAL_TILES,
    localFolder = "Terminal"
) {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val terminalFile = File(context.filesDir, "tiles/Terminal/$zoom/$x/$y.png")
        return when {
            terminalFile.exists() -> loadTileFromFile(terminalFile)
            else -> null
        }
    }
}
