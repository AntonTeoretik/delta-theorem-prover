package app.bridge

import core.model.VisualizationData
import org.cef.browser.CefBrowser

class WebUiBridge(private val browser: CefBrowser) {
    private var isPageReady: Boolean = false
    private var pendingPayload: String? = null
    private var pendingEditorText: String? = null

    fun onPageLoaded(initialText: String) {
        isPageReady = true
        setEditorText(initialText)
        pendingEditorText?.let {
            publishEditorText(it)
            pendingEditorText = null
        }
        pendingPayload?.let {
            publishRaw(it)
            pendingPayload = null
        }
    }

    fun setEditorText(text: String) {
        val payload = "\"${escapeJson(text)}\""
        if (!isPageReady) {
            pendingEditorText = payload
            return
        }
        publishEditorText(payload)
    }

    fun publish(data: VisualizationData) {
        val payload = data.toJson()
        if (!isPageReady) {
            pendingPayload = payload
            return
        }
        publishRaw(payload)
    }

    private fun publishRaw(payload: String) {
        val js = "window.renderFromHost && window.renderFromHost($payload);"
        browser.executeJavaScript(js, browser.url ?: "about:blank", 0)
    }

    private fun publishEditorText(payload: String) {
        val js = "window.setEditorTextFromHost && window.setEditorTextFromHost($payload);"
        browser.executeJavaScript(js, browser.url ?: "about:blank", 0)
    }

    private fun VisualizationData.toJson(): String {
        val linesJson = lines.joinToString(separator = ",") { line ->
            """{"lineNumber":${line.lineNumber},"length":${line.length},"preview":"${escapeJson(line.preview)}"}"""
        }

        return """
            {
              "sourceText":"${escapeJson(sourceText)}",
              "lineCount":$lineCount,
              "nonEmptyLineCount":$nonEmptyLineCount,
              "totalCharacters":$totalCharacters,
              "lines":[${linesJson}]
            }
        """.trimIndent()
    }

    private fun escapeJson(input: String): String {
        val sb = StringBuilder(input.length + 16)
        input.forEach { ch ->
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 32) {
                        sb.append("\\u")
                        sb.append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }
}
