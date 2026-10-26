package com.androidharness.core

import android.content.Context

/**
 * Which model to talk to, and where.
 *
 * Deliberately not hardcoded to one provider. The core speaks the
 * OpenAI chat-completions dialect, which Gemini's compatibility
 * endpoint, OpenAI itself, OpenRouter, Groq, llama.cpp, Ollama and vLLM
 * all answer - so "any base URL, any model name" is not a feature bolted
 * on, it is what that choice of dialect buys.
 *
 * The URL and model name are ordinary preferences. The key is not: it
 * goes through SecretStore.
 */
object ProviderSettings {

    private const val PREFERENCES = "harness-provider"
    private const val BASE_URL = "base_url"
    private const val MODEL = "model"
    private const val API_KEY = "api_key"

    /** A sensible starting point, not a commitment. */
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"

    data class Config(val baseUrl: String, val model: String, val apiKey: String) {
        val usable: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()

        /** Safe to log or show. The key never appears. */
        fun describe(): String = buildString {
            append("url:   ").append(baseUrl.ifBlank { "(not set)" }).append('\n')
            append("model: ").append(model.ifBlank { "(not set)" }).append('\n')
            append("key:   ").append(if (apiKey.isBlank()) "(not set)" else "set, ${apiKey.length} characters")
        }
    }

    fun load(context: Context): Config {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return Config(
            baseUrl = preferences.getString(BASE_URL, DEFAULT_BASE_URL).orEmpty(),
            model = preferences.getString(MODEL, "").orEmpty(),
            apiKey = SecretStore.get(context, API_KEY),
        )
    }

    fun save(context: Context, baseUrl: String, model: String, apiKey: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(BASE_URL, baseUrl.trim())
            .putString(MODEL, model.trim())
            .apply()
        SecretStore.put(context, API_KEY, apiKey.trim())
    }
}
