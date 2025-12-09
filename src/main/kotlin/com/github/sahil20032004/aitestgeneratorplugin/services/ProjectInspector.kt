package com.github.sahil20032004.aitestgeneratorplugin.services

import com.github.sahil20032004.aitestgeneratorplugin.models.FileContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

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

    fun getProjectContext(maxFiles: Int = 10): List<FileContext> {
        val contexts = mutableListOf<FileContext>()
        // Simplified: just get some representative files
        // In production, you'd want smarter selection
        return contexts
    }

    private fun extractPublicMethods(ktClass: KtClass): List<String> {
        return ktClass.declarations
            .filterIsInstance<org.jetbrains.kotlin.psi.KtNamedFunction>()
            .filter { function ->
                // A function is public if it has no visibility modifier or has 'public' modifier
                !function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PRIVATE_KEYWORD) &&
                !function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.PROTECTED_KEYWORD) &&
                !function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.INTERNAL_KEYWORD)
            }
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