package com.github.sahil20032004.aitestgeneratorplugin.generators

import com.intellij.openapi.application.ApplicationManager
import com.github.sahil20032004.aitestgeneratorplugin.models.TestScope
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

        return WriteCommandAction.runWriteCommandAction<InsertionResult>(project) {
            try {
                val psiFile = PsiManager.getInstance(project).findFile(existingFile) as? KtFile
                    ?: return@runWriteCommandAction InsertionResult(false, "Could not parse existing file", null)

                val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
                    ?: return@runWriteCommandAction InsertionResult(false, "Could not get document", null)

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
        }
    }

    private fun createNewTestFile(generatedTest: TestGenerator.GeneratedTest): InsertionResult {
        return WriteCommandAction.runWriteCommandAction<InsertionResult>(project) {
            try {
                // Determine the correct source root based on test scope
                val sourceRootPath = when (generatedTest.scope) {
                    TestScope.UNIT -> "app/src/test/java"
                    TestScope.INSTRUMENTATION -> "app/src/androidTest/java"
                }

                val packagePath = generatedTest.packageName.replace('.', '/')
                val fullPath = "$sourceRootPath/$packagePath"
                val fileName = "${generatedTest.className}.kt"

                // Get project base directory
                val baseDir = project.baseDir
                    ?: return@runWriteCommandAction InsertionResult(
                        false,
                        "Could not find project base directory",
                        null
                    )

                // Find or create the source root
                val sourceRoot = findOrCreateSourceRoot(baseDir, sourceRootPath)
                    ?: return@runWriteCommandAction InsertionResult(
                        false,
                        "Could not create source root: $sourceRootPath",
                        null
                    )

                // Create package directories
                val targetDir = createDirectories(sourceRoot, packagePath)
                    ?: return@runWriteCommandAction InsertionResult(
                        false,
                        "Could not create directory: $packagePath",
                        null
                    )

                // Create the test file
                val newFile = ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                    val file = targetDir.findChild(fileName)
                    if (file != null) {
                        // File already exists, overwrite it
                        file.setBinaryContent(generatedTest.code.toByteArray())
                        file
                    } else {
                        // Create new file
                        targetDir.createChildData(this, fileName).apply {
                            setBinaryContent(generatedTest.code.toByteArray())
                        }
                    }
                }

                // Refresh the file system
                sourceRoot.refresh(false, true)

                InsertionResult(
                    success = true,
                    message = "Successfully created ${generatedTest.className} with ${generatedTest.newMethodsCount} test methods at $fullPath/$fileName\n\nNote: Android Studio should automatically recognize this as a test source root. If not, right-click the folder → Mark Directory as → Test Sources Root.",
                    file = newFile
                )
            } catch (e: Exception) {
                InsertionResult(false, "Error creating file: ${e.message}\n${e.stackTraceToString()}", null)
            }
        }
    }

    private fun findOrCreateSourceRoot(baseDir: VirtualFile, sourceRootPath: String): VirtualFile? {
        return try {
            ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                var current = baseDir

                // Split path and create each directory
                val parts = sourceRootPath.split('/')
                for (part in parts) {
                    current = current.findChild(part) ?: current.createChildDirectory(this, part)
                }

                current
            }
        } catch (e: IOException) {
            null
        }
    }

    private fun createDirectories(baseDir: VirtualFile, path: String): VirtualFile? {
        if (path.isEmpty()) return baseDir

        return try {
            ApplicationManager.getApplication().runWriteAction<VirtualFile> {
                var current = baseDir
                path.split('/').forEach { segment ->
                    current = current.findChild(segment) ?: current.createChildDirectory(this, segment)
                }
                current
            }
        } catch (e: IOException) {
            null
        }
    }

    data class InsertionResult(
        val success: Boolean,
        val message: String,
        val file: VirtualFile?
    )
}