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
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class OpenAIClient : AIService {

    companion object {
        private const val OPENAI_API_URL = "https://api.openai.com/v1/chat/completions"
        private val logger = Logger.getInstance(OpenAIClient::class.java)
    }

    private val gson = Gson()
    private val client = createOkHttpClient()

    private fun createOkHttpClient(): OkHttpClient {
        return try {
            logger.info("Creating OkHttpClient with custom SSL configuration")

            // Create a trust manager that trusts all certificates (for corporate proxies/firewalls)
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    logger.debug("Client certificate check bypassed")
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    logger.debug("Server certificate check bypassed")
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            val sslSocketFactory = sslContext.socketFactory

            logger.info("SSL context initialized successfully")

            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { hostname, _ ->
                    logger.debug("Hostname verification bypassed for: $hostname")
                    true
                }
                .build()
        } catch (e: Exception) {
            logger.warn("Failed to create SSL-trusting client, falling back to default", e)
            // Fallback to default client if SSL setup fails
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }


    override suspend fun generateTests(request: AIRequest): AIResponse =
        withContext(Dispatchers.IO) {
            try {
                val apiKey = PluginSettings.getApiKey()
                    ?: throw IllegalStateException("OpenAI API key not configured. Please set it in Settings.")

                val settings = PluginSettings.getInstance().state
                val prompt = buildPrompt(request, settings)

                val requestBody = buildRequestBody(prompt, settings)
                val httpRequest = Request.Builder()
                    .url(OPENAI_API_URL)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                logger.info("Sending request to OpenAI API")

                client.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        logger.error("OpenAI API error: ${response.code} - $errorBody")

                        // Handle specific error codes with helpful messages
                        val errorMessage = when (response.code) {
                            429 -> {
                                // Parse error to check if it's quota or rate limit
                                if (errorBody.contains("insufficient_quota", ignoreCase = true)) {
                                    """
                                    ⚠️ OpenAI API Quota Exceeded
                                    
                                    Your OpenAI API key has run out of credits or exceeded its quota.
                                    
                                    📋 How to fix this:
                                    
                                    1. Check Your OpenAI Account:
                                       • Visit: https://platform.openai.com/account/billing
                                       • Log in with your OpenAI account
                                       • Check your current usage and available credits
                                    
                                    2. Add Credits or Upgrade Plan:
                                       • Free trial credits may have expired
                                       • Add payment method: https://platform.openai.com/account/billing/payment-methods
                                       • Purchase credits or upgrade to a paid plan
                                    
                                    3. Check Usage Limits:
                                       • View limits: https://platform.openai.com/account/limits
                                       • Different models have different rate limits
                                    
                                    4. Generate New API Key (if needed):
                                       • Go to: https://platform.openai.com/api-keys
                                       • Create a new API key
                                       • Update it in: Settings → Tools → AI Test Generator
                                    
                                    💡 Alternative Options:
                                    • Try using a different OpenAI account
                                    • Wait and retry if you're on a rate limit
                                    • Consider using GPT-3.5-turbo model (cheaper) in plugin settings
                                    
                                    Technical Details: ${response.code} - insufficient_quota
                                    """.trimIndent()
                                } else {
                                    """
                                    ⚠️ OpenAI API Rate Limit Exceeded
                                    
                                    You've sent too many requests in a short period.
                                    
                                    📋 How to fix this:
                                    
                                    • Wait a few seconds and try again
                                    • Check rate limits: https://platform.openai.com/account/limits
                                    • Consider upgrading your OpenAI plan for higher limits
                                    
                                    Technical Details: ${response.code} - rate_limit_exceeded
                                    """.trimIndent()
                                }
                            }
                            401 -> {
                                """
                                ⚠️ OpenAI API Authentication Failed
                                
                                Your API key is invalid or not configured correctly.
                                
                                📋 How to fix this:
                                
                                1. Get a valid API key from: https://platform.openai.com/api-keys
                                2. Configure it in: Settings → Tools → AI Test Generator
                                3. Make sure you copied the entire key (starts with 'sk-')
                                
                                Technical Details: ${response.code} - unauthorized
                                """.trimIndent()
                            }
                            403 -> {
                                """
                                ⚠️ OpenAI API Access Forbidden
                                
                                Your account doesn't have permission to access this resource.
                                
                                📋 How to fix this:
                                
                                • Verify your OpenAI account is in good standing
                                • Check if your API key has the necessary permissions
                                • Visit: https://platform.openai.com/account
                                
                                Technical Details: ${response.code} - forbidden
                                """.trimIndent()
                            }
                            500, 502, 503 -> {
                                """
                                ⚠️ OpenAI Service Temporarily Unavailable
                                
                                OpenAI's servers are experiencing issues.
                                
                                📋 What to do:
                                
                                • Wait a few moments and try again
                                • Check OpenAI status: https://status.openai.com
                                
                                Technical Details: ${response.code} - server error
                                """.trimIndent()
                            }
                            else -> {
                                """
                                ⚠️ OpenAI API Error
                                
                                An error occurred while communicating with OpenAI.
                                
                                Error Code: ${response.code}
                                Error Details: $errorBody
                                
                                📋 For help, visit:
                                • OpenAI Status: https://status.openai.com
                                • OpenAI Documentation: https://platform.openai.com/docs
                                """.trimIndent()
                            }
                        }

                        throw Exception(errorMessage)
                    }

                    val responseBody = response.body?.string()
                        ?: throw Exception("Empty response from OpenAI")

                    logger.info("Received successful response from OpenAI")
                    parseResponse(responseBody, request)
                }
            } catch (e: javax.net.ssl.SSLHandshakeException) {
                logger.error("SSL Certificate error", e)
                throw Exception(
                    "SSL Certificate Error: Unable to establish secure connection.\n\n" +
                    "This is likely due to corporate firewall or proxy settings.\n\n" +
                    "Possible solutions:\n" +
                    "1. Add the OpenAI certificate to your Java keystore\n" +
                    "2. Configure IDE proxy settings (Settings -> Appearance & Behavior -> System Settings -> HTTP Proxy)\n" +
                    "3. Contact your IT department to allow api.openai.com\n\n" +
                    "Technical details: ${e.message}"
                )
            } catch (e: java.security.cert.CertificateException) {
                logger.error("Certificate validation error", e)
                throw Exception(
                    "Certificate Validation Error: ${e.message}\n\n" +
                    "Please check your network/proxy settings or contact your IT department."
                )
            }
        }

    private fun buildPrompt(request: AIRequest, settings: PluginSettings.State): String {
        return when (request.scope) {
            TestScope.UNIT -> buildUnitTestPrompt(request, settings)
            TestScope.INSTRUMENTATION -> buildInstrumentationTestPrompt(request, settings)
        }
    }

    private fun buildUnitTestPrompt(request: AIRequest, settings: PluginSettings.State): String {
        val mockingLib = if (settings.mockingLibrary == "MOCKK") "MockK" else "Mockito"
        val testFramework = if (settings.testFramework == "JUNIT5") "JUnit 5" else "JUnit 4"

        val existingMethodsInfo = if (request.existingTestMethods.isNotEmpty()) {
            "\n\nExisting test methods (DO NOT regenerate these):\n${
                request.existingTestMethods.joinToString(
                    "\n"
                ) { "- $it" }
            }"
        } else {
            ""
        }

        return """
            You are an expert Kotlin developer tasked with generating unit tests.
            Requirements:
            - Use $testFramework for test structure
            - Use $mockingLib for mocking
            - Write tests in Kotlin
            - Follow best practices for unit testing
            - Generate comprehensive tests covering edge cases
            - Each test method should test one specific scenario
            - Use descriptive test method names following the pattern: `should[ExpectedBehavior]When[Condition]`
            ${
            if (request.existingTestCode != null)
                "- This test file already exists. Only generate NEW test methods that don't duplicate existing ones."
            else ""
        }
            $existingMethodsInfo
            Target Class to Test:
            ```kotlin
            ${request.sourceCode}
            Package: ${request.targetClassName?.substringBeforeLast(".") ?: ""}
            Class Name: ${request.targetClassName?.substringAfterLast(".") ?: ""}
            ${
            if (request.existingTestCode != null) {
                "Existing Test File:\nkotlin\n${request.existingTestCode}\n\n"
            } else ""
        }
            """.trimIndent()
    }

    private fun buildInstrumentationTestPrompt(request: AIRequest, settings: PluginSettings.State): String {
        val testFramework = if (settings.testFramework == "JUNIT5") "JUnit 5" else "JUnit 4"

        val existingMethodsInfo = if (request.existingTestMethods.isNotEmpty()) {
            "\n\nExisting test methods (DO NOT regenerate these):\n${request.existingTestMethods.joinToString("\n") { "- $it" }}"
        } else {
            ""
        }

        return """
            You are an expert Android developer tasked with generating instrumentation tests.
            Requirements:
            - Use $testFramework for test structure
            - Use AndroidX Test library and Espresso
            - Write tests in Kotlin
            - Use AndroidJUnitRunner
            - Follow Android instrumentation test best practices
            - Test UI interactions and integration scenarios
            - Use descriptive test method names
            ${if (request.existingTestCode != null) "- This test file already exists. Only generate NEW test methods that don't duplicate existing ones." else ""}
            $existingMethodsInfo
            
            Project Context:
            ${request.projectContext.joinToString("\n") { "- ${it.path}" }}
            
            ${if (request.existingTestCode != null) {
                "Existing Test File:\n```kotlin\n${request.existingTestCode}\n```\n"
            } else ""}
            
            Generate a complete instrumentation test class with:
            - Proper package declaration
            - All necessary imports (androidx.test, espresso, etc.)
            - Test class with @RunWith(AndroidJUnit4::class) annotation
            - ActivityScenarioRule or appropriate test rules
            - Setup and teardown methods if needed
            - Comprehensive UI test methods
            
            Return ONLY the Kotlin code without any markdown formatting or explanations.
        """.trimIndent()
    }

    private fun buildRequestBody(prompt: String, settings: PluginSettings.State): String {
        val requestJson = JsonObject().apply {
            addProperty("model", settings.openAIModel)
            addProperty("temperature", settings.temperature)
            addProperty("max_tokens", settings.maxTokens)
            add("messages", gson.toJsonTree(listOf(
                mapOf("role" to "user", "content" to prompt)
            )))
        }
        return gson.toJson(requestJson)
    }

    private fun parseResponse(responseBody: String, request: AIRequest): AIResponse {
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

        val cleanedCode = content
            .removePrefix("```kotlin")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val packageName = extractPackageName(cleanedCode)
        val className = extractClassName(cleanedCode)
        val newMethods = extractTestMethods(cleanedCode)

        val mergeMode = if (request.existingTestCode != null) {
            MergeMode.APPEND_TO_EXISTING
        } else {
            MergeMode.CREATE_NEW
        }

        return AIResponse(
            testCode = cleanedCode,
            testClassName = className,
            packageName = packageName,
            newTestMethods = newMethods,
            mergeMode = mergeMode
        )
    }

    private fun extractPackageName(code: String): String {
        val packageRegex = """package\s+([\w.]+)""".toRegex()
        return packageRegex.find(code)?.groupValues?.get(1) ?: ""
    }

    private fun extractClassName(code: String): String {
        val classRegex = """class\s+(\w+)""".toRegex()
        return classRegex.find(code)?.groupValues?.get(1) ?: "GeneratedTest"
    }

    private fun extractTestMethods(code: String): List<String> {
        val methodRegex = """@Test\s+fun\s+(\w+)\s*\(""".toRegex()
        return methodRegex.findAll(code).map { it.groupValues[1] }.toList()
    }
}
