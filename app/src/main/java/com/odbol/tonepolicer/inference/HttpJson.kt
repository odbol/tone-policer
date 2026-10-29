package com.odbol.tonepolicer.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/** Minimal JSON-over-HTTPS POST shared by the cloud backends. */
internal object HttpJson {
    suspend fun post(url: String, headers: Map<String, String>, body: JSONObject, service: String): JSONObject =
        withContext(Dispatchers.IO) {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw InferenceException("$service error $code: ${errorMessage(text)}")
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }

    /** Pulls a readable message out of the usual `{"error": {"message": ...}}` / `{"error": "..."}` shapes. */
    private fun errorMessage(body: String): String = runCatching {
        val json = JSONObject(body)
        val error = json.opt("error")
        when (error) {
            is JSONObject -> error.optString("message").ifBlank { error.toString() }
            is String -> error
            else -> json.optString("message").ifBlank { body.take(200) }
        }
    }.getOrDefault(body.take(200))
}
