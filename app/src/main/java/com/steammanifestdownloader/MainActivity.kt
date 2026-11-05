package com.steammanifestdownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var loadingContainer: FrameLayout
    private lateinit var loadingText: TextView
    private lateinit var progressBar: ProgressBar

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            handleDirectoryPicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidBridgeManager.activeActivity = this

        setupLayout()
        checkPermissions()
        startDownloadService()
        waitForServerAndLoad()
        setupBackHandler()
    }

    private fun setupLayout() {
        // Root layout
        val rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#171a21"))
        }

        // WebView
        webView = WebView(this).apply {
            setBackgroundColor(Color.parseColor("#171a21"))
            visibility = View.INVISIBLE
            setupWebView(this)
        }
        rootLayout.addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        // Loading container
        loadingContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#171a21"))
        }
        val innerLoading = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        progressBar = ProgressBar(this).apply {
            indeterminateTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.steam_accent)
        }
        loadingText = TextView(this).apply {
            text = "Starting Steam Manifest Downloader…"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 32, 0, 0)
        }
        innerLoading.addView(progressBar)
        innerLoading.addView(loadingText)
        loadingContainer.addView(innerLoading)
        rootLayout.addView(loadingContainer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        setContentView(rootLayout)

        // Apply edge-to-edge window insets
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true

        wv.addJavascriptInterface(AndroidBridge(this), "AndroidBridge")

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject Android CSS marker and bridge helpers
                wv.evaluateJavascript(
                    """
                    document.documentElement.classList.add('is-android');
                    window.isAndroidApp = true;
                    """.trimIndent(), null
                )
                loadingContainer.visibility = View.GONE
                wv.visibility = View.VISIBLE
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                return super.onConsoleMessage(consoleMessage)
            }
        }
    }

    private fun checkPermissions() {
        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // All Files Access prompt for Android 11+ if external storage needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun startDownloadService() {
        val serviceIntent = Intent(this, SteamDownloaderService::class.java).apply {
            action = SteamDownloaderService.ACTION_START_SERVICE
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun waitForServerAndLoad() {
        val targetUrl = "http://127.0.0.1:${SteamDownloaderApp.SERVER_PORT}/index.html"

        thread(isDaemon = true) {
            var ready = false
            var attempts = 0
            while (!ready && attempts < 60) {
                attempts++
                try {
                    val connection = URL(targetUrl).openConnection() as HttpURLConnection
                    connection.connectTimeout = 500
                    connection.readTimeout = 500
                    connection.requestMethod = "HEAD"
                    val code = connection.responseCode
                    if (code in 200..399) {
                        ready = true
                    }
                    connection.disconnect()
                } catch (_: Exception) {
                    Thread.sleep(300)
                }
            }

            runOnUiThread {
                if (ready) {
                    webView.loadUrl(targetUrl)
                } else {
                    loadingText.text = "Error starting local backend server. Please restart app."
                }
            }
        }
    }

    fun launchDirectoryPicker() {
        directoryPickerLauncher.launch(null)
    }

    private fun handleDirectoryPicked(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}

        val doc = DocumentFile.fromTreeUri(this, uri)
        val path = doc?.let { resolveDocumentPath(uri) } ?: uri.toString()

        // Send chosen path to webview javascript
        val safePath = path.replace("\\", "\\\\").replace("'", "\\'")
        val js = """
            if (window.__onAndroidDirectoryPicked) {
                window.__onAndroidDirectoryPicked('$safePath');
            }
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun resolveDocumentPath(uri: Uri): String {
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":")
            if (parts.size >= 2) {
                val type = parts[0]
                val relativePath = parts[1]
                if ("primary".equals(type, ignoreCase = true)) {
                    return "/storage/emulated/0/$relativePath"
                } else {
                    return "/storage/$type/$relativePath"
                }
            }
        } catch (_: Exception) {}
        return uri.path ?: uri.toString()
    }

    fun handleBridgeEvent(eventName: String, payload: JSONObject) {
        // Forward to WebView JavaScript if needed
        val jsonStr = payload.toString().replace("'", "\\'")
        webView.evaluateJavascript(
            "window.__TAURI__ && window.__TAURI__.event && window.__TAURI__.event.emit('$eventName', JSON.parse('$jsonStr'));",
            null
        )
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    // Send escape key / modal close event to JS first
                    webView.evaluateJavascript(
                        """
                        (function() {
                            const escEvent = new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true });
                            document.dispatchEvent(escEvent);
                        })();
                        """.trimIndent(), null
                    )
                    // Move task to back instead of killing app (keeps downloads alive)
                    moveTaskToBack(true)
                }
            }
        })
    }

    override fun onDestroy() {
        AndroidBridgeManager.activeActivity = null
        super.onDestroy()
    }
}
