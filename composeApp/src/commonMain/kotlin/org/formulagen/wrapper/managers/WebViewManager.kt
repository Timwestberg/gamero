package org.formulagen.wrapper.managers

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.multiplatform.webview.jsbridge.WebViewJsBridge
import com.multiplatform.webview.web.NativeWebView
import com.multiplatform.webview.web.WebViewFactoryParam
import com.multiplatform.webview.web.defaultWebViewFactory
import com.multiplatform.webview.web.WebView
import com.multiplatform.webview.web.rememberWebViewNavigator
import com.multiplatform.webview.web.rememberWebViewState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.managers.AppManager.Companion.TAG
import org.formulagen.wrapper.webview.JSBridgeDispatcher
import org.formulagen.wrapper.webview.JSBridgeListener
import org.formulagen.wrapper.webview.JsCaller
import org.formulagen.wrapper.webview.ProvideWebContent
import org.formulagen.wrapper.webview.WebContent
import org.formulagen.wrapper.webview.installJSBridgerHandlers


@Composable
fun WebViewManager(
    url: String,
    modifier: Modifier = Modifier,
    jsBridgeName: String = "FormulaGen",
    hideStatusBar: Boolean = false,
    enableLegacyBridge: Boolean = true,
    onWebViewCreated: (NativeWebView) -> Unit = {},
    webViewFactory: (WebViewFactoryParam) -> NativeWebView = ::defaultWebViewFactory,
) {
    val tag = "WebViewManager"
    val webViewNavigator = rememberWebViewNavigator()
    val jsBridge = remember { WebViewJsBridge(navigator = webViewNavigator, jsBridgeName = jsBridgeName) }
    val webContent = remember(jsBridge, webViewNavigator) { WebContent(JsCaller(jsBridge, webViewNavigator)) }
    val webViewState = rememberWebViewState(url)
    var webViewRef: NativeWebView? by remember { mutableStateOf<NativeWebView?>(null) }



    webViewState.webSettings.apply {
        isJavaScriptEnabled = true
        supportZoom = false
        androidWebSettings.apply {
            domStorageEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            isAlgorithmicDarkeningAllowed = true
            safeBrowsingEnabled = true
        }
        iOSWebSettings.apply {
            mediaPlaybackRequiresUserGesture = false
            bounces = false
            showVerticalScrollIndicator = false
            showHorizontalScrollIndicator = false
        }
    }

    ProvideWebContent(webContent) {
        WebView(
            state = webViewState,
            navigator = webViewNavigator,
            captureBackPresses = false,
            modifier = modifier.fillMaxSize(),
            webViewJsBridge = if (enableLegacyBridge) jsBridge else null,
            onCreated = { ref -> webViewRef = ref; onWebViewCreated(ref) },
            factory = webViewFactory
        )
    }
    
    LaunchedEffect(Unit) {
        EventBus.events
            .filterIsInstance<AppEvent.WebContent>()
            .collect { evt ->
                when (evt) {
                    AppEvent.WebContent.OnAppReady -> webContent.send("onAppReady")
                    is AppEvent.WebContent.OnManagerStart ->
                        log(LogLevel.Info, tag, "Manager: ${evt.name} has been verified on ${evt.platform}")
                }
            }
    }


    LaunchedEffect(jsBridge){
        installJSBridgerHandlers(
            jsBridge = jsBridge,
            listener = object: JSBridgeListener {
                override fun onDeviceEvent(
                    eventName: String,
                    parameters: String?,
                    dispatcher: JSBridgeDispatcher
                ) {
                    log(LogLevel.Info,tag, "[JSBridgeListener]: eventType: $eventName")

                    dispatcher.register(
                        eventName = "onSplashAnimationStart",
                        requestSerializer = Unit.serializer()
                    ){
                        EventBus.tryEmit(AppEvent.App.OnSplashAnimationStart)
                    }

                    dispatcher.register(
                        eventName = "onWebContentReady",
                        requestSerializer = Unit.serializer()
                    ){
                        EventBus.tryEmit(AppEvent.App.OnWebContentReady)
                    }

                    dispatcher.register(
                        eventName = "onSessionReady",
                        requestSerializer = AppEvent.Session.OnSessionReady.serializer()
                    ){ session ->
                        if (session != null) {
                            EventBus.tryEmit(AppEvent.Session.OnSessionReady(
                                userId = session.userId,
                                userName = session.userName
                            ))
                        }
                    }

                    dispatcher.register(
                        eventName = "onUserLogOut",
                        requestSerializer = Unit.serializer()
                    ){
                            EventBus.tryEmit(AppEvent.Session.OnUserLogOut)
                            EventBus.tryEmit(AppEvent.Session.OnClearSession)
                    }

                    dispatcher.register(
                        eventName = "onOpenExternalLink",
                        requestSerializer = AppEvent.ExternalLink.OnOpenExternalLink.serializer()
                    ){ req ->
                        if (req != null) {
                                EventBus.tryEmit(AppEvent.ExternalLink.OnOpenExternalLink(req.url, req.target))
                        }
                    }

                    dispatcher.register(
                        eventName = "onHapticFeedback",
                        requestSerializer = String.serializer()
                    ){ effect ->
                        if (effect != null) {
                            EventBus.tryEmit(
                                AppEvent.Haptic.OnHapticFeedback(effect)
                            )
                        }
                    }

                    dispatcher.register(
                        eventName = "onLogAnalyticEvent",
                        requestSerializer = AppEvent.Analytic.OnLogAnalyticEvent.serializer()
                    ){ event ->
                        if (event != null) {
                            EventBus.tryEmit(
                                AppEvent.Analytic.OnLogAnalyticEvent(eventName = event.eventName, eventData = event.eventData, type = event.type)
                            )
                        }
                    }
                }
            }
        )
    }
}

