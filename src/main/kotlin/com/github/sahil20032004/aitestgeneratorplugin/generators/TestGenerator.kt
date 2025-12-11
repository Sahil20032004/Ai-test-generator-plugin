package com.github.sahil20032004.aitestgeneratorplugin.generators

import com.github.sahil20032004.aitestgeneratorplugin.models.AIRequest
import com.github.sahil20032004.aitestgeneratorplugin.models.AIResponse
import com.github.sahil20032004.aitestgeneratorplugin.models.MergeMode
import com.github.sahil20032004.aitestgeneratorplugin.models.TestScope
import com.github.sahil20032004.aitestgeneratorplugin.services.AIService
import com.github.sahil20032004.aitestgeneratorplugin.services.ProjectInspector
import com.github.sahil20032004.aitestgeneratorplugin.settings.PluginSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.psi.KtFile

class TestGenerator(
    private val project: Project,
    private val aiService: AIService
) {

    suspend fun generateUnitTest(sourceFile: VirtualFile): GeneratedTest {
        val inspector = ProjectInspector(project)
        val analysis = when {
            sourceFile.extension == "kt" -> inspector.analyzeKotlinFile(sourceFile)
            sourceFile.extension == "java" -> inspector.analyzeJavaFile(sourceFile)
            else -> throw IllegalArgumentException("Unsupported file type: ${sourceFile.extension}")
        } ?: throw IllegalStateException("Could not analyze file: ${sourceFile.path}")

        // Check if test file already exists
        val existingTestFile = findExistingTestFile(analysis.packageName, analysis.className, TestScope.UNIT)
        val existingTestCode = existingTestFile?.let { readFileContent(it) }
        val existingTestMethods = existingTestCode?.let { extractExistingTestMethods(it) } ?: emptyList()

        val request = AIRequest(
            scope = TestScope.UNIT,
            targetFilePath = sourceFile.path,
            targetClassName = analysis.fullyQualifiedClassName,
            sourceCode = analysis.sourceCode,
            projectContext = emptyList(),
            existingTestCode = existingTestCode,
            existingTestMethods = existingTestMethods
        )

        val response = aiService.generateTests(request)

        val finalCode = if (existingTestCode != null && response.mergeMode == MergeMode.APPEND_TO_EXISTING) {
            mergeTestCode(existingTestCode, response.testCode, existingTestMethods, response.newTestMethods)
        } else {
            response.testCode
        }

        return GeneratedTest(
            code = finalCode,
            packageName = response.packageName,
            className = response.testClassName,
            scope = TestScope.UNIT,
            targetSourceFile = sourceFile,
            isUpdate = existingTestFile != null,
            existingFile = existingTestFile,
            newMethodsCount = response.newTestMethods.size
        )
    }

    suspend fun generateInstrumentationTest(): GeneratedTest {
        val inspector = ProjectInspector(project)
        val projectContext = inspector.getProjectContext(maxFiles = 15)
        val settings = PluginSettings.getInstance().state

        val basePackageName = projectContext.firstOrNull()?.packageName?.takeIf { it.isNotEmpty() }
            ?: project.name.lowercase().replace("-", "").replace("_", "")

        val existingTestFile = findExistingInstrumentationTestFile()
        val existingTestCode = existingTestFile?.let { readFileContent(it) }
        val existingTestMethods = existingTestCode?.let { extractExistingTestMethods(it) } ?: emptyList()

        val request = AIRequest(
            scope = TestScope.INSTRUMENTATION,
            targetFilePath = null,
            targetClassName = null,
            sourceCode = null,
            projectContext = projectContext,
            existingTestCode = existingTestCode,
            existingTestMethods = existingTestMethods,
            useBDD = settings.useBDDForInstrumentation
        )

        val response = aiService.generateTests(request)

        val finalCode = if (existingTestCode != null && response.mergeMode == MergeMode.APPEND_TO_EXISTING) {
            mergeTestCode(existingTestCode, response.testCode, existingTestMethods, response.newTestMethods)
        } else {
            response.testCode
        }

        val packageName = response.packageName.ifEmpty { basePackageName }

        return GeneratedTest(
            code = finalCode,
            packageName = packageName,
            className = response.testClassName.ifEmpty { "${project.name.replace("-", "")}InstrumentedTest" },
            scope = TestScope.INSTRUMENTATION,
            targetSourceFile = null,
            isUpdate = existingTestFile != null,
            existingFile = existingTestFile,
            newMethodsCount = response.newTestMethods.size,
            featureFileContent = response.featureFileContent,
            stepDefinitionsContent = response.stepDefinitionsContent,
            testRunnerContent = response.testRunnerContent
        )
    }

    private fun mergeTestCode(
        existingCode: String,
        newCode: String,
        existingMethods: List<String>,
        newMethods: List<String>
    ): String {
        // Extract only new test methods from generated code
        val newTestMethods = extractTestMethodsCode(newCode, newMethods)

        // Find insertion point (before closing brace of class)
        val insertionPoint = existingCode.lastIndexOf('}')
        if (insertionPoint == -1) {
            return newCode // Fallback to new code if can't parse existing
        }

        val beforeClosing = existingCode.substring(0, insertionPoint).trimEnd()
        val afterClosing = existingCode.substring(insertionPoint)

        return buildString {
            append(beforeClosing)
            append("\n\n")
            append("    // ========== AI Generated Tests (${java.time.LocalDateTime.now()}) ==========\n")
            newTestMethods.forEach { method ->
                append("\n")
                append(method.prependIndent("    "))
                append("\n")
            }
            append(afterClosing)
        }
    }

    private fun extractExistingTestMethods(code: String): List<String> {
        val methodRegex = """@Test\s+fun\s+(\w+)\s*\(""".toRegex()
        return methodRegex.findAll(code).map { it.groupValues[1] }.toList()
    }

    private fun extractTestMethodsCode(code: String, methodNames: List<String>): List<String> {
        val methods = mutableListOf<String>()

        methodNames.forEach { methodName ->
            // Find method by annotation + name
            val methodRegex = """(@Test[^}]*fun\s+$methodName\s*\([^)]*\)\s*\{(?:[^{}]|\{[^{}]*\})*\})""".toRegex(RegexOption.DOT_MATCHES_ALL)
            methodRegex.find(code)?.let { match ->
                methods.add(match.groupValues[1].trim())
            }
        }

        return methods
    }

    private fun findExistingTestFile(packageName: String, className: String, scope: TestScope): VirtualFile? {
        val testFileName = "${className}Test.kt"
        val testSourceRoot = when (scope) {
            TestScope.UNIT -> "src/test/java"
            TestScope.INSTRUMENTATION -> "src/androidTest/java"
        }

        val packagePath = packageName.replace('.', '/')
        val fullPath = "$$testSourceRoot/$$packagePath/$testFileName"

        return project.baseDir?.findFileByRelativePath(fullPath)
    }

    private fun findExistingInstrumentationTestFile(): VirtualFile? {
        val testSourceRoot = "src/androidTest/java"
        val projectName = project.name.replace("-", "").replace("_", "")
        val testFileName = "${projectName}InstrumentedTest.kt"

        // Try to find in common locations
        return project.baseDir?.findFileByRelativePath("$$testSourceRoot/$$testFileName")
    }

    private fun readFileContent(file: VirtualFile): String {
        val psiFile = PsiManager.getInstance(project).findFile(file)
        return psiFile?.text ?: file.inputStream.bufferedReader().use { it.readText() }
    }

    data class GeneratedTest(
        val code: String,
        val packageName: String,
        val className: String,
        val scope: TestScope,
        val targetSourceFile: VirtualFile?,
        val isUpdate: Boolean,
        val existingFile: VirtualFile?,
        val newMethodsCount: Int,
        val featureFileContent: String? = null,
        val stepDefinitionsContent: String? = null,
        val testRunnerContent: String? = null  // NEW
    )
}