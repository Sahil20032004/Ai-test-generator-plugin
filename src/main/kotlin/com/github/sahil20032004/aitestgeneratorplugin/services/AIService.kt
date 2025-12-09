package com.github.sahil20032004.aitestgeneratorplugin.services

import com.github.sahil20032004.aitestgeneratorplugin.models.AIRequest
import com.github.sahil20032004.aitestgeneratorplugin.models.AIResponse

interface AIService {
    suspend fun generateTests(request: AIRequest): AIResponse
}