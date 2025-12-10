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
import com.intellij.ui.components.JBTabbedPane
import java.awt.Font


class PreviewDialog(
    private val project: Project,
    private val generatedTest: TestGenerator.GeneratedTest
) : DialogWrapper(project) {

    private val isBDD = generatedTest.featureFileContent != null && generatedTest.stepDefinitionsContent != null

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

    init {
        title = if (generatedTest.isUpdate) {
            "Update Test: ${generatedTest.className}"
        } else if (isBDD) {
            "Create BDD Test: ${generatedTest.className}"
        } else {
            "Create Test: ${generatedTest.className}"
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = JBUI.Borders.empty(10)

        val infoPanel = JPanel()
        infoPanel.layout = BoxLayout(infoPanel, BoxLayout.Y_AXIS)

        val modeLabel = JBLabel(
            if (isBDD) {
                "Mode: Create BDD test (Feature file + Step definitions)"
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

        if (isBDD && featureArea != null) {
            // Use tabbed pane for BDD
            val tabbedPane = JBTabbedPane()

            val featureScrollPane = JBScrollPane(featureArea)
            featureScrollPane.preferredSize = Dimension(800, 600)
            tabbedPane.addTab("Feature File (.feature)", featureScrollPane)

            val stepDefsScrollPane = JBScrollPane(codeArea)
            stepDefsScrollPane.preferredSize = Dimension(800, 600)
            tabbedPane.addTab("Step Definitions (.kt)", stepDefsScrollPane)

            panel.add(infoPanel, BorderLayout.NORTH)
            panel.add(tabbedPane, BorderLayout.CENTER)
        } else {
            val scrollPane = JBScrollPane(codeArea)
            scrollPane.preferredSize = Dimension(800, 600)

            panel.add(infoPanel, BorderLayout.NORTH)
            panel.add(scrollPane, BorderLayout.CENTER)
        }

        return panel
    }

    override fun doOKAction() {
        val inserter = FileInserter(project)

        if (isBDD) {
            // Insert BDD files
            val editedTest = generatedTest.copy(
                code = codeArea.text,
                featureFileContent = featureArea?.text,
                stepDefinitionsContent = codeArea.text
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
            // Regular test insertion
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
