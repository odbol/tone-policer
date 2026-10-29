package com.odbol.tonepolicer.inference

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Tries Gemini Nano first (private, free), then cloud Gemini if an API key is configured,
 * then the offline heuristic, so the overlay keeps working when one backend is unavailable.
 */
class AutoEngine(
    private val onDevice: OnDeviceGeminiEngine,
    private val cloud: CloudGeminiEngine?,
    private val heuristic: HeuristicEngine = HeuristicEngine(),
) : InferenceEngine {
    override val name = "Auto"

    private var onDeviceReady: Boolean? = null
    private var onDeviceCheckedAt = 0L

    override suspend fun status(): EngineStatus = when {
        isOnDeviceReady() -> EngineStatus.Ready
        cloud != null -> EngineStatus.Ready
        else -> EngineStatus.Unavailable("Gemini Nano not ready and no API key; using offline heuristic")
    }

    override suspend fun analyze(request: ToneRequest) = firstWorking { it.analyze(request) }

    override suspend fun suggest(request: ToneRequest, analysis: ToneResult) =
        firstWorking { it.suggest(request, analysis) }

    private suspend fun <T> firstWorking(block: suspend (InferenceEngine) -> T): T {
        var lastError: Exception? = null
        for (engine in candidates()) {
            try {
                return block(engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${engine.name} failed, trying next backend", e)
                if (engine === onDevice) onDeviceReady = false
                lastError = e
            }
        }
        throw lastError ?: InferenceException("No inference backend available")
    }

    private suspend fun candidates(): List<InferenceEngine> = buildList {
        if (isOnDeviceReady()) add(onDevice)
        cloud?.let { add(it) }
        add(heuristic)
    }

    private suspend fun isOnDeviceReady(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val cached = onDeviceReady
        if (cached != null && now - onDeviceCheckedAt < RECHECK_MS) return cached
        onDeviceCheckedAt = now
        return (onDevice.status() is EngineStatus.Ready).also { onDeviceReady = it }
    }

    override fun close() {
        onDevice.close()
        cloud?.close()
    }

    private companion object {
        const val TAG = "AutoEngine"
        const val RECHECK_MS = 60_000L
    }
}
