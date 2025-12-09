package com.github.sahil20032004.aitestgeneratorplugin.models

data class AIRequest(
    val scope: TestScope,
    val targetFilePath: String?,
    val targetClassName: String?,
    val sourceCode: String?,
    val projectContext: List<FileContext>,
    val language: String = "KOTLIN",
    val existingTestCode: String? = null,
    val existingTestMethods: List<String> = emptyList()
)

data class FileContext(
    val path: String,
    val content: String,
    val packageName: String
)