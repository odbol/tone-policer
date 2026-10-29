package com.odbol.tonepolicer.inference

/**
 * What the user is typing, plus where.
 *
 * [screenContext] is the other visible text in the same window (e.g. the chat history above the
 * field), so the model can tell whether the reply acknowledges what the other person said.
 */
data class ToneRequest(
    val text: String,
    val packageName: String = "",
    val screenContext: String = "",
) {
    /** Cache key: the same reply can be fine in one conversation and rude in another. */
    val cacheKey: String get() = "${screenContext.hashCode()}|$text"
}

/** Result of a tone check. [severity] is 0 (fine) to 100 (hostile). */
data class ToneResult(
    val severity: Int,
    val categories: List<String>,
    val reason: String,
    val engine: String,
    /** Social expectations the reply misses, e.g. "Thank them for their help". */
    val missing: List<String> = emptyList(),
) {
    val severityFraction: Float get() = severity.coerceIn(0, 100) / 100f

    val label: String
        get() = when {
            severity < 15 -> "Looks good"
            severity < 40 -> "A bit off"
            severity < 70 -> "Could cause friction"
            else -> "Likely to offend"
        }

    val categoryLabel: String
        get() = categories.joinToString(" · ") { c ->
            c.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
}

/** One rewrite, with a short label for its approach (e.g. "Warm", "Concise"). */
data class Rewrite(
    val style: String,
    val text: String,
)

data class Suggestions(
    val rewrites: List<Rewrite>,
    val tip: String = "",
) {
    companion object {
        const val COUNT = 3
    }
}

sealed class EngineStatus(val description: String) {
    object Ready : EngineStatus("Ready")
    object Downloadable : EngineStatus("Model can be downloaded")
    object Downloading : EngineStatus("Model is downloading…")
    object NeedsApiKey : EngineStatus("Needs a Gemini API key")
    class Unavailable(reason: String) : EngineStatus("Unavailable: $reason")
}

class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when the model refused to process the text for safety reasons. */
class SafetyBlockedException(message: String) : Exception(message)

/** Common lifecycle for anything that runs a model. */
interface InferenceBackend {
    val name: String

    suspend fun status(): EngineStatus

    fun close() {}
}

/** Scores how a message will land. Runs on every pause in typing, so it should be fast. */
interface ToneAnalyzer : InferenceBackend {
    suspend fun analyze(request: ToneRequest): ToneResult
}

/** Writes alternative phrasings. Runs only when the user taps "Fix tone". */
interface RewriteSuggester : InferenceBackend {
    suspend fun suggest(request: ToneRequest, analysis: ToneResult): Suggestions
}
