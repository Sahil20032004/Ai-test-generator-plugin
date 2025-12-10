package com.github.sahil20032004.aitestgeneratorplugin.ui

import com.github.sahil20032004.aitestgeneratorplugin.settings.PluginSettings
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.CardLayout
import com.intellij.ui.components.JBCheckBox

class SettingsConfigurable : Configurable {

    private val providerField = ComboBox(arrayOf("GEMINI", "OPENAI"))

    // OpenAI fields
    private val openAIApiKeyField = JBPasswordField()
    private val openAIModelField = ComboBox(arrayOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4-turbo-2024-04-09",
        "gpt-3.5-turbo"
    ))

    // Gemini fields
    private val geminiApiKeyField = JBPasswordField()
    private val geminiModelField = ComboBox(arrayOf(
        "gemini-2.5-flash",
        "gemini-2.5-flash-lite"
    ))

    // Common fields
    private val maxTokensField = JBTextField()
    private val temperatureField = JBTextField()
    private val testFrameworkField = ComboBox(arrayOf("JUNIT5", "JUNIT4"))
    private val mockingLibraryField = ComboBox(arrayOf("MOCKK", "MOCKITO"))
    private val useBDDCheckbox = JBCheckBox("Use Cucumber BDD for Instrumentation Tests")

    private val providerSettingsPanel = JPanel(CardLayout())
    private val openAIPanel = JPanel()
    private val geminiPanel = JPanel()

    override fun getDisplayName(): String = "AI Test Generator"

    override fun createComponent(): JComponent {
        val settings = PluginSettings.getInstance().state

        providerField.selectedItem = settings.aiProvider

        openAIApiKeyField.text = PluginSettings.getOpenAIApiKey() ?: ""
        if (isValidOpenAIModel(settings.openAIModel)) {
            openAIModelField.selectedItem = settings.openAIModel
        } else {
            openAIModelField.selectedItem = "gpt-4o"
        }

        geminiApiKeyField.text = PluginSettings.getGeminiApiKey() ?: ""
        if (isValidGeminiModel(settings.geminiModel)) {
            geminiModelField.selectedItem = settings.geminiModel
        } else {
            geminiModelField.selectedItem = "gemini-2.5-flash"
        }

        maxTokensField.text = settings.maxTokens.toString()
        temperatureField.text = settings.temperature.toString()
        testFrameworkField.selectedItem = settings.testFramework
        mockingLibraryField.selectedItem = settings.mockingLibrary
        useBDDCheckbox.isSelected = settings.useBDDForInstrumentation

        openAIPanel.layout = javax.swing.BoxLayout(openAIPanel, javax.swing.BoxLayout.Y_AXIS)
        val openAIForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("OpenAI API Key:"), openAIApiKeyField, 1, false)
            .addLabeledComponent(JBLabel("OpenAI Model:"), openAIModelField, 1, false)
            .addComponent(JBLabel("<html><i>Get your API key from: <a href='https://platform.openai.com/api-keys'>https://platform.openai.com/api-keys</a></i></html>"))
            .panel
        openAIPanel.add(openAIForm)

        geminiPanel.layout = javax.swing.BoxLayout(geminiPanel, javax.swing.BoxLayout.Y_AXIS)
        val geminiForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Gemini API Key:"), geminiApiKeyField, 1, false)
            .addLabeledComponent(JBLabel("Gemini Model:"), geminiModelField, 1, false)
            .addComponent(JBLabel("<html><i><b>Free Tier Models Available:</b></i></html>"))
            .addComponent(JBLabel("<html><i>• gemini-2.5-flash (Latest, Experimental)</i></html>"))
            .addComponent(JBLabel("<html><i>• gemini-2.5-flash-lite (Stable, Recommended)</i></html>"))
            .addComponent(JBLabel("<html><i><br>Get your FREE API key from: <a href='https://aistudio.google.com/app/apikey'>https://aistudio.google.com/app/apikey</a></i></html>"))
            .panel
        geminiPanel.add(geminiForm)

        providerSettingsPanel.add(geminiPanel, "GEMINI")
        providerSettingsPanel.add(openAIPanel, "OPENAI")

        providerField.addActionListener {
            val cardLayout = providerSettingsPanel.layout as CardLayout
            cardLayout.show(providerSettingsPanel, providerField.selectedItem as String)
        }

        val cardLayout = providerSettingsPanel.layout as CardLayout
        cardLayout.show(providerSettingsPanel, settings.aiProvider)

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("AI Provider:"), providerField, 1, false)
            .addComponent(providerSettingsPanel)
            .addSeparator()
            .addLabeledComponent(JBLabel("Max Tokens:"), maxTokensField, 1, false)
            .addComponent(JBLabel("<html><i>Recommended: 8000 for Flash models, 2000 for others</i></html>"))
            .addLabeledComponent(JBLabel("Temperature (0.0-1.0):"), temperatureField, 1, false)
            .addLabeledComponent(JBLabel("Test Framework:"), testFrameworkField, 1, false)
            .addLabeledComponent(JBLabel("Mocking Library:"), mockingLibraryField, 1, false)
            .addSeparator()
            .addComponent(useBDDCheckbox)
            .addComponent(JBLabel("<html><i>When enabled, generates Cucumber BDD tests with .feature files and step definitions</i></html>"))
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val settings = PluginSettings.getInstance().state

        return providerField.selectedItem != settings.aiProvider ||
                String(openAIApiKeyField.password) != (PluginSettings.getOpenAIApiKey() ?: "") ||
                String(geminiApiKeyField.password) != (PluginSettings.getGeminiApiKey() ?: "") ||
                openAIModelField.selectedItem != settings.openAIModel ||
                geminiModelField.selectedItem != settings.geminiModel ||
                maxTokensField.text != settings.maxTokens.toString() ||
                temperatureField.text != settings.temperature.toString() ||
                testFrameworkField.selectedItem != settings.testFramework ||
                mockingLibraryField.selectedItem != settings.mockingLibrary ||
                useBDDCheckbox.isSelected != settings.useBDDForInstrumentation
    }

    override fun apply() {
        val settings = PluginSettings.getInstance().state

        settings.aiProvider = providerField.selectedItem as String

        PluginSettings.setOpenAIApiKey(String(openAIApiKeyField.password))
        PluginSettings.setGeminiApiKey(String(geminiApiKeyField.password))

        settings.openAIModel = openAIModelField.selectedItem as String
        settings.geminiModel = geminiModelField.selectedItem as String
        settings.maxTokens = maxTokensField.text.toIntOrNull() ?: 8000
        settings.temperature = temperatureField.text.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.3
        settings.testFramework = testFrameworkField.selectedItem as String
        settings.mockingLibrary = mockingLibraryField.selectedItem as String
        settings.useBDDForInstrumentation = useBDDCheckbox.isSelected
    }

    override fun reset() {
        val settings = PluginSettings.getInstance().state

        providerField.selectedItem = settings.aiProvider

        openAIApiKeyField.text = PluginSettings.getOpenAIApiKey() ?: ""
        geminiApiKeyField.text = PluginSettings.getGeminiApiKey() ?: ""

        if (isValidOpenAIModel(settings.openAIModel)) {
            openAIModelField.selectedItem = settings.openAIModel
        } else {
            openAIModelField.selectedItem = "gpt-4o"
        }

        if (isValidGeminiModel(settings.geminiModel)) {
            geminiModelField.selectedItem = settings.geminiModel
        } else {
            geminiModelField.selectedItem = "gemini-2.5-flash"
        }

        maxTokensField.text = settings.maxTokens.toString()
        temperatureField.text = settings.temperature.toString()
        testFrameworkField.selectedItem = settings.testFramework
        mockingLibraryField.selectedItem = settings.mockingLibrary
        useBDDCheckbox.isSelected = settings.useBDDForInstrumentation

        val cardLayout = providerSettingsPanel.layout as CardLayout
        cardLayout.show(providerSettingsPanel, settings.aiProvider)
    }

    private fun isValidOpenAIModel(model: String): Boolean {
        return model in listOf("gpt-4o", "gpt-4o-mini", "gpt-4-turbo-2024-04-09", "gpt-3.5-turbo")
    }

    private fun isValidGeminiModel(model: String): Boolean {
        return model in listOf(
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite"
        )
    }
}