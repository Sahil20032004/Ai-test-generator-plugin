package com.github.sahil20032004.aitestgeneratorplugin.services

import com.github.sahil20032004.aitestgeneratorplugin.models.FileContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtNamedFunction

class ProjectInspector(private val project: Project) {

    fun analyzeKotlinFile(file: VirtualFile): FileAnalysis? {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return null

        val ktClass = PsiTreeUtil.findChildOfType(psiFile, KtClass::class.java) ?: return null
        val packageName = psiFile.packageFqName.asString()
        val className = ktClass.name ?: return null
        val fullyQualifiedName = if (packageName.isNotEmpty()) {
            "$packageName.$className"
        } else {
            className
        }

        return FileAnalysis(
            packageName = packageName,
            className = className,
            fullyQualifiedClassName = fullyQualifiedName,
            sourceCode = psiFile.text,
            filePath = file.path,
            publicMethods = extractPublicMethods(ktClass)
        )
    }

    fun analyzeJavaFile(file: VirtualFile): FileAnalysis? {
        val psiFile = PsiManager.getInstance(project).findFile(file) as? PsiJavaFile ?: return null

        val psiClass = psiFile.classes.firstOrNull() ?: return null
        val packageName = psiFile.packageName
        val className = psiClass.name ?: return null
        val fullyQualifiedName = psiClass.qualifiedName ?: className

        return FileAnalysis(
            packageName = packageName,
            className = className,
            fullyQualifiedClassName = fullyQualifiedName,
            sourceCode = psiFile.text,
            filePath = file.path,
            publicMethods = extractPublicMethodsJava(psiClass)
        )
    }

    fun getProjectContext(maxFiles: Int = 20): List<FileContext> {
        val contexts = mutableListOf<FileContext>()

        try {
            // Get all Kotlin files in the project (excluding test directories)
            val scope = GlobalSearchScope.projectScope(project)
            val kotlinFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)

            var fileCount = 0
            for (virtualFile in kotlinFiles) {
                if (fileCount >= maxFiles) break

                // Skip test files
                if (isTestFile(virtualFile)) continue

                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile
                if (psiFile != null) {
                    val packageName = psiFile.packageFqName.asString()
                    val classes = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)

                    if (classes.isNotEmpty()) {
                        // Get a summary of the file instead of full content
                        val summary = buildFileSummary(psiFile, classes)

                        contexts.add(
                            FileContext(
                                path = virtualFile.path.substringAfter("src/main/"),
                                content = summary,
                                packageName = packageName
                            )
                        )
                        fileCount++
                    }
                }
            }

            // If no files found, add at least basic project info
            if (contexts.isEmpty()) {
                contexts.add(
                    FileContext(
                        path = "Project: ${project.name}",
                        content = "Android project with Kotlin",
                        packageName = ""
                    )
                )
            }

        } catch (e: Exception) {
            // Fallback: provide basic project info
            contexts.add(
                FileContext(
                    path = "Project: ${project.name}",
                    content = "Android project - analysis failed: ${e.message}",
                    packageName = ""
                )
            )
        }

        return contexts
    }

    private fun buildFileSummary(psiFile: KtFile, classes: Collection<KtClass>): String {
        return buildString {
            appendLine("File: ${psiFile.name}")
            appendLine("Package: ${psiFile.packageFqName}")
            appendLine()

            classes.forEach { ktClass ->
                appendLine("Class: ${ktClass.name}")

                // List public functions
                val functions = ktClass.declarations.filterIsInstance<KtNamedFunction>()
                if (functions.isNotEmpty()) {
                    appendLine("Public methods:")
                    functions.forEach { func ->
                        val params = func.valueParameters.joinToString(", ") {
                            "${it.name}: ${it.typeReference?.text ?: "?"}"
                        }
                        val returnType = func.typeReference?.text ?: "Unit"
                        appendLine("  - ${func.name}($params): $returnType")
                    }
                }
                appendLine()
            }
        }
    }

    private fun isTestFile(file: VirtualFile): Boolean {
        val path = file.path
        return path.contains("/test/") ||
                path.contains("/androidTest/") ||
                file.name.endsWith("Test.kt") ||
                file.name.endsWith("Tests.kt")
    }

    private fun extractPublicMethods(ktClass: KtClass): List<String> {
        return ktClass.declarations
            .filterIsInstance<KtNamedFunction>()
            .mapNotNull { it.name }
    }

    private fun extractPublicMethodsJava(psiClass: PsiClass): List<String> {
        return psiClass.methods
            .filter { it.hasModifierProperty(PsiModifier.PUBLIC) }
            .mapNotNull { it.name }
    }

    data class FileAnalysis(
        val packageName: String,
        val className: String,
        val fullyQualifiedClassName: String,
        val sourceCode: String,
        val filePath: String,
        val publicMethods: List<String>
    )
}