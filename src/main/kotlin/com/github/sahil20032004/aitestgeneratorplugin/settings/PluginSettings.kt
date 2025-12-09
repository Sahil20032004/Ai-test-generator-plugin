package com.github.sahil20032004.aitestgeneratorplugin.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.github.sahil20032004.aitestgeneratorplugin.models.AIProvider


@Service
@State(
    name = "AITestGeneratorSettings",
    storages = [Storage("AITestGeneratorSettings.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    private var myState = State()

    data class State(
        var aiProvider: String = AIProvider.GEMINI.name,
        var openAIModel: String = "gpt-4o",
        var geminiModel: String = "gemini-2.5-flash",  // Free tier model
        var maxTokens: Int = 8000,  // Increased for flash models
        var temperature: Double = 0.3,
        var testFramework: String = "JUNIT5",
        var mockingLibrary: String = "MOCKK"
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        private const val OPENAI_API_KEY_ATTRIBUTE = "AITestGenerator.OpenAIApiKey"
        private const val GEMINI_API_KEY_ATTRIBUTE = "AITestGenerator.GeminiApiKey"

        fun getInstance(): PluginSettings = service()

        fun setOpenAIApiKey(apiKey: String) {
            val credentialAttributes = CredentialAttributes(OPENAI_API_KEY_ATTRIBUTE)
            PasswordSafe.instance.set(credentialAttributes, Credentials(OPENAI_API_KEY_ATTRIBUTE, apiKey))
        }

        fun getOpenAIApiKey(): String? {
            val credentialAttributes = CredentialAttributes(OPENAI_API_KEY_ATTRIBUTE)
            return PasswordSafe.instance.getPassword(credentialAttributes)
        }

        fun setGeminiApiKey(apiKey: String) {
            val credentialAttributes = CredentialAttributes(GEMINI_API_KEY_ATTRIBUTE)
            PasswordSafe.instance.set(credentialAttributes, Credentials(GEMINI_API_KEY_ATTRIBUTE, apiKey))
        }

        fun getGeminiApiKey(): String? {
            val credentialAttributes = CredentialAttributes(GEMINI_API_KEY_ATTRIBUTE)
            return PasswordSafe.instance.getPassword(credentialAttributes)
        }

        fun getApiKey(): String? {
            val provider = try {
                AIProvider.valueOf(getInstance().state.aiProvider)
            } catch (e: Exception) {
                AIProvider.GEMINI
            }

            return when (provider) {
                AIProvider.OPENAI -> getOpenAIApiKey()
                AIProvider.GEMINI -> getGeminiApiKey()
            }
        }
    }
}