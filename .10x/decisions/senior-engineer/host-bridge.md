# host-bridge: implementation approach

- **A2 router.** Start from `NativeBridge.onQuery`. Lift the checks into `BridgeRouter.handle(raw: String, frame: FrameFacts, reply: (String) -> Unit): Cancel`. Keep `pending` as a map of call id to `Job` under a `SupervisorJob`. Inject the dispatcher, so tests use `StandardTestDispatcher`.
- **Typed handlers.** `method("link.open", LinkOpenArgs.serializer(), Unit.serializer()) { args -> links.open(args.url) }`. The registry stores a `(JsonObject) -> JsonElement` adapter, so the router never sees capability types.
- **A3 JCEF.** `CefMessageRouterHandlerAdapter` keeps its shape and calls the router. `FrameFacts(isMain = frame.isMain, origin = frame.url, documentId = frame.identifier)`. JCEF uses `callback.success` with the v2 reply JSON; v1 keeps the old `failure(code, msg)` path.
- **B1 Android.** Get `android.webkit.WebView` from `onWebViewCreated`. Call `WebViewCompat.addWebMessageListener(view, "gameroHost", setOf(trustedOrigin), listener)`. Reply with `replyProxy.postMessage(json)`. Detect navigation with `WebViewClient.onPageStarted` on the main frame. Feature-check `WebViewFeature.WEB_MESSAGE_LISTENER`.
- **B2 iOS.** Get `WKWebView` from `onWebViewCreated`. Add a `WKScriptMessageHandlerWithReplyProtocol` in `.page` content world. Use `message.frameInfo.isMainFrame` and `securityOrigin`. Reply through `replyHandler(json, nil)`. Navigation from `WKNavigationDelegate` `didCommit`.
- **Tricky parts.** The library owns the WebView client and delegate on Android and iOS. Wrap them and forward, do not replace. Kotlin/Native needs a `NSObject` subclass for the reply handler.
- **Remove later.** `JsCaller`, `installJSBridgerHandlers`, `enableLegacyBridge` after D1.
