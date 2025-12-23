package com.github.sahil20032004.aitestgeneratorplugin.models

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    enum class ChatRole {
        USER,
        ASSISTANT,
        SYSTEM
    }

    fun getFormattedTime(): String {
        return timestamp.format(DateTimeFormatter.ofPattern("HH:mm"))
    }
}

data class ChatContext(
    val originalCode: String,
    var currentCode: String,
    val testScope: TestScope,
    val targetClassName: String?,
    val packageName: String,
    val messages: MutableList<ChatMessage> = mutableListOf()
) {
    fun addMessage(message: ChatMessage) {
        messages.add(message)
    }

    fun getConversationHistory(): String {
        return messages.joinToString("\n\n") {
            "${it.role.name}: ${it.content}"
        }
    }
}