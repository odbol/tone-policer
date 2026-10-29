package com.odbol.tonepolicer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.google.mlkit.genai.common.DownloadStatus
import com.odbol.tonepolicer.Backend
import com.odbol.tonepolicer.Prefs
import com.odbol.tonepolicer.R
import com.odbol.tonepolicer.inference.EngineFactory
import com.odbol.tonepolicer.inference.OnDeviceGeminiEngine
import com.odbol.tonepolicer.inference.ToneRequest
import com.odbol.tonepolicer.service.ToneAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private val onDevice = OnDeviceGeminiEngine()
    private var downloadJob: Job? = null

    private lateinit var serviceStatus: TextView
    private lateinit var onDeviceStatus: TextView
    private lateinit var downloadButton: Button
    private lateinit var thresholdLabel: TextView
    private lateinit var checkResult: TextView
    private lateinit var lastError: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        applySystemBarInsets(findViewById(R.id.root))

        serviceStatus = findViewById(R.id.service_status)
        onDeviceStatus = findViewById(R.id.on_device_status)
        downloadButton = findViewById(R.id.download_model)
        thresholdLabel = findViewById(R.id.threshold_label)
        checkResult = findViewById(R.id.check_result)
        lastError = findViewById(R.id.last_error)

        findViewById<Button>(R.id.open_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Switch>(R.id.monitoring_enabled).apply {
            isChecked = prefs.monitoringEnabled
            setOnCheckedChangeListener { _, checked -> prefs.monitoringEnabled = checked }
        }

        bindBackendPicker()
        bindCloudFields()
        bindBehavior()

        downloadButton.setOnClickListener { downloadModel() }
        findViewById<Button>(R.id.refresh_status).setOnClickListener { refreshOnDeviceStatus() }
        findViewById<Button>(R.id.check_now).setOnClickListener { checkNow() }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        refreshOnDeviceStatus()
        val error = prefs.lastError
        lastError.visibility = if (error.isNullOrBlank()) View.GONE else View.VISIBLE
        lastError.text = "Last error: $error"
    }

    override fun onDestroy() {
        scope.cancel()
        onDevice.close()
        super.onDestroy()
    }

    private fun bindBackendPicker() {
        val ids = mapOf(
            Backend.AUTO to R.id.backend_auto,
            Backend.ON_DEVICE to R.id.backend_on_device,
            Backend.CLOUD to R.id.backend_cloud,
            Backend.HEURISTIC to R.id.backend_heuristic,
        )
        val group = findViewById<RadioGroup>(R.id.backend_group)
        group.check(ids.getValue(prefs.backend))
        group.setOnCheckedChangeListener { _, checkedId ->
            ids.entries.firstOrNull { it.value == checkedId }?.let { prefs.backend = it.key }
        }
    }

    private fun bindCloudFields() {
        findViewById<EditText>(R.id.api_key).apply {
            setText(prefs.apiKey)
            addTextChangedListener(afterChanged { prefs.apiKey = it })
        }
        findViewById<EditText>(R.id.cloud_model).apply {
            setText(prefs.cloudModel)
            addTextChangedListener(afterChanged { prefs.cloudModel = it })
        }
    }

    private fun bindBehavior() {
        val seekBar = findViewById<SeekBar>(R.id.threshold)
        seekBar.progress = prefs.flagThreshold - MIN_THRESHOLD
        thresholdLabel.text = getString(R.string.flag_threshold, prefs.flagThreshold)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                thresholdLabel.text = getString(R.string.flag_threshold, progress + MIN_THRESHOLD)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) {
                prefs.flagThreshold = bar.progress + MIN_THRESHOLD
            }
        })

        findViewById<Switch>(R.id.show_good_border).apply {
            isChecked = prefs.showGoodBorder
            setOnCheckedChangeListener { _, checked -> prefs.showGoodBorder = checked }
        }

        findViewById<Switch>(R.id.include_screen_context).apply {
            isChecked = prefs.includeScreenContext
            setOnCheckedChangeListener { _, checked -> prefs.includeScreenContext = checked }
        }
    }

    private fun updateServiceStatus() {
        val am = getSystemService(AccessibilityManager::class.java)
        val ours = ComponentName(this, ToneAccessibilityService::class.java)
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName.unflattenFromString(it.id) == ours }
        serviceStatus.setText(if (enabled) R.string.service_enabled else R.string.service_disabled)
    }

    private fun refreshOnDeviceStatus() {
        scope.launch {
            val status = onDevice.status()
            if (downloadJob?.isActive != true) onDeviceStatus.text = status.description
        }
    }

    private fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            var total = 0L
            try {
                onDevice.download().collect { status ->
                    onDeviceStatus.text = when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            total = status.bytesToDownload
                            "Downloading Gemini Nano…"
                        }
                        is DownloadStatus.DownloadProgress -> {
                            val done = status.totalBytesDownloaded
                            if (total > 0) "Downloading… ${done * 100 / total}%" else "Downloading… ${done / 1_000_000} MB"
                        }
                        is DownloadStatus.DownloadCompleted -> "Ready"
                        is DownloadStatus.DownloadFailed -> "Download failed: ${status.e.message}"
                        else -> onDeviceStatus.text
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onDeviceStatus.text = "Download failed: ${e.message}"
            }
        }
    }

    /** Runs the configured engine directly, so the backend can be tested without the overlay. */
    private fun checkNow() {
        val text = findViewById<EditText>(R.id.tone_test_input).text.toString()
        if (text.isBlank()) return
        // Stands in for the on-screen conversation the service reads in other apps.
        val theirMessage = findViewById<EditText>(R.id.tone_test_context).text.toString().trim()
        val context = if (theirMessage.isEmpty()) "" else "[left] $theirMessage"
        checkResult.text = "Checking…"
        scope.launch {
            val engine = EngineFactory.create(prefs)
            try {
                val request = ToneRequest(text, packageName, context)
                val result = engine.analyze(request)
                val suggestions = engine.suggest(request, result)
                checkResult.text = buildString {
                    appendLine("${result.label}: ${result.severity}/100 (${result.engine})")
                    if (result.categories.isNotEmpty()) appendLine(result.categoryLabel)
                    if (result.reason.isNotBlank()) appendLine(result.reason)
                    result.missing.forEach { appendLine("☐ $it") }
                    appendLine()
                    suggestions.rewrites.forEach { appendLine("• ${it.style}: ${it.text}") }
                    if (suggestions.tip.isNotBlank()) append(suggestions.tip)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                checkResult.text = "Error: ${e.message}"
            } finally {
                engine.close()
            }
        }
    }

    private fun applySystemBarInsets(root: View) {
        val base = root.paddingTop
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, base + bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    base + insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    private fun afterChanged(block: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = block(s?.toString().orEmpty())
    }

    private companion object {
        const val MIN_THRESHOLD = 10
    }
}
