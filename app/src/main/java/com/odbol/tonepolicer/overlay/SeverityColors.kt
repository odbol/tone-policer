package com.odbol.tonepolicer.overlay

import android.animation.ArgbEvaluator

/** Maps severity 0..1 onto green -> yellow -> red. */
object SeverityColors {
    const val GOOD = 0xFF2ECC71.toInt()
    const val WARN = 0xFFF1C40F.toInt()
    const val BAD = 0xFFE74C3C.toInt()

    private val evaluator = ArgbEvaluator()

    fun colorFor(severity: Float): Int {
        val s = severity.coerceIn(0f, 1f)
        return if (s < 0.5f) {
            evaluator.evaluate(s / 0.5f, GOOD, WARN) as Int
        } else {
            evaluator.evaluate((s - 0.5f) / 0.5f, WARN, BAD) as Int
        }
    }
}
