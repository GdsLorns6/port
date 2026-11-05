package com.steammanifestdownloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.webkit.JavascriptInterface

class AndroidBridge(private val activity: MainActivity) {

    @JavascriptInterface
    fun isAndroid(): Boolean = true

    @JavascriptInterface
    fun getAppVersion(): String = "1.5.0-android"

    @JavascriptInterface
    fun getDefaultDownloadDir(): String {
        return FileUtils.getDefaultDownloadDirectory()
    }

    @JavascriptInterface
    fun pickDirectory() {
        activity.runOnUiThread {
            activity.launchDirectoryPicker()
        }
    }

    @JavascriptInterface
    fun openFolder(path: String) {
        activity.runOnUiThread {
            FileUtils.openFolder(activity, path)
        }
    }

    @JavascriptInterface
    fun vibrate(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs.coerceIn(10, 1000), VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = activity.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs.coerceIn(10, 1000))
            }
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun isBatteryExempt(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(activity.packageName)
        } else {
            true
        }
    }

    @JavascriptInterface
    fun requestBatteryExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isBatteryExempt()) {
            activity.runOnUiThread {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val genericIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        activity.startActivity(genericIntent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    @JavascriptInterface
    fun onDownloadEvent(payloadJson: String) {
        AndroidBridgeManager.onDownloadEvent("download-progress", payloadJson)
    }

    @JavascriptInterface
    fun cancelDownload(jobId: String) {
        val intent = Intent(activity, SteamDownloaderService::class.java).apply {
            action = SteamDownloaderService.ACTION_CANCEL_DOWNLOAD
            putExtra(SteamDownloaderService.EXTRA_JOB_ID, jobId)
        }
        activity.startService(intent)
    }
}
