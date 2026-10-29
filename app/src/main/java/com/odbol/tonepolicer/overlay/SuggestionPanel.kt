package com.odbol.tonepolicer.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.odbol.tonepolicer.R
import com.odbol.tonepolicer.inference.Rewrite
import com.odbol.tonepolicer.inference.Suggestions
import com.odbol.tonepolicer.inference.ToneResult

/** The floating card that explains the problem and lists rewrites. Built in code since it lives in an overlay window. */
class SuggestionPanel(
    context: Context,
    private val onChosen: (String) -> Unit,
    private val onCopy: (String) -> Unit,
    private val onDismiss: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val surface = if (dark) 0xFF1F1F24.toInt() else Color.WHITE
    private val onSurface = if (dark) 0xFFECECF1.toInt() else 0xFF1B1B1F.toInt()
    private val muted = if (dark) 0xFFA9A9B3.toInt() else 0xFF5F5F6A.toInt()
    private val itemBackground = if (dark) 0xFF2B2B32.toInt() else 0xFFF2F2F6.toInt()

    private val dot = View(context)
    private val title = label(17f, onSurface, bold = true)
    private val categories = label(13f, muted)
    private val reason = label(14f, onSurface)
    private val missing = label(14f, onSurface)
    private val suggestionsHeader = label(13f, muted, bold = true).apply { setText(R.string.suggestions) }
    private val progress = ProgressBar(context).apply { isIndeterminate = true }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val tip = label(13f, muted).apply { setTypeface(typeface, Typeface.ITALIC) }
    private val footer = label(12f, muted).apply { setText(R.string.tap_to_replace) }

    init {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(surface)
                setStroke(dp(1), if (dark) 0x33FFFFFF else 0x22000000)
            }
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
        header.addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val close = label(18f, muted).apply {
            text = "✕"
            contentDescription = context.getString(R.string.close)
            setPadding(dp(12), dp(4), dp(4), dp(4))
            setOnClickListener { onDismiss() }
        }
        header.addView(close)

        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        body.addView(categories, matchWrap(top = 2))
        body.addView(reason, matchWrap(top = 8))
        body.addView(missing, matchWrap(top = 6))
        body.addView(suggestionsHeader, matchWrap(top = 14))
        body.addView(progress, LinearLayout.LayoutParams(dp(28), dp(28)).apply {
            topMargin = dp(8)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        body.addView(list, matchWrap(top = 4))
        body.addView(tip, matchWrap(top = 8))
        body.addView(footer, matchWrap(top = 8))

        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(body)

        card.addView(header)
        card.addView(scroll, matchWrap(top = 0))
        addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        // The window uses FLAG_WATCH_OUTSIDE_TOUCH; tapping elsewhere dismisses the card.
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onDismiss()
                true
            } else {
                false
            }
        }
    }

    fun showLoading(result: ToneResult) {
        bindHeader(result)
        progress.visibility = VISIBLE
        list.removeAllViews()
        tip.visibility = GONE
        footer.visibility = GONE
    }

    fun showSuggestions(result: ToneResult, suggestions: Suggestions) {
        bindHeader(result)
        progress.visibility = GONE
        list.removeAllViews()
        for (rewrite in suggestions.rewrites) {
            list.addView(suggestionItem(rewrite), matchWrap(top = 8))
        }
        tip.text = suggestions.tip
        tip.visibility = if (suggestions.tip.isBlank()) GONE else VISIBLE
        footer.visibility = VISIBLE
    }

    fun showError(result: ToneResult, message: String) {
        bindHeader(result)
        progress.visibility = GONE
        list.removeAllViews()
        list.addView(label(14f, SeverityColors.BAD).apply { text = message }, matchWrap(top = 8))
        tip.visibility = GONE
        footer.visibility = GONE
    }

    private fun bindHeader(result: ToneResult) {
        val color = SeverityColors.colorFor(result.severityFraction)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        title.text = "${result.label} · ${result.severity}/100"
        categories.text = result.categoryLabel
        categories.visibility = if (result.categories.isEmpty()) GONE else VISIBLE
        reason.text = result.reason
        reason.visibility = if (result.reason.isBlank()) GONE else VISIBLE
        missing.text = "Consider:\n" + result.missing.joinToString("\n") { "☐ $it" }
        missing.visibility = if (result.missing.isEmpty()) GONE else VISIBLE
    }

    private fun suggestionItem(rewrite: Rewrite) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(8), dp(12), dp(10))
        val shape = GradientDrawable().apply {
            cornerRadius = 12 * density
            setColor(itemBackground)
        }
        background = RippleDrawable(ColorStateList.valueOf(0x33808080), shape, null)
        addView(label(11f, muted, bold = true).apply { text = rewrite.style.uppercase() })
        addView(label(15f, onSurface).apply { text = rewrite.text }, matchWrap(top = 2))
        isClickable = true
        setOnClickListener { onChosen(rewrite.text) }
        setOnLongClickListener {
            onCopy(rewrite.text)
            true
        }
    }

    private fun label(sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun matchWrap(top: Int) =
        LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * density).toInt()
}
