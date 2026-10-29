package com.odbol.tonepolicer.inference

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Cloud Gemini through the Gemini Developer API (generativelanguage.googleapis.com) with an API key.
 *
 * Works on any device and is the most accurate option, but the typed text is sent to Google.
 */
class CloudGeminiEngine(
    private val apiKey: String,
    private val model: String,
) : ToneAnalyzer, RewriteSuggester {
    override val name = "Gemini $model (cloud)"

    override suspend fun status(): EngineStatus =
        if (apiKey.isBlank()) EngineStatus.NeedsApiKey("Gemini") else EngineStatus.Ready

    override suspend fun analyze(request: ToneRequest): ToneResult = try {
        Prompts.parseAnalysis(generate(Prompts.analysis(request), temperature = 0.1), name)
    } catch (e: SafetyBlockedException) {
        // If Gemini won't even read it, it's safe to say the tone is a problem.
        ToneResult(100, listOf("inappropriate"), "Gemini's safety filter blocked this text.", name)
    }

    override suspend fun suggest(request: ToneRequest, analysis: ToneResult): Suggestions = try {
        Prompts.suggestThree(request, analysis) { prompt -> generate(prompt, temperature = 0.8) }
    } catch (e: SafetyBlockedException) {
        throw InferenceException("This text is too hostile for Gemini to rewrite. Try starting over.", e)
    }

    private suspend fun generate(prompt: String, temperature: Double): String {
        if (apiKey.isBlank()) throw InferenceException("No Gemini API key set")
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", temperature)
                    .put("responseMimeType", "application/json"),
            )
            .put("safetySettings", safetySettings())
        val url = "$BASE_URL/${URLEncoder.encode(model, "UTF-8")}:generateContent"
        return extractText(HttpJson.post(url, mapOf("x-goog-api-key" to apiKey), body, "Gemini API"))
    }

    private fun extractText(response: JSONObject): String {
        response.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw SafetyBlockedException(it)
        }
        val candidate = response.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw InferenceException("Gemini returned no candidates")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
        if (parts == null || parts.length() == 0) {
            val finish = candidate.optString("finishReason")
            if (finish == "SAFETY" || finish == "PROHIBITED_CONTENT") throw SafetyBlockedException(finish)
            throw InferenceException("Gemini returned no text (finishReason=$finish)")
        }
        return buildString {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (!part.optBoolean("thought")) append(part.optString("text"))
            }
        }
    }

    // The whole point is to look at rude text, so only block the most extreme content.
    private fun safetySettings() = JSONArray().apply {
        for (category in HARM_CATEGORIES) {
            put(JSONObject().put("category", category).put("threshold", "BLOCK_ONLY_HIGH"))
        }
    }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        val HARM_CATEGORIES = listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        )
    }
}
