package com.github.sahil20032004.aitestgeneratorplugin.ui

import com.github.sahil20032004.aitestgeneratorplugin.generators.FileInserter
import com.github.sahil20032004.aitestgeneratorplugin.generators.TestGenerator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

class PreviewDialog(
    private val project: Project,
    private val generatedTest: TestGenerator.GeneratedTest
) : DialogWrapper(project) {

    private val codeArea = JTextArea().apply {
        text = generatedTest.code
        isEditable = true
        lineWrap = false
        tabSize = 4
        font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
    }

    init {
        title = if (generatedTest.isUpdate) {
            "Update Test: ${generatedTest.className}"
        } else {
            "Create Test: ${generatedTest.className}"
        }
        init()
    }

    public override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = JBUI.Borders.empty(10)

        // Header info
        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)

        val modeLabel = JBLabel(
            if (generatedTest.isUpdate) {
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

        // Code preview
        val scrollPane = JBScrollPane(codeArea)
        scrollPane.preferredSize = Dimension(800, 600)

        panel.add(infoPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    override fun doOKAction() {
        // Update the generated test with edited code
        val editedTest = generatedTest.copy(code = codeArea.text)

        // Insert or update the file
        val inserter = FileInserter(project)
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

    override fun createActions(): Array<Action> {
        return arrayOf(okAction, cancelAction)
    }
}