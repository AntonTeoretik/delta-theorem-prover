package app.ui

import core.CorePipeline
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JFrame

class MainWindow : JFrame("Delta Theorem Prover - MVP Skeleton") {
    private val pipeline = CorePipeline()
    private var latestText: String = buildString {

    }
    private var selectedDefinitionName: String? = null
    private var caretOffset: Int = 0
    private val webViewPanel = WebViewPanel(
        initialText = latestText,
        onEditorTextChanged = { source ->
            latestText = source
            caretOffset = caretOffset.coerceIn(0, source.length)
            publishCurrentVisualization()
        },
        onEditorCaretMoved = { offset ->
            caretOffset = offset.coerceIn(0, latestText.length)
            publishCurrentVisualization()
        },
        onDefinitionSelected = { definitionName ->
            selectedDefinitionName = definitionName.ifBlank { null }
            publishCurrentVisualization()
        },
    )

    init {
        defaultCloseOperation = DISPOSE_ON_CLOSE
        minimumSize = Dimension(1024, 700)
        size = Dimension(1280, 800)
        layout = BorderLayout()

        add(webViewPanel, BorderLayout.CENTER)

        addWindowListener(object : WindowAdapter() {
            override fun windowOpened(e: WindowEvent) {
                publishCurrentVisualization()
            }

            override fun windowClosing(e: WindowEvent) {
                webViewPanel.shutdown()
            }
        })
    }

    private fun publishCurrentVisualization() {
        val data = pipeline.buildVisualization(latestText, selectedDefinitionName, caretOffset)
        selectedDefinitionName = data.selectedDefinitionName
        webViewPanel.bridge.publish(data)
    }
}
