package com.odbol.tonepolicer.inference

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow

/**
 * Gemini Nano running on-device through AICore, via the ML Kit GenAI Prompt API.
 *
 * Text never leaves the phone. Requires an AICore-capable device (Pixel 9+, Galaxy S25+, etc.) and a
 * one-time model download. Note that AICore may refuse inference requests from apps that are not in
 * the foreground; [FallbackAnalyzer] falls back to another backend when that happens.
 */
class OnDeviceGeminiEngine : ToneAnalyzer, RewriteSuggester {
    override val name = "Gemini Nano (on-device)"

    private val model by lazy { Generation.getClient() }

    override suspend fun status(): EngineStatus = try {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> EngineStatus.Ready
            FeatureStatus.DOWNLOADABLE -> EngineStatus.Downloadable
            FeatureStatus.DOWNLOADING -> EngineStatus.Downloading
            else -> EngineStatus.Unavailable("this device does not support Gemini Nano")
        }
    } catch (e: Exception) {
        EngineStatus.Unavailable(e.message ?: e.javaClass.simpleName)
    }

    /** Starts (or joins) the Gemini Nano download. */
    fun download(): Flow<DownloadStatus> = model.download()

    override suspend fun analyze(request: ToneRequest): ToneResult {
        val raw = generate(Prompts.analysis(request), temperature = 0.1f, topK = 1)
        return Prompts.parseAnalysis(raw, name)
    }

    override suspend fun suggest(request: ToneRequest, analysis: ToneResult): Suggestions {
        return Prompts.suggestThree(request, analysis) { prompt ->
            generate(prompt, temperature = 0.7f, topK = 20)
        }
    }

    private suspend fun generate(prompt: String, temperature: Float, topK: Int): String {
        val status = status()
        if (status !is EngineStatus.Ready) throw InferenceException("Gemini Nano: ${status.description}")
        val response = model.generateContent(
            generateContentRequest(TextPart(prompt)) {
                this.temperature = temperature
                this.topK = topK
            },
        )
        return response.candidates.firstOrNull()?.text
            ?.takeIf { it.isNotBlank() }
            ?: throw InferenceException("Gemini Nano returned an empty response")
    }

    override fun close() {
        model.close()
    }
}
