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
            // Fall back to on-demand download — loadTileFromURL writes the result
            // to disk so subsequent visits hit the local cache.
            else -> loadTileFromURL(zoom, x, y, localFile)
        }
    }

    // base tiles (wall tiles) included in the app assets
    protected fun loadTileFromAssets(filePath: String): Tile? {
        return try {
            val inputStream = context.assets.open(filePath)
            val tileData = inputStream.use { it.readBytes() }
            Tile(tileSize, tileSize, tileData)
        } catch (_: Exception) {
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
            // Wall tiles for the wide zooms (bundled in app assets, no network).
            zoom in 4..7 -> loadTileFromAssets(wallFilePath)
            // Sectionals for chart zooms: prefer local cache, fall back to a
            // network download which writes the tile to disk for next time.
            zoom in 8..12 && sectionalFile.exists() -> loadTileFromFile(sectionalFile)
            zoom in 8..12 -> loadTileFromURL(zoom, x, y, sectionalFile)
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
            // On-demand download for terminal tiles. The server 404s tiles that
            // don't exist; loadTileFromURL returns null cleanly in that case.
            else -> loadTileFromURL(zoom, x, y, terminalFile)
        }
    }
}

class IfrTileProvider(context: Context) : BaseTileProvider(
    context,
    baseUrl = Endpoints.IFR_TILES,
    localFolder = "IFR"
) {
    override fun getTile(x: Int, y: Int, zoom: Int): Tile? {
        val ifrFile = File(context.filesDir, "tiles/IFR/$zoom/$x/$y.png")
        return when {
            ifrFile.exists() -> loadTileFromFile(ifrFile)
            else -> loadTileFromURL(zoom, x, y, ifrFile)
        }
    }
}
