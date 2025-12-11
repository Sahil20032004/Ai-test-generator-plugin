package com.github.sahil20032004.aitestgeneratorplugin.models

data class AIResponse(
    val testCode: String,
    val testClassName: String,
    val packageName: String,
    val newTestMethods: List<String>,
    val mergeMode: MergeMode,
    val featureFileContent: String? = null,
    val stepDefinitionsContent: String? = null,
    val testRunnerContent: String? = null  // NEW: Test runner class
)

enum class MergeMode {
    CREATE_NEW,
    APPEND_TO_EXISTING
}