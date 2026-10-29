package com.odbol.tonepolicer.inference

import com.odbol.tonepolicer.Backend
import com.odbol.tonepolicer.Prefs

object EngineFactory {
    fun create(prefs: Prefs): InferenceEngine = when (prefs.backend) {
        Backend.ON_DEVICE -> OnDeviceGeminiEngine()
        Backend.CLOUD -> CloudGeminiEngine(prefs.apiKey, prefs.cloudModel)
        Backend.HEURISTIC -> HeuristicEngine()
        Backend.AUTO -> AutoEngine(
            onDevice = OnDeviceGeminiEngine(),
            cloud = prefs.apiKey.takeIf { it.isNotBlank() }?.let { CloudGeminiEngine(it, prefs.cloudModel) },
        )
    }
}
