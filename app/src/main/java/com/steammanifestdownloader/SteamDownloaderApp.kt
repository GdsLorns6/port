package com.steammanifestdownloader

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class SteamDownloaderApp : Application() {

    companion object {
        const val SERVER_PORT = 8899
        @Volatile
        var isServerRunning = false
    }

    override fun onCreate() {
        super.onCreate()
        AndroidBridgeManager.appContext = this

        // Create notification channels on startup
        NotificationHelper.createNotificationChannels(this)

        // Initialize Chaquopy CPython 3.11 runtime
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Prepare web assets on internal storage for Python HTTP server
        extractWebAssetsIfNeeded()

        // Launch Python backend server
        startPythonBackend()
    }

    private fun extractWebAssetsIfNeeded() {
        val webDir = File(filesDir, "web")
        if (!webDir.exists()) {
            webDir.mkdirs()
        }
        copyAssetFolder("web", webDir)
    }

    private fun copyAssetFolder(assetPath: String, targetDir: File) {
        try {
            val assetsList = assets.list(assetPath) ?: return
            if (assetsList.isEmpty()) {
                // It's a file
                assets.open(assetPath).use { input ->
                    FileOutputStream(targetDir).use { output ->
                        input.copyTo(output)
                    }
                }
            } else {
                // It's a directory
                if (!targetDir.exists()) targetDir.mkdirs()
                for (item in assetsList) {
                    val subAsset = if (assetPath.isEmpty()) item else "$assetPath/$item"
                    val subTarget = File(targetDir, item)
                    copyAssetFolder(subAsset, subTarget)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startPythonBackend() {
        if (isServerRunning) return

        thread(isDaemon = true, name = "PythonBackendServer") {
            try {
                val py = Python.getInstance()

                // Configure Android paths in Python environment
                val osModule = py.getModule("os")
                val environ = osModule.get("environ")
                val dataDir = File(filesDir, "smd_data").apply { mkdirs() }
                val webDir = File(filesDir, "web")

                environ?.callAttr("__setitem__", "SMD_DATA_DIR", dataDir.absolutePath)
                environ?.callAttr("__setitem__", "SMD_WEB_DIR", webDir.absolutePath)
                environ?.callAttr("__setitem__", "SMD_DOWNLOAD_DIR", FileUtils.getDefaultDownloadDirectory())
                environ?.callAttr("__setitem__", "IS_ANDROID_APP", "1")

                isServerRunning = true
                val appModule = py.getModule("app")
                appModule.callAttr("run_server", SERVER_PORT)
            } catch (e: Exception) {
                isServerRunning = false
                e.printStackTrace()
            }
        }
    }
}
