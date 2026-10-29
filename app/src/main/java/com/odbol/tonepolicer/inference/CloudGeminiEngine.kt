package com.odbol.tonepolicer.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Cloud Gemini through the Gemini Developer API (generativelanguage.googleapis.com) with an API key.
 *
 * Works on any device and is the most accurate option, but the typed text is sent to Google.
 */
class CloudGeminiEngine(
    private val apiKey: String,
    private val model: String,
) : InferenceEngine {
    override val name = "Gemini $model (cloud)"

    override suspend fun status(): EngineStatus =
        if (apiKey.isBlank()) EngineStatus.NeedsApiKey else EngineStatus.Ready

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

    private suspend fun generate(prompt: String, temperature: Double): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw InferenceException("No Gemini API key set")

        val url = URI("$BASE_URL/${URLEncoder.encode(model, "UTF-8")}:generateContent").toURL()
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

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).getJSONObject("error").getString("message") }
                    .getOrDefault(text.take(200))
                throw InferenceException("Gemini API error $code: $message")
            }
            extractText(JSONObject(text))
        } finally {
            conn.disconnect()
        }
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
