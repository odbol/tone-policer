package com.odbol.tonepolicer

import android.content.Context
import android.content.SharedPreferences

enum class Backend(val id: String) {
    AUTO("auto"),
    ON_DEVICE("on_device"),
    CLOUD("cloud"),
    HEURISTIC("heuristic");

    companion object {
        fun fromId(id: String?): Backend = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** Thin wrapper over SharedPreferences shared by the settings UI and the accessibility service. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("tone_policer", Context.MODE_PRIVATE)

    var monitoringEnabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Which model scores the tone on every pause in typing. */
    var analysisBackend: Backend
        get() = Backend.fromId(sp.getString(KEY_ANALYSIS_BACKEND, null))
        set(value) = sp.edit().putString(KEY_ANALYSIS_BACKEND, value.id).apply()

    /** Which model writes the rewrites when "Fix tone" is tapped. */
    var suggestionBackend: Backend
        get() = Backend.fromId(sp.getString(KEY_SUGGESTION_BACKEND, null))
        set(value) = sp.edit().putString(KEY_SUGGESTION_BACKEND, value.id).apply()

    // Stored in plain app-private prefs. For a production build, prefer Firebase AI Logic
    // (no key on device) or a server-side proxy.
    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "").orEmpty()
        set(value) = sp.edit().putString(KEY_API_KEY, value.trim()).apply()

    var cloudModel: String
        get() = sp.getString(KEY_CLOUD_MODEL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLOUD_MODEL
        set(value) = sp.edit().putString(KEY_CLOUD_MODEL, value.trim()).apply()

    /** Severity (0-100) at which the "Fix tone" button appears. */
    var flagThreshold: Int
        get() = sp.getInt(KEY_THRESHOLD, 30)
        set(value) = sp.edit().putInt(KEY_THRESHOLD, value).apply()

    var showGoodBorder: Boolean
        get() = sp.getBoolean(KEY_SHOW_GOOD, true)
        set(value) = sp.edit().putBoolean(KEY_SHOW_GOOD, value).apply()

    /** Send the other visible text on screen (e.g. the chat history) along with the message. */
    var includeScreenContext: Boolean
        get() = sp.getBoolean(KEY_SCREEN_CONTEXT, true)
        set(value) = sp.edit().putBoolean(KEY_SCREEN_CONTEXT, value).apply()

    val debounceMs: Long
        get() = sp.getLong(KEY_DEBOUNCE, 900L)

    var lastError: String?
        get() = sp.getString(KEY_LAST_ERROR, null)
        set(value) = sp.edit().putString(KEY_LAST_ERROR, value).apply()

    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.registerOnSharedPreferenceChangeListener(listener)

    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        sp.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        const val KEY_ENABLED = "monitoring_enabled"
        const val KEY_ANALYSIS_BACKEND = "analysis_backend"
        const val KEY_SUGGESTION_BACKEND = "suggestion_backend"
        const val KEY_API_KEY = "gemini_api_key"
        const val KEY_CLOUD_MODEL = "cloud_model"
        const val KEY_THRESHOLD = "flag_threshold"
        const val KEY_SHOW_GOOD = "show_good_border"
        const val KEY_SCREEN_CONTEXT = "include_screen_context"
        const val KEY_DEBOUNCE = "debounce_ms"
        const val KEY_LAST_ERROR = "last_error"

        /** Keys that require rebuilding the analyzer / suggester when they change. */
        val ENGINE_KEYS = setOf(KEY_ANALYSIS_BACKEND, KEY_SUGGESTION_BACKEND, KEY_API_KEY, KEY_CLOUD_MODEL)

        const val DEFAULT_CLOUD_MODEL = "gemini-flash-lite-latest"
    }
}
