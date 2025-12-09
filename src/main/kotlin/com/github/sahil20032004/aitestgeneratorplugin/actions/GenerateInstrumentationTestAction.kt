package com.github.sahil20032004.aitestgeneratorplugin.actions


import com.github.sahil20032004.aitestgeneratorplugin.generators.TestGenerator
import com.github.sahil20032004.aitestgeneratorplugin.services.AIServiceFactory
import com.github.sahil20032004.aitestgeneratorplugin.ui.PreviewDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.runBlocking

class GenerateInstrumentationTestAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val validation = AIServiceFactory.validateConfiguration()
        if (!validation.isValid) {
            Messages.showErrorDialog(
                project,
                validation.message ?: "AI service not configured",
                "Configuration Error"
            )
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Instrumentation Tests...", true) {
            var generatedTest: TestGenerator.GeneratedTest? = null
            var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Analyzing project structure..."

                try {
                    runBlocking {
                        val aiService = AIServiceFactory.createService()
                        val generator = TestGenerator(project, aiService)

                        indicator.text = "Generating instrumentation tests with AI..."
                        generatedTest = generator.generateInstrumentationTest()
                    }
                } catch (e: Exception) {
                    error = e.message ?: "Unknown error occurred"
                }
            }

            override fun onSuccess() {
                generatedTest?.let { test ->
                    PreviewDialog(project, test).show()
                } ?: run {
                    Messages.showErrorDialog(project, error ?: "Failed to generate tests", "Error")
                }
            }

            override fun onThrowable(e: Throwable) {
                Messages.showErrorDialog(project, e.message ?: "Unknown error", "Error")
            }
        })
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}