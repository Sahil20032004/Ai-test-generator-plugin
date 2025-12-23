package com.github.sahil20032004.aitestgeneratorplugin.services

import com.github.sahil20032004.aitestgeneratorplugin.models.*
import com.github.sahil20032004.aitestgeneratorplugin.settings.PluginSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ChatService {

    private val client: OkHttpClient
    private val gson = Gson()
    private val logger = Logger.getInstance(ChatService::class.java)

    companion object {
        private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models"
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
    }

    init {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    suspend fun sendChatMessage(
        userMessage: String,
        chatContext: ChatContext
    ): ChatResponse = withContext(Dispatchers.IO) {
        val settings = PluginSettings.getInstance().state
        val provider = try {
            AIProvider.valueOf(settings.aiProvider)
        } catch (e: Exception) {
            AIProvider.GEMINI
        }

        when (provider) {
            AIProvider.GEMINI -> sendGeminiChatMessage(userMessage, chatContext, settings)
            AIProvider.OPENAI -> sendOpenAIChatMessage(userMessage, chatContext, settings)
        }
    }

    private suspend fun sendGeminiChatMessage(
        userMessage: String,
        chatContext: ChatContext,
        settings: PluginSettings.State
    ): ChatResponse {
        val apiKey = PluginSettings.getGeminiApiKey()
            ?: throw IllegalStateException("Gemini API key not configured")

        val model = settings.geminiModel
        val url = "$GEMINI_API_URL/$model:generateContent?key=$apiKey"

        val prompt = buildChatPrompt(userMessage, chatContext)

        val requestBody = buildGeminiRequestBody(prompt, settings)
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw Exception("Gemini API error: ${response.code} - $errorBody")
            }

            val responseBody = response.body?.string()
                ?: throw Exception("Empty response from Gemini")

            parseGeminiResponse(responseBody)
        }
    }

    private suspend fun sendOpenAIChatMessage(
        userMessage: String,
        chatContext: ChatContext,
        settings: PluginSettings.State
    ): ChatResponse {
        val apiKey = PluginSettings.getOpenAIApiKey()
            ?: throw IllegalStateException("OpenAI API key not configured")

        val prompt = buildChatPrompt(userMessage, chatContext)

        val messages = buildOpenAIChatMessages(prompt, chatContext)
        val requestBody = buildOpenAIRequestBody(messages, settings)

        val httpRequest = Request.Builder()
            .url(OPENAI_API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

       return client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw Exception("OpenAI API error: ${response.code} - $errorBody")
            }

            val responseBody = response.body?.string()
                ?: throw Exception("Empty response from OpenAI")

            parseOpenAIResponse(responseBody)
        }
    }

    private fun buildChatPrompt(userMessage: String, chatContext: ChatContext): String {
        val testType = when (chatContext.testScope) {
            TestScope.UNIT -> "unit test"
            TestScope.INSTRUMENTATION -> "instrumentation test"
        }

        return """
You are an AI assistant helping to refine and improve $testType code.

Context:
- Test Type: $testType
- Package: ${chatContext.packageName}
${if (chatContext.targetClassName != null) "- Target Class: ${chatContext.targetClassName}" else ""}

Current Test Code:
```kotlin
${chatContext.currentCode}
Conversation History:
${chatContext.getConversationHistory()}

User Request:
$userMessage

Instructions:

Understand the user's request
Modify the test code accordingly
Maintain test best practices
Keep the same package and imports
Return ONLY the modified Kotlin code between markers
Return format:
=== CODE START ===
[Modified test code here]
=== CODE END ===

=== EXPLANATION START ===
[Brief explanation of changes made]
=== EXPLANATION END ===
""".trimIndent()
    }

    private fun buildGeminiRequestBody(prompt: String, settings: PluginSettings.State): String {
        val requestJson = JsonObject().apply {
            add("contents", gson.toJsonTree(listOf(
                mapOf(
                    "parts" to listOf(
                        mapOf("text" to prompt)
                    )
                )
            )))
            add("generationConfig", gson.toJsonTree(mapOf(
                "temperature" to settings.temperature,
                "maxOutputTokens" to settings.maxTokens,
                "topP" to 0.95,
                "topK" to 40
            )))
        }
        return gson.toJson(requestJson)
    }

    private fun buildOpenAIChatMessages(prompt: String, chatContext: ChatContext): List<Map<String, String>> {
        return listOf(
            mapOf("role" to "user", "content" to prompt)
        )
    }

    private fun buildOpenAIRequestBody(messages: List<Map<String, String>>, settings: PluginSettings.State): String {
        val requestJson = JsonObject().apply {
            addProperty("model", settings.openAIModel)
            addProperty("temperature", settings.temperature)
            addProperty("max_tokens", settings.maxTokens)
            add("messages", gson.toJsonTree(messages))
        }
        return gson.toJson(requestJson)
    }

    private fun parseGeminiResponse(responseBody: String): ChatResponse {
        val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)

        val candidates = jsonResponse.getAsJsonArray("candidates")
        if (candidates == null || candidates.isEmpty) {
            throw Exception("No response from Gemini")
        }

        val content = candidates[0].asJsonObject
            .getAsJsonObject("content")
            .getAsJsonArray("parts")[0].asJsonObject
            .get("text")
            .asString
            .trim()

        return parseChatResponse(content)
    }

    private fun parseOpenAIResponse(responseBody: String): ChatResponse {
        val jsonResponse = gson.fromJson(responseBody, JsonObject::class.java)
        val choices = jsonResponse.getAsJsonArray("choices")

        if (choices.isEmpty()) {
            throw Exception("No response from OpenAI")
        }

        val content = choices[0].asJsonObject
            .getAsJsonObject("message")
            .get("content")
            .asString
            .trim()

        return parseChatResponse(content)
    }

    private fun parseChatResponse(content: String): ChatResponse {
        val codePattern = """=== CODE START ===\s*(.*?)\s*=== CODE END ===""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val explanationPattern = """=== EXPLANATION START ===\s*(.*?)\s*=== EXPLANATION END ===""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val code = codePattern.find(content)?.groupValues?.get(1)?.trim()
            ?.removePrefix("```kotlin")
            ?.removePrefix("```")
            ?.removeSuffix("```")
            ?.trim()
            ?: content.removePrefix("```kotlin").removePrefix("```").removeSuffix("```").trim()

        val explanation = explanationPattern.find(content)?.groupValues?.get(1)?.trim()
            ?: "Code updated based on your request."

        return ChatResponse(
            code = code,
            explanation = explanation
        )
    }

    data class ChatResponse(
        val code: String,
        val explanation: String
    )
}