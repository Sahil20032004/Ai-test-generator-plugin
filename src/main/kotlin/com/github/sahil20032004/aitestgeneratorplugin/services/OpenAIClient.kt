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

    //TODO add generateTests function from GeminiClient and refactor common code

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

        // Detect if project uses Jetpack Compose
        val hasCompose = request.projectContext.any {
            it.content.contains("Type: COMPOSE_SCREEN") ||
            it.content.contains("Type: COMPOSABLE") ||
            it.content.contains("Jetpack Compose")
        }

        val composeInstructions = if (hasCompose) {
            """

JETPACK COMPOSE TESTING DETECTED:
- Use androidx.compose.ui.test library
- Use ComposeTestRule (createComposeRule() or createAndroidComposeRule())
- Use semantics for test tags: Modifier.testTag("tag_name")
- Use Compose test finders:
  * onNodeWithTag("tag_name")
  * onNodeWithText("text")
  * onNodeWithContentDescription("description")
- Use Compose assertions:
  * assertExists()
  * assertIsDisplayed()
  * assertTextEquals("text")
  * assertIsEnabled()
- Use Compose actions:
  * performClick()
  * performTextInput("text")
  * performScrollTo()
- For navigation testing, verify composables appear/disappear
- Test state changes and recomposition
- Use waitUntil for async operations
- Example:
  ```kotlin
  @get:Rule
  val composeTestRule = createComposeRule()
  
  @Test
  fun testComposableUI() {
      composeTestRule.setContent {
          MyComposable()
      }
      composeTestRule.onNodeWithTag("button").performClick()
      composeTestRule.onNodeWithText("Success").assertIsDisplayed()
  }
  ```
"""
        } else ""

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
$composeInstructions

$projectInfo

${if (request.existingTestCode != null) {
            "Existing Test File:\n```kotlin\n${request.existingTestCode}\n```\n"
        } else ""}

Based on the project structure above, generate instrumentation tests that:
1. Test real components from the project (Activities, ViewModels, Composables, etc.)
2. Include proper package declaration matching the project
3. Import necessary AndroidX Test${if (hasCompose) ", Compose UI Test," else ""} and Espresso libraries
4. Use @RunWith(AndroidJUnit4::class) annotation
5. Include ActivityScenario or ActivityScenarioRule if Activities are present
${if (hasCompose) "6. Use ComposeTestRule and semantic-based testing for Compose UIs\n7. Test Composable functions with proper state management" else "6. Test UI interactions with Espresso if UI components exist"}
8. Include basic smoke tests if no specific components are identified

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

        val basePackage = request.projectContext.firstOrNull()?.packageName ?: "com.example.test"

        // Detect if project uses Jetpack Compose
        val hasCompose = request.projectContext.any {
            it.content.contains("Type: COMPOSE_SCREEN") ||
            it.content.contains("Type: COMPOSABLE") ||
            it.content.contains("Jetpack Compose")
        }

        val composeInstructions = if (hasCompose) {
            """

JETPACK COMPOSE TESTING DETECTED:
For step definitions that test Compose UI:
- Use ComposeTestRule (declare as lateinit var and initialize in @Before)
- Use composeTestRule.setContent { } to launch composables
- Use semantic finders: onNodeWithTag, onNodeWithText, onNodeWithContentDescription
- Use Compose actions: performClick(), performTextInput()
- Use Compose assertions: assertIsDisplayed(), assertTextEquals()
- Example step definition:
  ```kotlin
  @Given("I am on the login screen")
  fun iAmOnTheLoginScreen() {
      composeTestRule.setContent {
          LoginScreen()
      }
  }
  
  @When("I enter email {string}")
  fun iEnterEmail(email: String) {
      composeTestRule.onNodeWithTag("email_field").performTextInput(email)
  }
  
  @Then("I should see {string}")
  fun iShouldSee(text: String) {
      composeTestRule.onNodeWithText(text).assertIsDisplayed()
  }
  ```
"""
        } else ""

        return """
You are an expert Android BDD test developer tasked with generating Cucumber BDD tests.

Requirements:
- Generate THREE separate files:
  1. A Gherkin .feature file with test scenarios
  2. A Kotlin step definitions file
  3. A Kotlin test runner class (CucumberTestRunner)
- Use Cucumber framework for Android
- Write in BDD Given-When-Then format
- Follow Android instrumentation test best practices
- Use AndroidX Test${if (hasCompose) ", Compose UI Test," else ""} and Espresso where needed
- Create realistic scenarios based on the project structure
$composeInstructions

$projectInfo

Generate ALL THREE files with the following structure:

=== FEATURE FILE START ===
[Generate complete .feature file here with proper Gherkin syntax]
=== FEATURE FILE END ===

=== STEP DEFINITIONS START ===
[Generate complete Kotlin step definitions file here]
=== STEP DEFINITIONS END ===

=== TEST RUNNER START ===
[Generate complete Kotlin test runner class here]
=== TEST RUNNER END ===

Feature File Guidelines:
- Use proper Gherkin syntax (Feature, Scenario, Given, When, Then)
- Create realistic scenarios based on project components
- Include multiple scenarios covering different use cases
- Use descriptive scenario names
- Add background steps if needed

Step Definitions Guidelines:
- Package: $basePackage
- Import necessary Cucumber annotations (@Given, @When, @Then)
- Import AndroidX Test${if (hasCompose) ", Compose UI Test," else ""} and Espresso
${if (hasCompose) "- Declare ComposeTestRule as lateinit var\n- Initialize ComposeTestRule in @Before method" else ""}
- Implement step definitions with actual test logic
- Use descriptive parameter names
- Include assertions
- Keep state in class properties
${if (hasCompose) "- Use semantic testing for Compose UI interactions" else ""}

Test Runner Guidelines:
- Package: $basePackage
- Class name: CucumberTestRunner
- Use @RunWith(CucumberAndroidJUnitRunner::class) or extend CucumberAndroidJUnitRunner
- Use @CucumberOptions annotation with:
  - features: Point to "features" directory
  - glue: Point to step definitions package
- No test methods needed (Cucumber discovers scenarios)
- Should be runnable as an instrumentation test

Example Test Runner Structure:
```kotlin
package $basePackage

import io.cucumber.android.runner.CucumberAndroidJUnitRunner
import io.cucumber.junit.CucumberOptions
import org.junit.runner.RunWith

@RunWith(CucumberAndroidJUnitRunner::class)
@CucumberOptions(
    features = ["features"],
    glue = ["$basePackage"]
)
class CucumberTestRunner
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

        // Parse feature file, step definitions, and test runner
        val featureFilePattern = """=== FEATURE FILE START ===\s*(.*?)\s*=== FEATURE FILE END ===""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val stepDefsPattern = """=== STEP DEFINITIONS START ===\s*(.*?)\s*=== STEP DEFINITIONS END ===""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val testRunnerPattern = """=== TEST RUNNER START ===\s*(.*?)\s*=== TEST RUNNER END ===""".toRegex(RegexOption.DOT_MATCHES_ALL)

        val featureFileContent = featureFilePattern.find(content)?.groupValues?.get(1)?.trim()
            ?: throw Exception("Could not parse feature file from response")

        val stepDefinitionsContent = stepDefsPattern.find(content)?.groupValues?.get(1)?.trim()
            ?: throw Exception("Could not parse step definitions from response")

        val testRunnerContent = testRunnerPattern.find(content)?.groupValues?.get(1)?.trim()
            ?: throw Exception("Could not parse test runner from response")

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

        val cleanedRunner = testRunnerContent
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
            stepDefinitionsContent = cleanedSteps,
            testRunnerContent = cleanedRunner
        )
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
