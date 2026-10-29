package com.odbol.tonepolicer.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import com.odbol.tonepolicer.Prefs
import com.odbol.tonepolicer.R
import com.odbol.tonepolicer.inference.EngineFactory
import com.odbol.tonepolicer.inference.InferenceEngine
import com.odbol.tonepolicer.inference.Suggestions
import com.odbol.tonepolicer.inference.ToneRequest
import com.odbol.tonepolicer.inference.ToneResult
import com.odbol.tonepolicer.overlay.OverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches the focused editable field in every app, scores its text with the configured
 * [InferenceEngine], and drives the glow / fix-it overlay.
 */
class ToneAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private lateinit var engine: InferenceEngine

    private val analysisCache = LruCache<String, ToneResult>(128)
    private val suggestionCache = LruCache<String, Suggestions>(32)

    private var trackedNode: AccessibilityNodeInfo? = null
    private var trackedText = ""
    private var trackedPackage = ""
    private var latestResult: ToneResult? = null
    private var analyzeJob: Job? = null
    private var suggestJob: Job? = null
    private val bounds = Rect()

    private var repositionPending = false
    private val repositionRunnable = Runnable {
        repositionPending = false
        reposition()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        prefs.register(this)
        engine = EngineFactory.create(prefs)
        overlay = OverlayController(
            context = this,
            onFixClicked = ::onFixClicked,
            onSuggestionChosen = ::applySuggestion,
            onCopy = ::copyToClipboard,
            onPanelDismissed = { suggestJob?.cancel() },
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!::overlay.isInitialized) return
        if (!prefs.monitoringEnabled) {
            clearTracking()
            return
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> onFieldEvent(event)
            else -> scheduleReposition()
        }
    }

    private fun onFieldEvent(event: AccessibilityEvent) {
        val node = event.source ?: return
        if (!node.isEditable) {
            scheduleReposition()
            return
        }
        val pkg = event.packageName?.toString().orEmpty()
        if (!shouldMonitor(node, pkg)) {
            clearTracking()
            return
        }

        val text = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        val sameField = node == trackedNode
        trackedNode = node
        trackedPackage = pkg

        if (sameField && text == trackedText) {
            scheduleReposition()
            return
        }
        trackedText = text
        analyzeJob?.cancel()
        suggestJob?.cancel()
        overlay.hidePanel()

        if (text.isBlank() || text.trim().length < MIN_CHARS) {
            latestResult = null
            overlay.hide()
            return
        }
        if (!sameField) {
            latestResult = null
            overlay.hide()
        }

        val request = ToneRequest(text, pkg)
        val cached = analysisCache.get(text)
        if (cached != null) {
            onAnalyzed(text, cached)
            return
        }
        analyzeJob = scope.launch {
            delay(prefs.debounceMs)
            val result = try {
                engine.analyze(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tone analysis failed", e)
                prefs.lastError = "${engine.name}: ${e.message}"
                return@launch
            }
            analysisCache.put(text, result)
            onAnalyzed(text, result)
        }
    }

    private fun onAnalyzed(text: String, result: ToneResult) {
        if (text != trackedText) return
        latestResult = result
        render()
    }

    private fun shouldMonitor(node: AccessibilityNodeInfo, pkg: String): Boolean {
        if (node.isPassword) return false
        // Don't police the keyboard's own search / emoji fields.
        if (node.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false
        // In our own app, only the "Try it" box is monitored (not the API key / settings fields).
        if (pkg == packageName) return node.viewIdResourceName?.endsWith(":id/tone_test_input") == true
        return true
    }

    private fun render() {
        val node = trackedNode ?: return
        val result = latestResult ?: return
        if (!node.refresh()) {
            clearTracking()
            return
        }
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || !node.isVisibleToUser) {
            overlay.hide()
            return
        }
        val flagged = result.severity >= prefs.flagThreshold
        overlay.show(bounds, result, showBorder = flagged || prefs.showGoodBorder, showButton = flagged)
    }

    private fun scheduleReposition() {
        if (trackedNode == null || repositionPending) return
        repositionPending = true
        handler.postDelayed(repositionRunnable, REPOSITION_THROTTLE_MS)
    }

    /** Called on scroll / layout / window changes to follow (or drop) the tracked field. */
    private fun reposition() {
        val node = trackedNode ?: return
        if (!node.refresh() || !node.isFocused) {
            clearTracking()
            return
        }
        if (latestResult == null) return
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty || !node.isVisibleToUser) {
            overlay.hide()
        } else {
            render()
        }
    }

    private fun onFixClicked() {
        val result = latestResult ?: return
        val text = trackedText
        suggestionCache.get(text)?.let {
            overlay.showPanelSuggestions(result, it)
            return
        }
        overlay.showPanelLoading(result)
        suggestJob?.cancel()
        suggestJob = scope.launch {
            try {
                val suggestions = engine.suggest(ToneRequest(text, trackedPackage), result)
                suggestionCache.put(text, suggestions)
                if (text == trackedText) overlay.showPanelSuggestions(result, suggestions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Suggestion request failed", e)
                if (text == trackedText) {
                    overlay.showPanelError(result, e.message ?: "Couldn't get suggestions.")
                }
            }
        }
    }

    private fun applySuggestion(replacement: String) {
        overlay.hidePanel()
        val node = trackedNode
        val replaced = node != null && node.refresh() && node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, replacement)
            },
        )
        if (replaced) {
            // Put the cursor at the end so the user can keep typing.
            node?.performAction(
                AccessibilityNodeInfo.ACTION_SET_SELECTION,
                Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, replacement.length)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, replacement.length)
                },
            )
        } else {
            // Some apps (custom editors, web views) ignore ACTION_SET_TEXT.
            copyToClipboard(replacement)
        }
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private fun clearTracking() {
        analyzeJob?.cancel()
        suggestJob?.cancel()
        trackedNode = null
        trackedText = ""
        latestResult = null
        if (::overlay.isInitialized) overlay.hide()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            in Prefs.ENGINE_KEYS -> {
                engine.close()
                engine = EngineFactory.create(prefs)
                analysisCache.evictAll()
                suggestionCache.evictAll()
                // Re-score whatever is on screen with the new engine.
                trackedText = ""
            }
            Prefs.KEY_ENABLED -> if (!prefs.monitoringEnabled) clearTracking()
            Prefs.KEY_THRESHOLD, Prefs.KEY_SHOW_GOOD -> render()
        }
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.hide()
    }

    override fun onDestroy() {
        handler.removeCallbacks(repositionRunnable)
        scope.cancel()
        if (::overlay.isInitialized) {
            overlay.hide()
            prefs.unregister(this)
            engine.close()
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ToneService"
        const val MIN_CHARS = 4
        const val REPOSITION_THROTTLE_MS = 50L
    }
}
