package com.odbol.tonepolicer.inference

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Runs a request on the first backend in [members] that is ready, moving down the list when one
 * isn't set up or throws. Used for the "Auto" choices.
 */
class FallbackChain<T : InferenceBackend>(private val members: List<T>) {
    private val readyUntil = HashMap<T, Long>()
    private val notReadyUntil = HashMap<T, Long>()

    suspend fun <R> run(block: suspend (T) -> R): R {
        var lastError: Exception? = null
        for (member in members) {
            if (!isReady(member)) continue
            try {
                return block(member)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "${member.name} failed, trying next backend", e)
                markNotReady(member)
                lastError = e
            }
        }
        throw lastError ?: InferenceException("No inference backend available")
    }

    suspend fun anyReady(): Boolean = members.any { isReady(it) }

    fun close() = members.forEach { it.close() }

    private suspend fun isReady(member: T): Boolean {
        val now = SystemClock.elapsedRealtime()
        if ((readyUntil[member] ?: 0L) > now) return true
        if ((notReadyUntil[member] ?: 0L) > now) return false
        val ready = member.status() is EngineStatus.Ready
        if (ready) readyUntil[member] = now + RECHECK_MS else markNotReady(member)
        return ready
    }

    private fun markNotReady(member: T) {
        readyUntil.remove(member)
        notReadyUntil[member] = SystemClock.elapsedRealtime() + RECHECK_MS
    }

    private companion object {
        const val TAG = "FallbackChain"
        const val RECHECK_MS = 60_000L
    }
}

class FallbackAnalyzer(members: List<ToneAnalyzer>) : ToneAnalyzer {
    private val chain = FallbackChain(members)

    override val name = "Auto"

    override suspend fun status(): EngineStatus =
        if (chain.anyReady()) EngineStatus.Ready else EngineStatus.Unavailable("no analysis backend is ready")

    override suspend fun analyze(request: ToneRequest) = chain.run { it.analyze(request) }

    override fun close() = chain.close()
}

class FallbackSuggester(members: List<RewriteSuggester>) : RewriteSuggester {
    private val chain = FallbackChain(members)

    override val name = "Auto"

    override suspend fun status(): EngineStatus =
        if (chain.anyReady()) EngineStatus.Ready else EngineStatus.Unavailable("no suggestion backend is ready")

    override suspend fun suggest(request: ToneRequest, analysis: ToneResult) =
        chain.run { it.suggest(request, analysis) }

    override fun close() = chain.close()
}
