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

class GeminiClient : AIService {

    private val client = createHttpClient()

    private fun createHttpClient(): OkHttpClient {
        return try {
            // Create a trust manager that accepts all certificates
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())

            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            logger.error("Failed to create SSL-enabled HTTP client, falling back to default", e)
            // Fallback to default client if SSL configuration fails
            OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    private val gson = Gson()
    private val logger = Logger.getInstance(GeminiClient::class.java)

    companion object {
        private const val GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1/models"
    }

    override suspend fun generateTests(request: AIRequest): AIResponse = withContext(Dispatchers.IO) {
        val apiKey = PluginSettings.getGeminiApiKey()
            ?: throw IllegalStateException("Gemini API key not configured. Please set it in Settings.")

        val settings = PluginSettings.getInstance().state

        // Use BDD prompt for instrumentation tests if enabled
        val prompt = if (request.scope == TestScope.INSTRUMENTATION && request.useBDD) {
            buildCucumberBDDPrompt(request, settings)
        } else {
            buildPrompt(request, settings)
        }

        val model = settings.geminiModel
        val url = "$GEMINI_API_URL/$model:generateContent?key=$apiKey"

        logger.info("Calling Gemini API with model: $model, BDD: ${request.useBDD}")

        val requestBody = buildRequestBody(prompt, settings)
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                logger.error("Gemini API error: ${response.code} - $errorBody")

                val errorMessage = buildString {
                    append("⚠️ Gemini API Error\n\n")
                    append("An error occurred while communicating with Google Gemini.\n\n")
                    append("Error Code: ${response.code}\n")
                    append("Error Details: $errorBody\n\n")

                    when (response.code) {
                        400 -> {
                            append("\n💡 Solution:\n")
                            append("Invalid request. Please check your settings.\n")
                        }
                        401, 403 -> {
                            append("\n💡 Solution:\n")
                            append("Invalid API key. Please check your Gemini API key in Settings.\n")
                            append("Get your API key from: https://aistudio.google.com/app/apikey\n")
                        }
                        404 -> {
                            append("\n💡 Solution:\n")
                            append("Model not found. The model '$model' is not available.\n")
                            append("Please go to Settings → AI Test Generator and select a different model\n")
                        }
                        429 -> {
                            append("\n💡 Solution:\n")
                            append("Rate limit exceeded or quota reached.\n")
                        }
                    }

                    append("\n📋 For help, visit:\n")
                    append("• Gemini API Documentation: https://ai.google.dev/docs\n")
                    append("• Get API Key: https://aistudio.google.com/app/apikey")
                }

                throw Exception(errorMessage)
            }

            val responseBody = response.body?.string()
                ?: throw Exception("Empty response from Gemini")

            if (request.scope == TestScope.INSTRUMENTATION && request.useBDD) {
                parseBDDResponse(responseBody, request)
            } else {
                parseResponse(responseBody, request)
            }
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
            "\n\nExisting test methods (DO NOT regenerate these):\n${request.existingTestMethods.joinToString("\n") { "- $it" }}"
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
${if (request.existingTestCode != null) "- This test file already exists. Only generate NEW test methods that don't duplicate existing ones." else ""}
$existingMethodsInfo

Target Class to Test:
```kotlin
${request.sourceCode}
Package: ${request.targetClassName?.substringBeforeLast(".") ?: ""}
Class Name: ${request.targetClassName?.substringAfterLast(".") ?: ""}

${if (request.existingTestCode != null) {
            "Existing Test File:\nkotlin\n${request.existingTestCode}\n\n"
        } else ""}

Generate a complete test class with:

Proper package declaration
All necessary imports
Test class with appropriate annotations
Setup and teardown methods if needed
Comprehensive test methods
Return ONLY the Kotlin code without any markdown formatting or explanations.
""".trimIndent()
    }


    private fun buildInstrumentationTestPrompt(request: AIRequest, settings: PluginSettings.State): String {
        val testFramework = if (settings.testFramework == "JUNIT5") "JUnit 5" else "JUnit 4"

        val existingMethodsInfo = if (request.existingTestMethods.isNotEmpty()) {
            "\n\nExisting test methods (DO NOT regenerate these):\n${request.existingTestMethods.joinToString("\n") { "- $it" }}"
        } else {
            ""
        }

        val projectInfo = if (request.projectContext.isNotEmpty()) {
            buildString {
                appendLine("\nProject Structure:")
                request.projectContext.take(10).forEach { context ->
                    appendLine("\nFile: ${context.path}")
                    appendLine(context.content)
                }
            }
        } else {
            "\nNo project files analyzed - generate basic instrumentation test template"
        }

        return """
You are an expert Android developer tasked with generating instrumentation tests.

Requirements:
- Use $testFramework for test structure
- Use AndroidX Test library (androidx.test) and Espresso for UI testing
- Write tests in Kotlin
- Use @RunWith(AndroidJUnit4::class)
- Follow Android instrumentation test best practices
- Test actual Android components and UI if they exist in the project
- Use descriptive test method names following pattern: `should[ExpectedBehavior]When[Condition]`
${if (request.existingTestCode != null) "- This test file already exists. Only generate NEW test methods that don't duplicate existing ones." else ""}
$existingMethodsInfo

$projectInfo

${if (request.existingTestCode != null) {
            "Existing Test File:\n```kotlin\n${request.existingTestCode}\n```\n"
        } else ""}

Based on the project structure above, generate instrumentation tests that:
1. Test real components from the project (Activities, ViewModels, etc.)
2. Include proper package declaration matching the project
3. Import necessary AndroidX Test and Espresso libraries
4. Use @RunWith(AndroidJUnit4::class) annotation
5. Include ActivityScenario or ActivityScenarioRule if Activities are present
6. Test UI interactions with Espresso if UI components exist
7. Include basic smoke tests if no specific components are identified

Generate a complete instrumentation test class.

Return ONLY the Kotlin code without any markdown formatting or explanations.
        """.trimIndent()
    }

    private fun buildCucumberBDDPrompt(request: AIRequest, settings: PluginSettings.State): String {
        val projectInfo = if (request.projectContext.isNotEmpty()) {
            buildString {
                appendLine("\nProject Structure:")
                request.projectContext.take(10).forEach { context ->
                    appendLine("\nFile: ${context.path}")
                    appendLine(context.content)
                }
            }
        } else {
            "\nNo project files analyzed - generate basic BDD test template"
        }

        return """
You are an expert Android BDD test developer tasked with generating Cucumber BDD tests.

Requirements:
- Generate TWO separate files:
  1. A Gherkin .feature file with test scenarios
  2. A Kotlin step definitions file
- Use Cucumber framework for Android
- Write in BDD Given-When-Then format
- Follow Android instrumentation test best practices
- Use AndroidX Test and Espresso where needed
- Create realistic scenarios based on the project structure

$projectInfo

Generate BOTH files with the following structure:

=== FEATURE FILE START ===
[Generate complete .feature file here with proper Gherkin syntax]
=== FEATURE FILE END ===

=== STEP DEFINITIONS START ===
[Generate complete Kotlin step definitions file here]
=== STEP DEFINITIONS END ===

Feature File Guidelines:
- Use proper Gherkin syntax (Feature, Scenario, Given, When, Then)
- Create realistic scenarios based on project components
- Include multiple scenarios covering different use cases
- Use descriptive scenario names
- Add background steps if needed

Step Definitions Guidelines:
- Package: ${request.projectContext.firstOrNull()?.packageName ?: "com.example.test"}
- Import necessary Cucumber annotations (@Given, @When, @Then)
- Import AndroidX Test and Espresso
- Implement step definitions with actual test logic
- Use descriptive parameter names
- Include assertions

Return the content with clear separators as shown above.
    """.trimIndent()
    }

    private fun parseBDDResponse(responseBody: String, request: AIRequest): AIResponse {
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

        // Parse feature file and step definitions
        val featureFilePattern = """=== FEATURE FILE START ===\s*(.*?)\s*=== FEATURE FILE END ===""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val stepDefsPattern = """=== STEP DEFINITIONS START ===\s*(.*?)\s*=== STEP DEFINITIONS END ===""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val featureFileContent = featureFilePattern.find(content)?.groupValues?.get(1)?.trim()
            ?: throw Exception("Could not parse feature file from response")

        val stepDefinitionsContent = stepDefsPattern.find(content)?.groupValues?.get(1)?.trim()
            ?: throw Exception("Could not parse step definitions from response")

        // Clean up any markdown formatting
        val cleanedFeature = featureFileContent
            .removePrefix("```gherkin")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val cleanedSteps = stepDefinitionsContent
            .removePrefix("```kotlin")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val packageName = extractPackageName(cleanedSteps).ifEmpty {
            request.projectContext.firstOrNull()?.packageName ?: "com.example.test"
        }

        return AIResponse(
            testCode = cleanedSteps,
            testClassName = extractClassName(cleanedSteps).ifEmpty { "StepDefinitions" },
            packageName = packageName,
            newTestMethods = emptyList(),
            mergeMode = MergeMode.CREATE_NEW,
            featureFileContent = cleanedFeature,
            stepDefinitionsContent = cleanedSteps
        )
    }

    private fun buildRequestBody(prompt: String, settings: PluginSettings.State): String {
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

    private fun parseResponse(responseBody: String, request: AIRequest): AIResponse {
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