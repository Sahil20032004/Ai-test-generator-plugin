package com.github.sahil20032004.aitestgeneratorplugin.actions

import com.github.sahil20032004.aitestgeneratorplugin.generators.TestGenerator
import com.github.sahil20032004.aitestgeneratorplugin.services.OpenAIClient
import com.github.sahil20032004.aitestgeneratorplugin.settings.PluginSettings
import com.github.sahil20032004.aitestgeneratorplugin.ui.PreviewDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.runBlocking
import com.github.sahil20032004.aitestgeneratorplugin.services.AIServiceFactory
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.vfs.VirtualFile


class GenerateUnitTestAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val virtualFile = getTargetFile(e) ?: return

        if (!isValidSourceFile(virtualFile)) {
            Messages.showErrorDialog(
                project,
                "Please select a Kotlin or Java source file",
                "Invalid File Type"
            )
            return
        }

        val validation = AIServiceFactory.validateConfiguration()
        if (!validation.isValid) {
            Messages.showErrorDialog(
                project,
                validation.message ?: "AI service not configured",
                "Configuration Error"
            )
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Generating Unit Tests...", true) {
            var generatedTest: TestGenerator.GeneratedTest? = null
            var error: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Analyzing source file..."

                try {
                    runBlocking {
                        val aiService = AIServiceFactory.createService()
                        val generator = TestGenerator(project, aiService)

                        indicator.text = "Generating tests with AI..."
                        generatedTest = generator.generateUnitTest(virtualFile)
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
        val project = e.project
        val targetFile = getTargetFile(e)

        val isEnabled = project != null && targetFile != null && isValidSourceFile(targetFile)

        e.presentation.isEnabledAndVisible = isEnabled

        if (isEnabled) {
            e.presentation.text = "AI Generate Unit Tests"
            e.presentation.description = "Generate unit tests for ${targetFile?.name} using AI"
        } else {
            e.presentation.text = "AI Generate Unit Tests"
            e.presentation.description = "Select a Kotlin or Java source file to generate tests"
        }
    }

    private fun getTargetFile(e: AnActionEvent): VirtualFile? {
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let { return it }
        e.getData(CommonDataKeys.PSI_FILE)?.virtualFile?.let { return it }
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.firstOrNull()?.let { return it }
        e.getData(PlatformDataKeys.VIRTUAL_FILE)?.let { return it }
        return null
    }

    private fun isValidSourceFile(file: VirtualFile): Boolean {
        return !file.isDirectory && file.extension?.lowercase() in listOf("kt", "java")
    }
}