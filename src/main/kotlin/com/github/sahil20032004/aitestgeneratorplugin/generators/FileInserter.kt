package com.github.sahil20032004.aitestgeneratorplugin.generators

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile
import java.io.IOException

class FileInserter(private val project: Project) {

    fun insertOrUpdateTest(generatedTest: TestGenerator.GeneratedTest): InsertionResult {
        return if (generatedTest.isUpdate && generatedTest.existingFile != null) {
            updateExistingTestFile(generatedTest)
        } else {
            createNewTestFile(generatedTest)
        }
    }

    private fun updateExistingTestFile(generatedTest: TestGenerator.GeneratedTest): InsertionResult {
        val existingFile = generatedTest.existingFile
            ?: return InsertionResult(false, "Existing file not found", null)

        return WriteCommandAction.runWriteCommandAction<InsertionResult>(project, {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(existingFile) as? KtFile
                    ?: return@runWriteCommandAction InsertionResult(false, "Could not parse existing file", null)

                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runWriteCommandAction InsertionResult(false, "Could not get document", null)

                // Replace entire file content with merged code
                ApplicationManager.getApplication().runWriteAction {
                    document.setText(generatedTest.code)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }

                InsertionResult(
                    success = true,
                    message = "Successfully updated ${generatedTest.className} with ${generatedTest.newMethodsCount} new test methods",
                    file = existingFile
                )
            } catch (e: Exception) {
                InsertionResult(false, "Error updating file: ${e.message}", null)
            }
        })
    }

    private fun createNewTestFile(generatedTest: TestGenerator.GeneratedTest): InsertionResult {
        return WriteCommandAction.runWriteCommandAction<InsertionResult>(project, {
            try {
                val testSourceRoot = when (generatedTest.scope) {
                    com.github.sahil20032004.aitestgeneratorplugin.models.TestScope.UNIT -> "src/test/java"
                    com.github.sahil20032004.aitestgeneratorplugin.models.TestScope.INSTRUMENTATION -> "src/androidTest/java"
                }

                val packagePath = generatedTest.packageName.replace('.', '/')
                val fullPath = "$testSourceRoot/$packagePath"
                val fileName = "${generatedTest.className}.kt"

                // Create directories
                val baseDir = project.baseDir ?: return@runWriteCommandAction InsertionResult(
                    false,
                    "Could not find project base directory",
                    null
                )

                val targetDir = createDirectories(baseDir, fullPath)
                    ?: return@runWriteCommandAction InsertionResult(
                        false,
                        "Could not create directory: $fullPath",
                        null
                    )

                // Create file
                val newFile = ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                    targetDir.findOrCreateChildData(null, fileName).apply {
                        setBinaryContent(generatedTest.code.toByteArray())
                    }
                }

                InsertionResult(
                    success = true,
                    message = "Successfully created ${generatedTest.className} with ${generatedTest.newMethodsCount} test methods",
                    file = newFile
                )
            } catch (e: Exception) {
                InsertionResult(false, "Error creating file: ${e.message}", null)
            }
        })
    }

    private fun createDirectories(baseDir: VirtualFile, path: String): VirtualFile? {
        return try {
            ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                var current = baseDir
                path.split('/').forEach { segment ->
                    current = current.findChild(segment) ?: current.createChildDirectory(null, segment)
                }
                current
            }
        } catch (_: IOException) {
            null
        }
    }

    data class InsertionResult(
        val success: Boolean,
        val message: String,
        val file: VirtualFile?
    )
}