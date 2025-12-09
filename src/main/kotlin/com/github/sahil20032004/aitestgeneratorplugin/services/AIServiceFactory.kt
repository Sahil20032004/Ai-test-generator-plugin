package com.github.sahil20032004.aitestgeneratorplugin.services

import com.github.sahil20032004.aitestgeneratorplugin.models.AIProvider
import com.github.sahil20032004.aitestgeneratorplugin.settings.PluginSettings

object AIServiceFactory {

    fun createService(): AIService {
        val provider = try {
            AIProvider.valueOf(PluginSettings.getInstance().state.aiProvider)
        } catch (e: Exception) {
            AIProvider.GEMINI
        }

        return when (provider) {
            AIProvider.OPENAI -> OpenAIClient()
            AIProvider.GEMINI -> GeminiClient()
        }
    }

    fun validateConfiguration(): ValidationResult {
        val settings = PluginSettings.getInstance().state
        val provider = try {
            AIProvider.valueOf(settings.aiProvider)
        } catch (e: Exception) {
            AIProvider.GEMINI
        }

        return when (provider) {
            AIProvider.OPENAI -> {
                val apiKey = PluginSettings.getOpenAIApiKey()
                if (apiKey.isNullOrBlank()) {
                    ValidationResult(
                        isValid = false,
                        message = "OpenAI API key not configured. Please set it in Settings → AI Test Generator."
                    )
                } else {
                    ValidationResult(isValid = true)
                }
            }
            AIProvider.GEMINI -> {
                val apiKey = PluginSettings.getGeminiApiKey()
                if (apiKey.isNullOrBlank()) {
                    ValidationResult(
                        isValid = false,
                        message = "Gemini API key not configured. Please set it in Settings → AI Test Generator.\n\nGet your API key from: https://makersuite.google.com/app/apikey"
                    )
                } else {
                    ValidationResult(isValid = true)
                }
            }
        }
    }

    data class ValidationResult(
        val isValid: Boolean,
        val message: String? = null
    )
}