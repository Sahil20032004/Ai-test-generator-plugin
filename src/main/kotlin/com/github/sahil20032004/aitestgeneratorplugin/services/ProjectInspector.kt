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
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.diagnostic.Logger

class ProjectInspector(private val project: Project) {

    private val logger = Logger.getInstance(ProjectInspector::class.java)

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

    fun getProjectContext(maxFiles: Int = 30): List<FileContext> {
        val contexts = mutableListOf<FileContext>()

        try {
            logger.info("Starting project analysis...")

            val scope = GlobalSearchScope.projectScope(project)

            // 1. Analyze Kotlin files
            val kotlinFiles = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, scope)
            logger.info("Found ${kotlinFiles.size} Kotlin files")

            val kotlinContexts = analyzeKotlinFiles(kotlinFiles, maxFiles / 2)
            contexts.addAll(kotlinContexts)

            // 2. Analyze Java files
            val javaFiles = FileTypeIndex.getFiles(JavaFileType.INSTANCE, scope)
            logger.info("Found ${javaFiles.size} Java files")

            val javaContexts = analyzeJavaFiles(javaFiles, maxFiles / 2)
            contexts.addAll(javaContexts)

            // 3. Add project summary
            val projectSummary = buildProjectSummary(contexts)
            contexts.add(0, FileContext(
                path = "PROJECT_SUMMARY",
                content = projectSummary,
                packageName = ""
            ))

            logger.info("Analysis complete. Generated ${contexts.size} file contexts")

        } catch (e: Exception) {
            logger.error("Error analyzing project", e)
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

    private fun analyzeKotlinFiles(files: Collection<VirtualFile>, maxFiles: Int): List<FileContext> {
        val contexts = mutableListOf<FileContext>()
        var fileCount = 0

        // Prioritize important Android components
        val prioritizedFiles = prioritizeFiles(files)

        for (virtualFile in prioritizedFiles) {
            if (fileCount >= maxFiles) break
            if (isTestFile(virtualFile)) continue

            try {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? KtFile ?: continue
                val packageName = psiFile.packageFqName.asString()
                val classes = PsiTreeUtil.findChildrenOfType(psiFile, KtClass::class.java)

                if (classes.isNotEmpty()) {
                    val componentType = detectAndroidComponentType(classes.first())
                    val summary = buildKotlinFileSummary(psiFile, classes, componentType)

                    contexts.add(
                        FileContext(
                            path = virtualFile.path.substringAfter("src/main/"),
                            content = summary,
                            packageName = packageName
                        )
                    )
                    fileCount++
                }
            } catch (e: Exception) {
                logger.warn("Error analyzing Kotlin file: ${virtualFile.path}", e)
            }
        }

        return contexts
    }

    private fun analyzeJavaFiles(files: Collection<VirtualFile>, maxFiles: Int): List<FileContext> {
        val contexts = mutableListOf<FileContext>()
        var fileCount = 0

        val prioritizedFiles = prioritizeFiles(files)

        for (virtualFile in prioritizedFiles) {
            if (fileCount >= maxFiles) break
            if (isTestFile(virtualFile)) continue

            try {
                val psiFile = PsiManager.getInstance(project).findFile(virtualFile) as? PsiJavaFile ?: continue
                val packageName = psiFile.packageName
                val classes = psiFile.classes

                if (classes.isNotEmpty()) {
                    val componentType = detectAndroidComponentTypeJava(classes.first())
                    val summary = buildJavaFileSummary(psiFile, classes, componentType)

                    contexts.add(
                        FileContext(
                            path = virtualFile.path.substringAfter("src/main/"),
                            content = summary,
                            packageName = packageName
                        )
                    )
                    fileCount++
                }
            } catch (e: Exception) {
                logger.warn("Error analyzing Java file: ${virtualFile.path}", e)
            }
        }

        return contexts
    }

    private fun prioritizeFiles(files: Collection<VirtualFile>): List<VirtualFile> {
        return files.sortedByDescending { file ->
            val name = file.nameWithoutExtension
            when {
                // Highest priority: Activities
                name.endsWith("Activity") -> 1000
                // High priority: Fragments and ViewModels
                name.endsWith("Fragment") || name.endsWith("ViewModel") -> 900
                // Medium priority: Repositories, Use Cases, Services
                name.contains("Repository") || name.contains("UseCase") || name.contains("Service") -> 700
                // Lower priority: Models and Data classes
                name.contains("Model") || name.contains("Data") || name.contains("Entity") -> 500
                // Lowest: Utils and helpers
                name.contains("Util") || name.contains("Helper") -> 300
                else -> 100
            }
        }
    }

    private fun detectAndroidComponentType(ktClass: KtClass): AndroidComponentType {
        val className = ktClass.name ?: return AndroidComponentType.UNKNOWN
        val superTypes = ktClass.superTypeListEntries.mapNotNull { it.text }

        return when {
            className.endsWith("Activity") || superTypes.any { it.contains("Activity") } ->
                AndroidComponentType.ACTIVITY
            className.endsWith("Fragment") || superTypes.any { it.contains("Fragment") } ->
                AndroidComponentType.FRAGMENT
            className.endsWith("ViewModel") || superTypes.any { it.contains("ViewModel") } ->
                AndroidComponentType.VIEWMODEL
            className.contains("Repository") ->
                AndroidComponentType.REPOSITORY
            className.contains("UseCase") || className.contains("Interactor") ->
                AndroidComponentType.USE_CASE
            className.endsWith("Service") || superTypes.any { it.contains("Service") } ->
                AndroidComponentType.SERVICE
            else -> AndroidComponentType.OTHER
        }
    }

    private fun detectAndroidComponentTypeJava(psiClass: PsiClass): AndroidComponentType {
        val className = psiClass.name ?: return AndroidComponentType.UNKNOWN
        val superClass = psiClass.superClass?.name

        return when {
            className.endsWith("Activity") || superClass?.contains("Activity") == true ->
                AndroidComponentType.ACTIVITY
            className.endsWith("Fragment") || superClass?.contains("Fragment") == true ->
                AndroidComponentType.FRAGMENT
            className.endsWith("ViewModel") || superClass?.contains("ViewModel") == true ->
                AndroidComponentType.VIEWMODEL
            className.contains("Repository") ->
                AndroidComponentType.REPOSITORY
            className.contains("UseCase") || className.contains("Interactor") ->
                AndroidComponentType.USE_CASE
            className.endsWith("Service") || superClass?.contains("Service") == true ->
                AndroidComponentType.SERVICE
            else -> AndroidComponentType.OTHER
        }
    }

    private fun buildKotlinFileSummary(
        psiFile: KtFile,
        classes: Collection<KtClass>,
        componentType: AndroidComponentType
    ): String {
        return buildString {
            appendLine("File: ${psiFile.name}")
            appendLine("Package: ${psiFile.packageFqName}")
            appendLine("Type: ${componentType.name}")
            appendLine()

            classes.forEach { ktClass ->
                appendLine("Class: ${ktClass.name}")

                // List properties
                val properties = ktClass.getProperties()
                if (properties.isNotEmpty()) {
                    appendLine("Properties:")
                    properties.take(10).forEach { prop ->
                        appendLine("  - ${prop.name}: ${prop.typeReference?.text ?: "?"}")
                    }
                }

                // List functions
                val functions = ktClass.declarations.filterIsInstance<KtNamedFunction>()
                if (functions.isNotEmpty()) {
                    appendLine("Methods:")
                    functions.take(15).forEach { func ->
                        val params = func.valueParameters.joinToString(", ") {
                            "${it.name}: ${it.typeReference?.text ?: "?"}"
                        }
                        val returnType = func.typeReference?.text ?: "Unit"
                        appendLine("  - ${func.name}($params): $returnType")
                    }
                }

                // Add component-specific info
                when (componentType) {
                    AndroidComponentType.ACTIVITY -> {
                        appendLine("\nActivity Lifecycle Methods Detected:")
                        functions.filter { it.name in LIFECYCLE_METHODS }.forEach {
                            appendLine("  - ${it.name}()")
                        }
                    }
                    AndroidComponentType.VIEWMODEL -> {
                        appendLine("\nViewModel with LiveData/StateFlow detected")
                    }
                    else -> {}
                }

                appendLine()
            }
        }
    }

    private fun buildJavaFileSummary(
        psiFile: PsiJavaFile,
        classes: Array<PsiClass>,
        componentType: AndroidComponentType
    ): String {
        return buildString {
            appendLine("File: ${psiFile.name}")
            appendLine("Package: ${psiFile.packageName}")
            appendLine("Type: ${componentType.name}")
            appendLine()

            classes.forEach { psiClass ->
                appendLine("Class: ${psiClass.name}")

                // List fields
                val fields = psiClass.fields
                if (fields.isNotEmpty()) {
                    appendLine("Fields:")
                    fields.take(10).forEach { field ->
                        appendLine("  - ${field.name}: ${field.type.presentableText}")
                    }
                }

                // List methods
                val methods = psiClass.methods
                if (methods.isNotEmpty()) {
                    appendLine("Methods:")
                    methods.take(15).forEach { method ->
                        val params = method.parameterList.parameters.joinToString(", ") {
                            "${it.name}: ${it.type.presentableText}"
                        }
                        appendLine("  - ${method.name}($params): ${method.returnType?.presentableText ?: "void"}")
                    }
                }

                appendLine()
            }
        }
    }

    private fun buildProjectSummary(contexts: List<FileContext>): String {
        val activityCount = contexts.count { it.content.contains("Type: ACTIVITY") }
        val fragmentCount = contexts.count { it.content.contains("Type: FRAGMENT") }
        val viewModelCount = contexts.count { it.content.contains("Type: VIEWMODEL") }
        val repositoryCount = contexts.count { it.content.contains("Type: REPOSITORY") }

        return buildString {
            appendLine("=== PROJECT STRUCTURE SUMMARY ===")
            appendLine("Project Name: ${project.name}")
            appendLine("Total Files Analyzed: ${contexts.size - 1}") // -1 for this summary
            appendLine()
            appendLine("Android Components:")
            appendLine("  - Activities: $activityCount")
            appendLine("  - Fragments: $fragmentCount")
            appendLine("  - ViewModels: $viewModelCount")
            appendLine("  - Repositories: $repositoryCount")
            appendLine()
            appendLine("Architecture: ${detectArchitecture(activityCount, fragmentCount, viewModelCount, repositoryCount)}")
            appendLine()
            appendLine("Main Packages:")
            contexts.mapNotNull { it.packageName }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(5)
                .forEach { appendLine("  - $it") }
        }
    }

    private fun detectArchitecture(
        activityCount: Int,
        fragmentCount: Int,
        viewModelCount: Int,
        repositoryCount: Int
    ): String {
        return when {
            viewModelCount > 0 && repositoryCount > 0 -> "MVVM (Model-View-ViewModel)"
            viewModelCount > 0 -> "MVVM-like"
            activityCount > 0 || fragmentCount > 0 -> "Traditional Android"
            else -> "Unknown"
        }
    }

    private fun isTestFile(file: VirtualFile): Boolean {
        val path = file.path
        return path.contains("/test/") ||
                path.contains("/androidTest/") ||
                file.name.endsWith("Test.kt") ||
                file.name.endsWith("Tests.kt") ||
                file.name.endsWith("Test.java") ||
                file.name.endsWith("Tests.java")
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

    enum class AndroidComponentType {
        ACTIVITY,
        FRAGMENT,
        VIEWMODEL,
        REPOSITORY,
        USE_CASE,
        SERVICE,
        OTHER,
        UNKNOWN
    }

    companion object {
        private val LIFECYCLE_METHODS = setOf(
            "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
            "onCreateView", "onViewCreated", "onDestroyView"
        )
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