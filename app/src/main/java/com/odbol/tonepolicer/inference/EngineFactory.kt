package com.odbol.tonepolicer.inference

import com.odbol.tonepolicer.Backend
import com.odbol.tonepolicer.Prefs

/** Builds the analyzer and suggester independently, so each can use a different model. */
object EngineFactory {
    fun createAnalyzer(prefs: Prefs): ToneAnalyzer = when (prefs.analysisBackend) {
        Backend.ON_DEVICE -> OnDeviceGeminiEngine()
        Backend.CLOUD -> CloudGeminiEngine(prefs.apiKey, prefs.cloudModel)
        Backend.HEURISTIC -> HeuristicEngine()
        Backend.AUTO -> FallbackAnalyzer(listOfNotNull(OnDeviceGeminiEngine(), cloud(prefs), HeuristicEngine()))
    }

    fun createSuggester(prefs: Prefs): RewriteSuggester = when (prefs.suggestionBackend) {
        Backend.ON_DEVICE -> OnDeviceGeminiEngine()
        Backend.CLOUD -> CloudGeminiEngine(prefs.apiKey, prefs.cloudModel)
        Backend.HEURISTIC -> HeuristicEngine()
        Backend.AUTO -> FallbackSuggester(listOfNotNull(OnDeviceGeminiEngine(), cloud(prefs), HeuristicEngine()))
    }

    private fun cloud(prefs: Prefs): CloudGeminiEngine? =
        prefs.apiKey.takeIf { it.isNotBlank() }?.let { CloudGeminiEngine(it, prefs.cloudModel) }
}
