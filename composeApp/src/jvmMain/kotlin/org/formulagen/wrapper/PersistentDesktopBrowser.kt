package org.formulagen.wrapper

import com.multiplatform.webview.web.WebContent
import com.multiplatform.webview.web.WebViewFactoryParam
import dev.datlag.kcef.KCEFBrowser
import org.cef.browser.CefRequestContext
import org.cef.handler.CefPermissionHandler

/**
 * Use the app's configured disk profile, not the library's new in-memory request context. [media]
 * answers camera and microphone requests; it joins this WebView's own client before the first page loads.
 */
internal fun persistentDesktopBrowser(param: WebViewFactoryParam, media: CefPermissionHandler): KCEFBrowser {
    val content = param.state.content
    require(content is WebContent.Url) { "The desktop host requires URL content" }
    val context = CefRequestContext.getGlobalContext()
    check(context.isGlobal) { "The configured desktop browser profile is unavailable" }
    param.client.addPermissionHandler(media)
    return param.client.createBrowser(content.url, param.rendering, param.transparent, context).also {
        check(it.requestContext.isGlobal) { "The desktop browser did not use its persistent profile" }
        println("GAMERO_BROWSER_CONTEXT: global=true")
    }
}
