package com.odbol.tonepolicer.inference

import com.odbol.tonepolicer.Backend
import com.odbol.tonepolicer.Prefs

/** Builds the analyzer and suggester independently, so each can use a different model. */
object EngineFactory {
    fun createAnalyzer(prefs: Prefs): ToneAnalyzer = when (prefs.analysisBackend) {
        Backend.JEV -> JevAnalyzer(prefs.jevApiKey, prefs.jevModel)
        Backend.ON_DEVICE -> OnDeviceGeminiEngine()
        Backend.CLOUD -> CloudGeminiEngine(prefs.apiKey, prefs.cloudModel)
        Backend.HEURISTIC -> HeuristicEngine()
        Backend.AUTO -> FallbackAnalyzer(
            listOfNotNull(jev(prefs), OnDeviceGeminiEngine(), cloud(prefs), HeuristicEngine()),
        )
    }

    fun createSuggester(prefs: Prefs): RewriteSuggester = when (prefs.suggestionBackend) {
        // Jev is decision-only and can't write text, so it never backs suggestions.
        Backend.ON_DEVICE, Backend.JEV -> OnDeviceGeminiEngine()
        Backend.CLOUD -> CloudGeminiEngine(prefs.apiKey, prefs.cloudModel)
        Backend.HEURISTIC -> HeuristicEngine()
        Backend.AUTO -> FallbackSuggester(listOfNotNull(OnDeviceGeminiEngine(), cloud(prefs), HeuristicEngine()))
    }

    private fun jev(prefs: Prefs): JevAnalyzer? =
        prefs.jevApiKey.takeIf { it.isNotBlank() }?.let { JevAnalyzer(it, prefs.jevModel) }

    private fun cloud(prefs: Prefs): CloudGeminiEngine? =
        prefs.apiKey.takeIf { it.isNotBlank() }?.let { CloudGeminiEngine(it, prefs.cloudModel) }
}
