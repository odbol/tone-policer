package com.odbol.tonepolicer.inference

/** What the user is typing, plus where. */
data class ToneRequest(
    val text: String,
    val packageName: String = "",
)

/** Result of a tone check. [severity] is 0 (fine) to 100 (hostile). */
data class ToneResult(
    val severity: Int,
    val categories: List<String>,
    val reason: String,
    val engine: String,
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

data class Suggestions(
    val rewrites: List<String>,
    val tip: String = "",
)

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

interface InferenceEngine {
    val name: String

    suspend fun status(): EngineStatus

    suspend fun analyze(request: ToneRequest): ToneResult

    suspend fun suggest(request: ToneRequest, analysis: ToneResult): Suggestions

    fun close() {}
}
