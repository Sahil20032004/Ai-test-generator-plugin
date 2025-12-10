package com.github.sahil20032004.aitestgeneratorplugin.models

data class AIResponse(
    val testCode: String,
    val testClassName: String,
    val packageName: String,
    val newTestMethods: List<String>,
    val mergeMode: MergeMode,
    val featureFileContent: String? = null,  // New field for .feature file
    val stepDefinitionsContent: String? = null  // New field for step definitions
)

enum class MergeMode {
    CREATE_NEW,
    APPEND_TO_EXISTING
}