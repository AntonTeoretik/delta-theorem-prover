package app.bridge

import core.model.Diagnostic
import core.model.InfixDeclaration
import core.model.TermEdge
import core.model.TextHighlight
import core.model.TermNode
import core.model.TypeHint
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
        val diagnosticsJson = diagnostics.joinToString(",") { it.toJson() }
        val textHighlightsJson = textHighlights.joinToString(",") { it.toJson() }
        val typeHintsJson = typeHints.joinToString(",") { it.toJson() }
        val symbolReplacementsJson = symbolReplacements.entries.joinToString(",") { (from, to) ->
            "\"${escapeJson(from)}\":\"${escapeJson(to)}\""
        }
        val infixDeclarationsJson = infixDeclarations.joinToString(",") { it.toJson() }
        val definitionsJson = definitionNames.joinToString(",") { "\"${escapeJson(it)}\"" }
        val selectedDefinitionJson = selectedDefinitionName?.let { "\"${escapeJson(it)}\"" } ?: "null"
        val freeVarsJson = freeVariableNames.joinToString(",") { "\"${escapeJson(it)}\"" }
        val nodesJson = nodes.joinToString(",") { it.toJson() }
        val nodeTypeHintsJson = nodeTypeHints.entries.joinToString(",") { (nodeId, typeText) ->
            "\"${escapeJson(nodeId)}\":\"${escapeJson(typeText)}\""
        }
        val blueEdgesJson = blueEdges.joinToString(",") { it.toJson() }
        val greenEdgesJson = greenEdges.joinToString(",") { it.toJson() }

        return """
            {
              "sourceText":"${escapeJson(sourceText)}",
              "diagnostics":[$diagnosticsJson],
              "textHighlights":[$textHighlightsJson],
              "typeHints":[$typeHintsJson],
              "symbolReplacements":{$symbolReplacementsJson},
              "infixDeclarations":[$infixDeclarationsJson],
              "definitionNames":[$definitionsJson],
              "selectedDefinitionName":$selectedDefinitionJson,
              "freeVariableNames":[$freeVarsJson],
              "nodes":[$nodesJson],
              "nodeTypeHints":{$nodeTypeHintsJson},
              "blueEdges":[$blueEdgesJson],
              "greenEdges":[$greenEdgesJson]
            }
        """.trimIndent()
    }

    private fun Diagnostic.toJson(): String {
        return """{"message":"${escapeJson(message)}","line":$line,"column":$column}"""
    }

    private fun TextHighlight.toJson(): String {
        return """{"startOffset":${span.startOffset},"endOffset":${span.endOffset},"kind":"${kind.name}"}"""
    }

    private fun TypeHint.toJson(): String {
        return """{"id":"${escapeJson(id)}","startOffset":${span.startOffset},"endOffset":${span.endOffset},"type":"${escapeJson(type)}"}"""
    }

    private fun InfixDeclaration.toJson(): String {
        return """{"name":"${escapeJson(name)}","precedence":$precedence,"associativity":"${associativity.name}","nameSpan":{"startOffset":${nameSpan.startOffset},"endOffset":${nameSpan.endOffset}}}"""
    }

    private fun TermNode.toJson(): String {
        return """
            {
              "id":"${escapeJson(id)}",
              "type":"${type.name}",
              "label":"${escapeJson(label)}",
              "x":$x,
              "y":$y,
              "width":$width,
              "height":$height,
              "blueInputCount":$blueInputCount,
              "blueOutputCount":$blueOutputCount,
              "greenInputCount":$greenInputCount,
              "greenOutputCount":$greenOutputCount
            }
        """.trimIndent()
    }

    private fun TermEdge.toJson(): String {
        return """
            {
              "id":"${escapeJson(id)}",
              "fromNodeId":"${escapeJson(fromNodeId)}",
              "toNodeId":"${escapeJson(toNodeId)}",
              "fromPort":$fromPort,
              "toPort":$toPort
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
