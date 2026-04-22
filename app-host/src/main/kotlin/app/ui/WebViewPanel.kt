package app.ui

import app.bridge.WebUiBridge
import app.bridge.WebUiPathResolver
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.browser.CefMessageRouter
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter

class WebViewPanel(
    private val initialText: String,
    private val onEditorTextChanged: (String) -> Unit,
    private val onEditorCaretMoved: (Int) -> Unit,
    private val onDefinitionSelected: (String) -> Unit,
) : JPanel(BorderLayout()) {
    private val cefApp: CefApp
    private val client: CefClient
    private val browser: CefBrowser
    private val messageRouter: CefMessageRouter
    val bridge: WebUiBridge

    init {
        val builder = CefAppBuilder()
        builder.setInstallDir(File("jcef-bundle"))
        builder.cefSettings.windowless_rendering_enabled = false
        builder.addJcefArgs("--disable-gpu", "--disable-gpu-compositing")

        cefApp = builder.build()
        client = cefApp.createClient()

        messageRouter = CefMessageRouter.create()
        messageRouter.addHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(
                browser: CefBrowser?,
                frame: CefFrame?,
                queryId: Long,
                request: String?,
                persistent: Boolean,
                callback: CefQueryCallback?,
            ): Boolean {
                val payload = request ?: ""
                if (payload.startsWith("editorTextChanged:")) {
                    onEditorTextChanged(payload.removePrefix("editorTextChanged:"))
                    callback?.success("ok")
                    return true
                }

                if (payload.startsWith("editorCaretMoved:")) {
                    val rawOffset = payload.removePrefix("editorCaretMoved:")
                    val caretOffset = rawOffset.toIntOrNull() ?: 0
                    onEditorCaretMoved(caretOffset)
                    callback?.success("ok")
                    return true
                }

                if (payload.startsWith("selectDefinition:")) {
                    onDefinitionSelected(payload.removePrefix("selectDefinition:"))
                    callback?.success("ok")
                    return true
                }
                callback?.failure(400, "Unknown bridge message")
                return true
            }
        }, false)
        client.addMessageRouter(messageRouter)

        val indexUrl = WebUiPathResolver.resolveDistPath().resolve("index.html").toUri().toString()
        browser = client.createBrowser(indexUrl, false, false)
        bridge = WebUiBridge(browser)

        client.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame != null && frame.isMain) {
                    bridge.onPageLoaded(initialText)
                }
            }
        })

        add(browser.uiComponent, BorderLayout.CENTER)
    }

    fun shutdown() {
        client.removeMessageRouter(messageRouter)
        client.dispose()
        cefApp.dispose()
    }
}
