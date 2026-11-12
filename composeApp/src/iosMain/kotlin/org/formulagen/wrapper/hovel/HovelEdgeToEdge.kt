package org.formulagen.wrapper.hovel

import platform.UIKit.UIScrollViewContentInsetAdjustmentBehavior
import platform.WebKit.WKContentWorld
import platform.WebKit.WKUserScript
import platform.WebKit.WKUserScriptInjectionTime
import platform.WebKit.WKWebView

/**
 * Keeps every viewport tag at `viewport-fit=cover`. The web view library adds its own tag on each navigation, after
 * Hovel's and without it, and the last tag wins, so Hovel's pages would read every safe-area inset as 0.
 */
internal const val VIEWPORT_FIT_COVER_SCRIPT = """(() => {
  const cover = (meta) => {
    if (meta.name === 'viewport' && !/viewport-fit/.test(meta.content)) meta.content += ', viewport-fit=cover';
  };
  new MutationObserver((changes) => {
    for (const change of changes) {
      for (const node of change.addedNodes) if (node instanceof HTMLMetaElement) cover(node);
    }
  }).observe(document, { childList: true, subtree: true });
})();"""

/**
 * Lets Hovel's pages draw edge to edge, under the status bar and the Dynamic Island and over the home indicator, as
 * Messages does. The web view stops insetting its content by the safe area, which left blank bands above and below
 * the page, and the pages pad themselves by `env(safe-area-inset-*)` instead. Call it once per web view, before its
 * first page loads; calling it again adds nothing.
 */
fun hovelEdgeToEdge(webView: WKWebView) {
    webView.scrollView.contentInsetAdjustmentBehavior =
        UIScrollViewContentInsetAdjustmentBehavior.UIScrollViewContentInsetAdjustmentNever
    val controller = webView.configuration.userContentController
    if (controller.userScripts.none { (it as? WKUserScript)?.source == VIEWPORT_FIT_COVER_SCRIPT }) {
        controller.addUserScript(
            WKUserScript(
                source = VIEWPORT_FIT_COVER_SCRIPT,
                injectionTime = WKUserScriptInjectionTime.WKUserScriptInjectionTimeAtDocumentStart,
                forMainFrameOnly = true,
                inContentWorld = WKContentWorld.pageWorld,
            ),
        )
    }
}
