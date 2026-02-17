package com.mindfulhome.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Persists user preferences using SharedPreferences.
 *
 * Modelled after the distraction-linter SettingsManager with the same
 * AI-mode pattern (on-device vs backend).
 */
object SettingsManager {

    private const val PREF_NAME = "mindfulhome_settings"

    // AI mode
    private const val AI_MODE_KEY = "ai_mode"
    const val AI_MODE_ON_DEVICE = "on_device"
    const val AI_MODE_BACKEND = "backend"

    // Backend model selection
    private const val BACKEND_MODEL_KEY = "backend_model"

    /** Available Vertex AI models the user can pick from. */
    data class ModelOption(val id: String, val label: String, val description: String)

    /** Fallback list used when the backend is unreachable. */
    val AVAILABLE_MODELS = listOf(
        ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", "Fast, capable, best value (recommended)"),
        ModelOption("gemini-2.5-flash-lite", "Gemini 2.5 Flash Lite", "Fastest, lowest cost"),
        ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", "Most capable, thinking model, higher cost"),
    )

    val DEFAULT_MODEL = AVAILABLE_MODELS.first().id

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun getAIMode(context: Context): String =
        prefs(context).getString(AI_MODE_KEY, AI_MODE_ON_DEVICE) ?: AI_MODE_ON_DEVICE

    fun setAIMode(context: Context, mode: String) {
        prefs(context).edit { putString(AI_MODE_KEY, mode) }
    }

    fun getBackendModel(context: Context): String =
        prefs(context).getString(BACKEND_MODEL_KEY, DEFAULT_MODEL) ?: DEFAULT_MODEL

    fun setBackendModel(context: Context, model: String) {
        prefs(context).edit { putString(BACKEND_MODEL_KEY, model) }
    }
}
