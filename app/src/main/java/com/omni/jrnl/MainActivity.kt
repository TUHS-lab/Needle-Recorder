package com.omni.jrnl

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG     = "MainActivity"
        private const val APP_URL = "file:///android_asset/design/app.html"
    }

    private lateinit var webView: WebView
    private lateinit var recorderManager: RecorderManager
    private lateinit var webAppInterface: WebAppInterface

    private val viewModel: MainViewModel by viewModels()

    // ─── Safe JS queue ───────────────────────────────────────────────────────
    @Volatile private var isWebViewReady = false
    private val jsQueue = mutableListOf<String>()


    // ─── Pending permission action ────────────────────────────────────────────
    private var pendingAction: (() -> Unit)? = null

    // ─── Permission launchers ─────────────────────────────────────────────────

    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "RECORD_AUDIO result: isGranted=$isGranted")
        if (isGranted) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(this, getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
            evaluateJs("onPermissionDenied()")
        }
        pendingAction = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "POST_NOTIFICATIONS result: isGranted=$isGranted")
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setupEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Повторно после layout: на части устройств контраст/«тёмные иконки»
        // для светлого фона применяются только после attach декора.
        window.decorView.post { applySystemBarAppearance() }

        Log.d(TAG, "onCreate() isRestoring=${viewModel.isRestoring()}")

        // ─── Back press: навигация внутри приложения, а не закрытие ────────────
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // JS-роутер сам решает: вернуться на предыдущий экран
                // или вызвать Android.closeApp() если уже на главном.
                evaluateJs("handleBackPress()")
            }
        })

        requestNotificationPermissionIfNeeded()
        cleanUpStaleRecordingService()

        recorderManager = RecorderManager(this)
        setupWebInterface()
        setupWebView()

        Log.d(TAG, "Loading SPA: $APP_URL")
        webView.loadUrl(APP_URL)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")

        if (recorderManager.isRecording) {
            Log.w(TAG, "onDestroy: recording active — stopping")
            viewModel.wasRecording = true
            recorderManager.stopRecording()
            RecordingService.stop(this)
        } else {
            viewModel.wasRecording = false
        }

        webAppInterface.release()
        recorderManager.release()

        webView.apply {
            stopLoading()
            clearHistory()
            removeAllViews()
            destroy()
        }

        super.onDestroy()
    }

    // ─── Edge-to-edge ─────────────────────────────────────────────────────────

    private fun setupEdgeToEdge() {
        // Контент рисуется под статус-баром и нижней навигацией
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Статус-бар совпадает с фоном приложения — иконки остаются в системной области,
        // но не «теряются» на прозрачном сквозь WebView.
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor     = Color.parseColor("#EAE6DF")
            window.navigationBarColor = Color.TRANSPARENT
        }

        // Разделитель над gesture bar — убираем, чтобы полоса не «отрезалась» визуально
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.navigationBarDividerColor = Color.TRANSPARENT
        }

        applySystemBarAppearance()
    }

    private fun applySystemBarAppearance() {
        // Светлый фон → тёмные (чёрные) иконки в статус- и навигационной панели
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }

    // ─── Setup ────────────────────────────────────────────────────────────────

    private fun setupWebInterface() {
        webAppInterface = WebAppInterface(
            context = this,
            recorderManager = recorderManager,

            onRequestRecordPermission = {
                // КРИТИЧНО: launcher.launch() вызывается только из UI-потока.
                // @JavascriptInterface методы вызываются из потока WebView → нужен runOnUiThread.
                runOnUiThread {
                    pendingAction = { webAppInterface.doStartRecording() }
                    checkAndRequestAudioPermission()
                }
            },

            onRecordingStarted = {
                Log.d(TAG, "Callback: onRecordingStarted")
                viewModel.wasRecording = true
                runOnUiThread { RecordingService.start(this) }
                evaluateJs("onRecordingStarted()")
            },

            onRecordingStopped = { filePath ->
                Log.d(TAG, "Callback: onRecordingStopped → $filePath")
                viewModel.wasRecording = false
                runOnUiThread { RecordingService.stop(this) }
                val escaped = filePath.replace("\\", "\\\\").replace("'", "\\'")
                evaluateJs("onRecordingStopped('$escaped')")
            },

            // Kotlin-инициированная навигация (восстановление состояния).
            // JS-навигация между экранами происходит ТОЛЬКО через navigate() в JS —
            // не через этот bridge, чтобы не создавать бесконечный цикл.
            onNavigateTo = { view ->
                Log.d(TAG, "Callback: onNavigateTo view=$view")
                viewModel.currentView = view
                evaluateJs("__navigate('$view')")
            },

            onPlaybackStarted = { durationSec ->
                Log.d(TAG, "Callback: onPlaybackStarted durationSec=$durationSec")
                evaluateJs("onPlaybackStarted($durationSec)")
            },

            onPlaybackFinished = {
                Log.d(TAG, "Callback: onPlaybackFinished")
                evaluateJs("onPlaybackFinished()")
            },

            onSystemPause = {
                Log.d(TAG, "Callback: onSystemPause")
                evaluateJs("onPlaybackPaused()")
            }
        )
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webView)

        // ── Insets → применяем к КОНТЕЙНЕРУ (FrameLayout), не к WebView ──────
        // WebView.setPadding() не двигает web-контент — HTML всегда рендерится
        // с (0,0). Но FrameLayout.setPadding() сдвигает дочерний WebView нативно.
        // Результат: WebView начинается ниже статус-бара; web-контент не требует
        // CSS-компенсации. Фон в зазоре = android:background FrameLayout = cream.
        val container = findViewById<FrameLayout>(R.id.container)
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            Log.d(TAG, "Container insets applied: top=${bars.top} bottom=${bars.bottom}")
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(container)

        WebView.setWebContentsDebuggingEnabled(true)

        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            allowFileAccess                  = true
            allowContentAccess               = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.addJavascriptInterface(webAppInterface, "Android")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return !url.startsWith("file:///android_asset/")
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "onPageFinished: $url | queued=${jsQueue.size}")
                isWebViewReady = true

                webView.post {
                    if (viewModel.isRestoring()) {
                        restoreStateAfterRecreation()
                    }
                    jsQueue.forEach { webView.evaluateJavascript(it, null) }
                    jsQueue.clear()
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                super.onReceivedError(view, request, error)
                Log.e(TAG, "WebView error ${error.errorCode}: ${error.description} → ${request.url}")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                Log.d(TAG, "JS [${msg.messageLevel()}]: ${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
                return true
            }
        }
    }

    // ─── State restoration ────────────────────────────────────────────────────

    private fun restoreStateAfterRecreation() {
        Log.d(TAG, "restoreState: view=${viewModel.currentView}, path=${viewModel.currentPlaybackPath}")
        viewModel.currentPlaybackPath?.let { path ->
            val escaped = path.replace("'", "\\'")
            webView.evaluateJavascript("localStorage.setItem('currentPlayback','$escaped')", null)
        }
        val view = viewModel.currentView
        if (view != "home") {
            webView.evaluateJavascript("__navigate('$view')", null)
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Безопасный вызов JS из любого потока.
     * Буферизует вызовы до onPageFinished через webView.post().
     */
    private fun evaluateJs(script: String) {
        webView.post {
            if (isWebViewReady) {
                Log.d(TAG, "evaluateJs: $script")
                webView.evaluateJavascript(script, null)
            } else {
                Log.d(TAG, "Queuing JS: $script")
                jsQueue.add(script)
            }
        }
    }

    private fun checkAndRequestAudioPermission() {
        val permission = Manifest.permission.RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "RECORD_AUDIO already granted")
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Log.d(TAG, "Requesting RECORD_AUDIO")
            recordPermissionLauncher.launch(permission)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Requesting POST_NOTIFICATIONS")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun cleanUpStaleRecordingService() {
        if (RecordingService.isRunning) {
            Log.w(TAG, "Stale RecordingService — stopping")
            RecordingService.stop(this)
        }
        if (viewModel.wasRecording) {
            Log.w(TAG, "Previous session was recording — cleared")
            viewModel.wasRecording = false
        }
    }
}
