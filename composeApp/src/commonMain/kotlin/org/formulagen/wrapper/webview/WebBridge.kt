package org.formulagen.wrapper.webview

import com.multiplatform.webview.jsbridge.IJsMessageHandler
import com.multiplatform.webview.jsbridge.JsMessage
import com.multiplatform.webview.jsbridge.WebViewJsBridge
import com.multiplatform.webview.web.WebViewNavigator
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive


class JsCaller(
    private val jsBridge: WebViewJsBridge,
    private val navigator: WebViewNavigator?,
) {
    fun call(methodName: String, parameters: String = "{}"){
        val jsCallerScriptInjection = """
            ( function() {
                try {
                    if (window.${jsBridge.jsBridgeName} && window.${jsBridge.jsBridgeName}.callJs){
                        window.${jsBridge.jsBridgeName}.callJs("$methodName", $parameters);
                        } else if (typeof window.invokeJavaScriptFunction === 'function'){
                          window.invokeJavaScriptFunction("$methodName",$parameters);
                        }
                     } catch (e) {
                       console.error(e)
                     }
                     })();
            """.trimIndent()
        navigator?.evaluateJavaScript(jsCallerScriptInjection)
    }
}

interface JSBridgeListener {
    fun onDeviceEvent(eventName: String, parameters: String?, dispatcher: JSBridgeDispatcher)
}

fun installJSBridgerHandlers(
    jsBridge: WebViewJsBridge,
    listener: JSBridgeListener
){
    val dispatcher = JSBridgeDispatcher()

    jsBridge.register(object: IJsMessageHandler {
        override fun methodName(): String = "deviceEvent"
        override fun handle(
            message: JsMessage,
            navigator: WebViewNavigator?,
            callback: (String) -> Unit
        ){
            /**
             * Accepts Multiple Payloads
             * "onEvent"
             * ["onEvent", {...}] (array)
             * {type: "onEvent", data {...} } (object)
             * */
            val rawParams = message.params
            val element = safeParse(rawParams)
            val (type, data) = when (element){
                is JsonArray -> {
                    val t = element.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: ""
                    val d = element.getOrNull(1)?.toString()
                    t to d
                }
                is JsonObject -> {
                    val t = (element["type"] ?: element["eventType"] ?: element["method"])?.jsonPrimitive?.contentOrNull ?: ""
                    val d = (element["data"] ?: element["params"])?.toString()
                    t to d
                }
                is JsonPrimitive -> {
                    (element.contentOrNull ?: "") to null
                }
                else -> {
                    val t = rawParams.trim().removeSurrounding("\"","\"")
                    t to null
                }
            }
            listener.onDeviceEvent(type, data, dispatcher)
        }
    })


}

private fun safeParse(json: String): JsonElement? = try { Json.parseToJsonElement(json)} catch (_: Throwable) {null}

class JSBridgeDispatcher(
    private val json: Json = Json {ignoreUnknownKeys = true}
){
    private val handlers = mutableMapOf<String, (String?) -> String?>()

    fun<Req> register(
        eventName: String,
        requestSerializer: KSerializer<Req>,
        handler: (Req?) -> Unit,
    ){
        handlers[eventName] = { params ->
            val req = parseOrNull(params){
                    nonNull -> json.decodeFromString(requestSerializer, nonNull)
            }
            handler(req)
            null
        }
    }

    fun<Req, Res> register(
        eventName: String,
        requestSerializer: KSerializer<Req>,
        responseSerializer: KSerializer<Res>,
        handler: (Req?) -> Res?
    ){
        handlers[eventName] = { params ->
            val req= parseOrNull(params){ nonNull ->
                json.decodeFromString(requestSerializer, nonNull)
            }
            val res = handler(req)
            res?.let { json.encodeToString(responseSerializer, it) }
        }
    }

    fun dispatch(eventName: String, parameters: String?): String? {
        val handler = handlers[eventName] ?: return null
        return handler(parameters)
    }
}

private inline fun <T> parseOrNull(payload: String?, decode: (String) -> T): T? {
    if (payload == null) return null
    val trimmed = payload.trim()
    if (trimmed.isEmpty() || trimmed == "null" || trimmed == "undefined") return null
    return try {
        decode(trimmed)
    }catch (_: Throwable){
        null
    }
}