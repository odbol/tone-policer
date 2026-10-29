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
import com.odbol.tonepolicer.inference.RewriteSuggester
import com.odbol.tonepolicer.inference.ToneAnalyzer
import com.odbol.tonepolicer.inference.Suggestions
import com.odbol.tonepolicer.inference.ToneRequest
import com.odbol.tonepolicer.inference.ToneResult
import com.odbol.tonepolicer.overlay.OverlayController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Watches the focused editable field in every app, scores its text with the configured
 * [ToneAnalyzer], fetches rewrites from the [RewriteSuggester] on demand, and drives the glow / fix-it overlay.
 */
class ToneAccessibilityService : AccessibilityService(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: Prefs
    private lateinit var overlay: OverlayController
    private lateinit var analyzer: ToneAnalyzer
    private lateinit var suggester: RewriteSuggester

    private val analysisCache = LruCache<String, ToneResult>(128)
    private val suggestionCache = LruCache<String, Suggestions>(32)

    private var trackedNode: AccessibilityNodeInfo? = null
    private var trackedText = ""
    /** The request (text + screen context) that produced [latestResult]. */
    private var latestRequest: ToneRequest? = null
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
        analyzer = EngineFactory.createAnalyzer(prefs)
        suggester = EngineFactory.createSuggester(prefs)
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

        analyzeJob = scope.launch {
            delay(prefs.debounceMs)
            val request = ToneRequest(text, pkg, screenContext(node))
            analysisCache.get(request.cacheKey)?.let {
                onAnalyzed(request, it)
                return@launch
            }
            val result = try {
                analyzer.analyze(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tone analysis failed", e)
                prefs.lastError = "${analyzer.name}: ${e.message}"
                return@launch
            }
            analysisCache.put(request.cacheKey, result)
            onAnalyzed(request, result)
        }
    }

    private fun onAnalyzed(request: ToneRequest, result: ToneResult) {
        if (request.text != trackedText) return
        latestRequest = request
        latestResult = result
        render()
    }

    /** Reads the surrounding on-screen text off the main thread; the node tree walk is many binder calls. */
    private suspend fun screenContext(node: AccessibilityNodeInfo): String {
        if (!prefs.includeScreenContext) return ""
        return withContext(Dispatchers.Default) {
            try {
                ScreenContextExtractor.extract(node)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Couldn't read screen context", e)
                ""
            }
        }
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
        val request = latestRequest ?: return
        val text = request.text
        suggestionCache.get(request.cacheKey)?.let {
            overlay.showPanelSuggestions(result, it)
            return
        }
        overlay.showPanelLoading(result)
        suggestJob?.cancel()
        suggestJob = scope.launch {
            try {
                val suggestions = suggester.suggest(request, result)
                suggestionCache.put(request.cacheKey, suggestions)
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
        latestRequest = null
        latestResult = null
        if (::overlay.isInitialized) overlay.hide()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            in Prefs.ENGINE_KEYS -> {
                analyzer.close()
                suggester.close()
                analyzer = EngineFactory.createAnalyzer(prefs)
                suggester = EngineFactory.createSuggester(prefs)
                analysisCache.evictAll()
                suggestionCache.evictAll()
                // Re-score whatever is on screen with the new backends.
                trackedText = ""
            }
            Prefs.KEY_ENABLED -> if (!prefs.monitoringEnabled) clearTracking()
            Prefs.KEY_THRESHOLD, Prefs.KEY_SHOW_GOOD -> render()
            Prefs.KEY_SCREEN_CONTEXT -> trackedText = ""
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
            analyzer.close()
            suggester.close()
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ToneService"
        const val MIN_CHARS = 4
        const val REPOSITION_THROTTLE_MS = 50L
    }
}
