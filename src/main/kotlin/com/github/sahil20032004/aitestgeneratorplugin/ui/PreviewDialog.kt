package com.github.sahil20032004.aitestgeneratorplugin.ui

import com.github.sahil20032004.aitestgeneratorplugin.generators.FileInserter
import com.github.sahil20032004.aitestgeneratorplugin.generators.TestGenerator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.ui.components.JBTabbedPane
import com.github.sahil20032004.aitestgeneratorplugin.models.ChatContext
import com.github.sahil20032004.aitestgeneratorplugin.models.ChatMessage
import com.github.sahil20032004.aitestgeneratorplugin.services.ChatService
import com.intellij.ui.components.JBTextArea
import javax.swing.*
import kotlinx.coroutines.CoroutineScope
import java.awt.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import javax.swing.border.EmptyBorder
import kotlinx.coroutines.withContext
import java.awt.Font
import java.awt.*

class PreviewDialog(
    private val project: Project,
    private val generatedTest: TestGenerator.GeneratedTest
) : DialogWrapper(project) {

    private val isBDD = generatedTest.featureFileContent != null &&
            generatedTest.stepDefinitionsContent != null &&
            generatedTest.testRunnerContent != null

    private val codeArea = JTextArea().apply {
        text = generatedTest.code
        isEditable = true
        lineWrap = false
        tabSize = 4
        font = Font("Monospaced", Font.PLAIN, 12)
    }

    private val featureArea = if (isBDD) {
        JTextArea().apply {
            text = generatedTest.featureFileContent
            isEditable = true
            lineWrap = false
            tabSize = 4
            font = Font("Monospaced", Font.PLAIN, 12)
        }
    } else null

    private val runnerArea = if (isBDD) {
        JTextArea().apply {
            text = generatedTest.testRunnerContent
            isEditable = true
            lineWrap = false
            tabSize = 4
            font = Font("Monospaced", Font.PLAIN, 12)
        }
    } else null

    // Chat components
    private val chatContext = ChatContext(
        originalCode = generatedTest.code,
        currentCode = generatedTest.code,
        testScope = generatedTest.scope,
        targetClassName = generatedTest.targetSourceFile?.nameWithoutExtension,
        packageName = generatedTest.packageName
    )

    private val chatService = ChatService()
    private val chatHistoryPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = EmptyBorder(10, 10, 10, 10)
    }

    private val chatInputField = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 3
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.GRAY),
            EmptyBorder(5, 5, 5, 5)
        )
    }

    private val sendButton = JButton("Send").apply {
        addActionListener { sendChatMessage() }
    }

    private val chatScrollPane = JBScrollPane(chatHistoryPanel).apply {
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
        preferredSize = Dimension(400, 500)
    }

    init {
        title = if (generatedTest.isUpdate) {
            "Update Test: ${generatedTest.className}"
        } else if (isBDD) {
            "Create BDD Test: ${generatedTest.className}"
        } else {
            "Create Test: ${generatedTest.className}"
        }

        // Add initial system message
        addSystemMessage("AI Test Assistant ready! You can ask me to:\n" +
                "• Add more test cases\n" +
                "• Improve existing tests\n" +
                "• Add edge case coverage\n" +
                "• Refactor test structure\n" +
                "• Add documentation")

        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(10, 10))
        mainPanel.border = JBUI.Borders.empty(10)

        // Info panel
        val infoPanel = createInfoPanel()

        // Split pane: Code on left, Chat on right
        val splitPane = JSplitPane(JSplitPane.HORIZONTAL_SPLIT)
        splitPane.leftComponent = createCodePanel()
        splitPane.rightComponent = createChatPanel()
        splitPane.dividerLocation = 800
        splitPane.resizeWeight = 0.7

        mainPanel.add(infoPanel, BorderLayout.NORTH)
        mainPanel.add(splitPane, BorderLayout.CENTER)

        return mainPanel
    }

    private fun createInfoPanel(): JPanel {
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)

        val modeLabel = JBLabel(
            if (isBDD) {
                "Mode: Create BDD test (Feature + Steps + Runner)"
            } else if (generatedTest.isUpdate) {
                "Mode: Update existing test (${generatedTest.newMethodsCount} new methods will be added)"
            } else {
                "Mode: Create new test (${generatedTest.newMethodsCount} methods)"
            }
        )
        modeLabel.border = JBUI.Borders.emptyBottom(5)

        val packageLabel = JBLabel("Package: ${generatedTest.packageName}")
        packageLabel.border = JBUI.Borders.emptyBottom(5)

        val classLabel = JBLabel("Class: ${generatedTest.className}")
        classLabel.border = JBUI.Borders.emptyBottom(10)

        infoPanel.add(modeLabel)
        infoPanel.add(packageLabel)
        infoPanel.add(classLabel)

        return infoPanel
    }

    private fun createCodePanel(): JComponent {
        if (isBDD && featureArea != null && runnerArea != null) {
            val tabbedPane = JBTabbedPane()

            val featureScrollPane = JBScrollPane(featureArea)
            featureScrollPane.preferredSize = Dimension(800, 600)
            tabbedPane.addTab("1. Feature File (.feature)", featureScrollPane)

            val stepDefsScrollPane = JBScrollPane(codeArea)
            stepDefsScrollPane.preferredSize = Dimension(800, 600)
            tabbedPane.addTab("2. Step Definitions (.kt)", stepDefsScrollPane)

            val runnerScrollPane = JBScrollPane(runnerArea)
            runnerScrollPane.preferredSize = Dimension(800, 600)
            tabbedPane.addTab("3. Test Runner (.kt)", runnerScrollPane)

            return tabbedPane
        } else {
            val scrollPane = JBScrollPane(codeArea)
            scrollPane.preferredSize = Dimension(800, 600)
            return scrollPane
        }
    }

    private fun createChatPanel(): JComponent {
        val chatPanel = JPanel(BorderLayout(5, 5))
        chatPanel.border = BorderFactory.createTitledBorder("AI Chat Assistant")

        // Chat history
        chatPanel.add(chatScrollPane, BorderLayout.CENTER)

        // Input panel
        val inputPanel = JPanel(BorderLayout(5, 5))
        inputPanel.border = EmptyBorder(5, 5, 5, 5)

        val inputScrollPane = JBScrollPane(chatInputField)
        inputPanel.add(inputScrollPane, BorderLayout.CENTER)
        inputPanel.add(sendButton, BorderLayout.EAST)

        chatPanel.add(inputPanel, BorderLayout.SOUTH)

        // Add quick action buttons
        val quickActionsPanel = createQuickActionsPanel()
        chatPanel.add(quickActionsPanel, BorderLayout.NORTH)

        return chatPanel
    }

    private fun createQuickActionsPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT))
        panel.border = EmptyBorder(5, 5, 5, 5)

        val addTestButton = JButton("Add Edge Cases").apply {
            toolTipText = "Add edge case tests"
            addActionListener {
                chatInputField.text = "Add more edge case tests to cover boundary conditions and error scenarios"
                sendChatMessage()
            }
        }

        val improveButton = JButton("Improve Tests").apply {
            toolTipText = "Improve test quality"
            addActionListener {
                chatInputField.text = "Improve these tests with better assertions and more comprehensive coverage"
                sendChatMessage()
            }
        }

        val docButton = JButton("Add Docs").apply {
            toolTipText = "Add documentation"
            addActionListener {
                chatInputField.text = "Add KDoc comments to explain what each test method is testing"
                sendChatMessage()
            }
        }

        panel.add(addTestButton)
        panel.add(improveButton)
        panel.add(docButton)

        return panel
    }

    private fun sendChatMessage() {
        val userMessage = chatInputField.text.trim()
        if (userMessage.isEmpty()) return

        // Add user message to UI
        addUserMessage(userMessage)
        chatInputField.text = ""
        sendButton.isEnabled = false

        // Update context with current code
        chatContext.currentCode = codeArea.text
        chatContext.addMessage(ChatMessage(ChatMessage.ChatRole.USER, userMessage))

        // Send to AI
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = chatService.sendChatMessage(userMessage, chatContext)

                withContext(Dispatchers.Swing) {
                    // Update code area
                    codeArea.text = response.code
                    chatContext.currentCode = response.code

                    // Add assistant response
                    chatContext.addMessage(ChatMessage(ChatMessage.ChatRole.ASSISTANT, response.explanation))
                    addAssistantMessage(response.explanation)

                    sendButton.isEnabled = true
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Swing) {
                    addErrorMessage("Error: ${e.message}")
                    sendButton.isEnabled = true
                }
            }
        }
    }

    private fun addUserMessage(message: String) {
        val messagePanel = createMessageBubble(message, Color(0xE3F2FD), Color.BLACK, true)
        chatHistoryPanel.add(messagePanel)
        chatHistoryPanel.add(Box.createVerticalStrut(10))
        chatHistoryPanel.revalidate()
        scrollToBottom()
    }

    private fun addAssistantMessage(message: String) {
        val messagePanel = createMessageBubble(message, Color(0xF5F5F5), Color.BLACK, false)
        chatHistoryPanel.add(messagePanel)
        chatHistoryPanel.add(Box.createVerticalStrut(10))
        chatHistoryPanel.revalidate()
        scrollToBottom()
    }

    private fun addSystemMessage(message: String) {
        val messagePanel = createMessageBubble(message, Color(0xFFF9C4), Color.BLACK, false)
        chatHistoryPanel.add(messagePanel)
        chatHistoryPanel.add(Box.createVerticalStrut(10))
        chatHistoryPanel.revalidate()
        scrollToBottom()
    }

    private fun addErrorMessage(message: String) {
        val messagePanel = createMessageBubble(message, Color(0xFFCDD2), Color(0xC62828), false)
        chatHistoryPanel.add(messagePanel)
        chatHistoryPanel.add(Box.createVerticalStrut(10))
        chatHistoryPanel.revalidate()
        scrollToBottom()
    }

    private fun createMessageBubble(
        message: String,
        backgroundColor: Color,
        textColor: Color,
        isUser: Boolean
    ): JPanel {
        val bubblePanel = JPanel(BorderLayout())
        bubblePanel.background = backgroundColor
        bubblePanel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(backgroundColor.darker(), 1, true),
            EmptyBorder(8, 12, 8, 12)
        )

        val textArea = JBTextArea(message).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            foreground = textColor
            background = backgroundColor
            font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        }

        bubblePanel.add(textArea, BorderLayout.CENTER)

        val containerPanel = JPanel(BorderLayout())
        containerPanel.isOpaque = false
        containerPanel.border = EmptyBorder(0, if (isUser) 50 else 0, 0, if (isUser) 0 else 50)
        containerPanel.add(bubblePanel, if (isUser) BorderLayout.EAST else BorderLayout.WEST)

        return containerPanel
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            val verticalScrollBar = chatScrollPane.verticalScrollBar
            verticalScrollBar.value = verticalScrollBar.maximum
        }
    }

    override fun doOKAction() {
        val inserter = FileInserter(project)

        if (isBDD) {
            val editedTest = generatedTest.copy(
                code = codeArea.text,
                featureFileContent = featureArea?.text,
                stepDefinitionsContent = codeArea.text,
                testRunnerContent = runnerArea?.text
            )

            val result = inserter.insertBDDFiles(editedTest)

            if (result.success) {
                JOptionPane.showMessageDialog(
                    contentPane,
                    result.message,
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
                super.doOKAction()
            } else {
                JOptionPane.showMessageDialog(
                    contentPane,
                    result.message,
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        } else {
            val editedTest = generatedTest.copy(code = codeArea.text)
            val result = inserter.insertOrUpdateTest(editedTest)

            if (result.success) {
                JOptionPane.showMessageDialog(
                    contentPane,
                    result.message,
                    "Success",
                    JOptionPane.INFORMATION_MESSAGE
                )
                super.doOKAction()
            } else {
                JOptionPane.showMessageDialog(
                    contentPane,
                    result.message,
                    "Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction)
    }
}