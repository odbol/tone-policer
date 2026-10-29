package com.odbol.tonepolicer.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.odbol.tonepolicer.R
import com.odbol.tonepolicer.inference.Suggestions
import com.odbol.tonepolicer.inference.ToneResult

/**
 * Owns the three accessibility-overlay windows: the glow border (not touchable), the
 * "Fix tone" button above the field, and the suggestions card.
 *
 * Must be created from an AccessibilityService context so TYPE_ACCESSIBILITY_OVERLAY is allowed
 * without the SYSTEM_ALERT_WINDOW permission. All coordinates are in screen space.
 */
class OverlayController(
    private val context: Context,
    private val onFixClicked: () -> Unit,
    private val onSuggestionChosen: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onPanelDismissed: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    private val border = GlowBorderView(context)
    private val button = TextView(context).apply {
        setText(R.string.fix_tone)
        textSize = 13f
        setTextColor(0xFF1B1B1F.toInt())
        setPadding(dp(12), dp(6), dp(12), dp(6))
        elevation = 4 * density
        setOnClickListener { onFixClicked() }
    }
    private val panel = SuggestionPanel(context, onSuggestionChosen, onCopy, onDismiss = {
        hidePanel()
        onPanelDismissed()
    })

    private val borderParams = params(touchable = false)
    private val buttonParams = params(touchable = true)
    private val panelParams = params(touchable = true).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }

    private val attached = mutableSetOf<View>()
    private val anchor = Rect()
    private var showBorder = false
    private var showButton = false

    val isPanelShowing: Boolean get() = panel in attached

    /** Shows (or updates) the border and button for a field at [bounds]. */
    fun show(bounds: Rect, result: ToneResult, showBorder: Boolean, showButton: Boolean) {
        this.showBorder = showBorder
        this.showButton = showButton
        val color = SeverityColors.colorFor(result.severityFraction)
        border.setSeverity(result.severityFraction)
        button.background = GradientDrawable().apply {
            cornerRadius = 100 * density
            setColor(color)
        }
        move(bounds)
    }

    /** Re-anchors everything to the field's new on-screen position. */
    fun move(bounds: Rect) {
        anchor.set(bounds)
        val (screenW, screenH) = screenSize()

        if (showBorder) {
            val pad = border.glowPadding
            borderParams.x = bounds.left - pad
            borderParams.y = bounds.top - pad
            borderParams.width = bounds.width() + 2 * pad
            borderParams.height = bounds.height() + 2 * pad
            place(border, borderParams)
        } else {
            remove(border)
        }

        if (showButton) {
            button.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            val w = button.measuredWidth
            val h = button.measuredHeight
            buttonParams.width = w
            buttonParams.height = h
            buttonParams.x = (bounds.right - w).coerceIn(0, (screenW - w).coerceAtLeast(0))
            // Sit just above the field's top-right corner; tuck inside if there's no room above.
            val above = bounds.top - h - dp(6)
            buttonParams.y = if (above >= statusBarGuess()) above else bounds.top + dp(4)
            place(button, buttonParams)
        } else {
            remove(button)
            hidePanel()
        }

        if (isPanelShowing) layoutPanel(screenW, screenH)
    }

    fun showPanelLoading(result: ToneResult) {
        panel.showLoading(result)
        showPanel()
    }

    fun showPanelSuggestions(result: ToneResult, suggestions: Suggestions) {
        panel.showSuggestions(result, suggestions)
        showPanel()
    }

    fun showPanelError(result: ToneResult, message: String) {
        panel.showError(result, message)
        showPanel()
    }

    fun hidePanel() {
        remove(panel)
    }

    fun hide() {
        remove(panel)
        remove(button)
        remove(border)
    }

    private fun showPanel() {
        val (screenW, screenH) = screenSize()
        layoutPanel(screenW, screenH)
    }

    private fun layoutPanel(screenW: Int, screenH: Int) {
        val margin = dp(12)
        val width = minOf(screenW - 2 * margin, dp(420))
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val spaceAbove = anchor.top - margin - statusBarGuess()
        val spaceBelow = screenH - anchor.bottom - margin
        val preferAbove = spaceAbove >= spaceBelow
        val maxHeight = (if (preferAbove) spaceAbove else spaceBelow).coerceAtLeast(dp(160))
        val height = minOf(panel.measuredHeight, maxHeight)

        panelParams.width = width
        panelParams.height = height
        panelParams.x = anchor.left.coerceIn(margin, (screenW - width - margin).coerceAtLeast(margin))
        panelParams.y = if (preferAbove) {
            (anchor.top - margin - height).coerceAtLeast(0)
        } else {
            (anchor.bottom + margin).coerceAtMost((screenH - height).coerceAtLeast(0))
        }
        place(panel, panelParams)
    }

    private fun params(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // Never take focus: the keyboard must stay attached to the field being typed in.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            if (touchable) {
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            } else {
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            },
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use raw screen coordinates; don't let the system shift us below the status bar.
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun place(view: View, params: WindowManager.LayoutParams) {
        try {
            if (view in attached) {
                wm.updateViewLayout(view, params)
            } else {
                wm.addView(view, params)
                attached += view
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to show overlay", e)
        }
    }

    private fun remove(view: View) {
        if (!attached.remove(view)) return
        try {
            wm.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to remove overlay", e)
        }
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = context.resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }

    private fun statusBarGuess() = dp(24)

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val TAG = "OverlayController"
    }
}
