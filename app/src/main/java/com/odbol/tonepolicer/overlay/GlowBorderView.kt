package com.odbol.tonepolicer.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/**
 * A pulsing, glowing rounded rectangle drawn around a text field. The view is laid out
 * [glowPadding] px larger than the field on every side so the blur has room to spread.
 */
class GlowBorderView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density

    val glowPadding = (16 * density).toInt()
    private val cornerRadius = 12 * density

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7 * density
        maskFilter = BlurMaskFilter(9 * density, BlurMaskFilter.Blur.NORMAL)
    }
    private val rect = RectF()

    private var color = Color.TRANSPARENT
    private var severity = 0f
    private var pulse = 1f
    private var colorAnimator: ValueAnimator? = null

    private val pulseAnimator = ValueAnimator.ofFloat(0.45f, 1f).apply {
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        duration = PULSE_SLOW_MS
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // BlurMaskFilter is not supported by hardware-accelerated canvases.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setSeverity(value: Float) {
        severity = value.coerceIn(0f, 1f)
        val target = SeverityColors.colorFor(severity)
        colorAnimator?.cancel()
        if (color == Color.TRANSPARENT) {
            color = target
            invalidate()
        } else if (target != color) {
            colorAnimator = ValueAnimator.ofArgb(color, target).apply {
                duration = 350
                addUpdateListener {
                    color = it.animatedValue as Int
                    invalidate()
                }
                start()
            }
        }
        // Worse tone pulses faster.
        val pulseMs = (PULSE_SLOW_MS - (PULSE_SLOW_MS - PULSE_FAST_MS) * severity).toLong()
        if (pulseAnimator.duration != pulseMs) {
            pulseAnimator.cancel()
            pulseAnimator.duration = pulseMs
        }
        if (isAttachedToWindow && !pulseAnimator.isStarted) pulseAnimator.start()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!pulseAnimator.isStarted) pulseAnimator.start()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator.cancel()
        colorAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        // Draw the ring just outside the field's edge.
        val inset = glowPadding - 2 * density
        rect.set(inset, inset, width - inset, height - inset)

        // Fine text gets a calm, faint glow; bad text gets a bright, throbbing one.
        val glowStrength = (0.3f + 0.7f * severity) * pulse
        glowPaint.color = color
        glowPaint.alpha = (255 * glowStrength).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, glowPaint)

        strokePaint.color = color
        strokePaint.alpha = (255 * (0.65f + 0.35f * pulse)).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
    }

    private companion object {
        const val PULSE_SLOW_MS = 1800L
        const val PULSE_FAST_MS = 550L
    }
}
