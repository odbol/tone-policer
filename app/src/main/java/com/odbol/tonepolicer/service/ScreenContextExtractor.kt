package com.odbol.tonepolicer.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Collects the visible text around a text field (chat history, the email being replied to, a
 * post's title...) so the model can judge whether the reply fits the conversation.
 *
 * Lines come out top to bottom, closest-to-the-field last. Chat bubbles are tagged [left] or [right]
 * by horizontal position, since most chat apps put the other person's messages on the left.
 */
object ScreenContextExtractor {
    private const val MAX_NODES = 600
    private const val MAX_CHARS = 2500

    private class Line(val top: Int, val left: Int, val text: String)

    fun extract(field: AccessibilityNodeInfo): String {
        val root = field.window?.root ?: return ""
        val fieldBounds = Rect().also { field.getBoundsInScreen(it) }
        val rootBounds = Rect().also { root.getBoundsInScreen(it) }
        val screenWidth = rootBounds.width().coerceAtLeast(1)

        val lines = mutableListOf<Line>()
        val seen = HashSet<String>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { addLast(root) }
        val bounds = Rect()
        var visited = 0

        while (stack.isNotEmpty() && visited < MAX_NODES) {
            val node = stack.removeLast()
            visited++
            if (node == field || !node.isVisibleToUser || node.isPassword) continue

            val text = node.text?.toString()?.trim().orEmpty()
            // Skip other input boxes (drafts, search) and tiny labels like icon glyphs.
            if (!node.isEditable && text.length >= 2 && seen.add(text)) {
                node.getBoundsInScreen(bounds)
                // Text below the field is usually buttons or the keyboard suggestion strip.
                if (bounds.top < fieldBounds.bottom) {
                    lines += Line(bounds.top, bounds.left, side(bounds, screenWidth) + text.replace('\n', ' '))
                }
            }
            for (i in node.childCount - 1 downTo 0) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }

        lines.sortWith(compareBy<Line> { it.top }.thenBy { it.left })
        // Keep the lines closest to the field: that's where the latest messages are.
        val kept = ArrayDeque<String>()
        var length = 0
        for (line in lines.asReversed()) {
            if (length + line.text.length > MAX_CHARS) break
            kept.addFirst(line.text)
            length += line.text.length + 1
        }
        return kept.joinToString("\n")
    }

    private fun side(bounds: Rect, screenWidth: Int): String {
        // Full-width text (titles, email bodies) has no meaningful side.
        if (bounds.width() > screenWidth * 0.8f) return ""
        val center = bounds.centerX().toFloat() / screenWidth
        return when {
            center < 0.45f -> "[left] "
            center > 0.55f -> "[right] "
            else -> ""
        }
    }
}
