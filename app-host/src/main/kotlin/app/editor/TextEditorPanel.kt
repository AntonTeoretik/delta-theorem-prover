package app.editor

import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.JTextArea
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class TextEditorPanel : JPanel(BorderLayout()) {
    private val textArea = JTextArea()

    init {
        textArea.isEditable = true
        textArea.lineWrap = false
        textArea.caretColor = java.awt.Color(20, 20, 20)
        textArea.caret.blinkRate = 500
        add(JScrollPane(textArea), BorderLayout.CENTER)
    }

    fun onTextChanged(listener: (String) -> Unit) {
        textArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = listener(textArea.text)
            override fun removeUpdate(e: DocumentEvent) = listener(textArea.text)
            override fun changedUpdate(e: DocumentEvent) = listener(textArea.text)
        })
    }

    fun onUserKeyInput(listener: () -> Unit) {
        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                listener()
            }
        })
    }

    fun setText(text: String) {
        textArea.text = text
    }

    fun onEditorFocusRequested(listener: () -> Unit) {
        textArea.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                listener()
            }
        })
    }

    fun focusEditor() {
        SwingUtilities.invokeLater {
            if (!textArea.requestFocusInWindow()) {
                textArea.requestFocus()
            }
        }
    }

    fun hasEditorFocus(): Boolean = textArea.isFocusOwner
}
