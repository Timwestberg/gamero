package org.formulagen.wrapper.phone

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.conversations.ApprovalOption
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataUsingEncoding
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyle
import platform.UIKit.UIAlertActionStyleCancel
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertActionStyleDestructive
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The iPhone app as an approval device for the web app at [appUrl]: Keychain, CommonCrypto, NSURLSession, UIKit alerts. */
internal fun iosPhoneApprovals(appUrl: String): PhoneApprovals = PhoneApprovals(
    platform = "ios",
    name = deviceName(UIDevice.currentDevice.model, "iPhone"),
    api = DeviceApprovalApi(IosDeviceHttp(), appUrl),
    vault = IosDeviceVault(),
    crypto = IosDeviceCrypto,
    dialogs = IosApprovalDialogs(),
    clock = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
)

/**
 * The credential as one generic-password Keychain item, readable after the first unlock on this device only, so it
 * never moves to another device in a backup.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosDeviceVault(
    private val service: String = "org.formulagen.gamero.approval-device",
    private val account: String = "device",
) : DeviceVault {
    override fun read(): String? = memScoped {
        val found = alloc<CFTypeRefVar>()
        val status = query(kSecReturnData to kCFBooleanTrue, kSecMatchLimit to kSecMatchLimitOne) { SecItemCopyMatching(it, found.ptr) }
        if (status == errSecItemNotFound) return null
        check(status == errSecSuccess) { "Keychain read failed ($status)" }
        val data = CFBridgingRelease(found.value) as? NSData ?: return null
        NSString.create(data = data, encoding = NSUTF8StringEncoding)?.toString()
    }

    override fun write(value: String) {
        clear()
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
        val status = query(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly) { SecItemAdd(it, null) }
        check(status == errSecSuccess) { "Keychain write failed ($status)" }
    }

    override fun clear() {
        val status = query { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "Keychain delete failed ($status)" }
    }

    /** Runs [block] with this item's query plus [extra], releasing every Core Foundation value it made. */
    private inline fun <T> query(vararg extra: Pair<CFStringRef?, Any?>, block: (CFDictionaryRef?) -> T): T {
        val made = mutableListOf<CFTypeRef?>()
        val pairs = listOf<Pair<CFStringRef?, Any?>>(kSecClass to kSecClassGenericPassword, kSecAttrService to service, kSecAttrAccount to account) + extra
        val dictionary = CFDictionaryCreateMutable(null, pairs.size.convert(), kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        try {
            for ((key, value) in pairs) {
                val cf: CFTypeRef? = when (value) {
                    is CPointer<*> -> value.reinterpret()
                    else -> CFBridgingRetain(value).also { made += it }
                }
                CFDictionaryAddValue(dictionary, key, cf)
            }
            return block(dictionary)
        } finally {
            made.forEach { CFRelease(it) }
            CFRelease(dictionary)
        }
    }
}

/** Secure random bytes from `SecRandomCopyBytes` and SHA-256 from CommonCrypto. */
@OptIn(ExperimentalForeignApi::class)
internal object IosDeviceCrypto : DeviceCrypto {
    override fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        val status = bytes.usePinned { SecRandomCopyBytes(kSecRandomDefault, count.convert(), it.addressOf(0)) }
        check(status == errSecSuccess) { "No secure random bytes ($status)" }
        return bytes
    }

    override fun sha256(bytes: ByteArray): ByteArray {
        val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
        val input = if (bytes.isEmpty()) ByteArray(1) else bytes
        input.usePinned { data -> digest.usePinned { out -> CC_SHA256(data.addressOf(0), bytes.size.convert(), out.addressOf(0)) } }
        return digest.toByteArray()
    }
}

/**
 * Device calls over an ephemeral NSURLSession: no cookies, no cache, and no redirects, so the device token goes only
 * to the configured server. Cancelling the call cancels the request.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosDeviceHttp(private val timeoutSeconds: Double = 15.0) : DeviceHttp {
    private val session = NSURLSession.sessionWithConfiguration(
        NSURLSessionConfiguration.ephemeralSessionConfiguration, delegate = NoRedirects(), delegateQueue = null,
    )

    override suspend fun send(request: DeviceRequest): DeviceResponse = suspendCancellableCoroutine { continuation ->
        val url = NSURL.URLWithString(request.url)
        if (url == null) {
            continuation.resumeWithException(IllegalArgumentException("Not a URL"))
            return@suspendCancellableCoroutine
        }
        val call = NSMutableURLRequest.requestWithURL(url).apply {
            setHTTPMethod(request.method)
            setTimeoutInterval(timeoutSeconds)
            request.headers.forEach { (name, value) -> setValue(value, forHTTPHeaderField = name) }
            request.body?.let { setHTTPBody(NSString.create(string = it).dataUsingEncoding(NSUTF8StringEncoding)) }
        }
        val task = session.dataTaskWithRequest(call) { data, response, error ->
            when {
                error != null -> continuation.resumeWithException(IllegalStateException(error.localizedDescription))
                else -> {
                    val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
                    val body = data?.let { NSString.create(data = it, encoding = NSUTF8StringEncoding)?.toString() } ?: ""
                    continuation.resume(DeviceResponse(status, body))
                }
            }
        }
        continuation.invokeOnCancellation { task.cancel() }
        task.resume()
    }

    /** Refuses every redirect: the 3xx itself is the answer, and the token is never re-sent elsewhere. */
    private class NoRedirects : NSObject(), NSURLSessionTaskDelegateProtocol {
        @ObjCSignatureOverride
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            willPerformHTTPRedirection: NSHTTPURLResponse,
            newRequest: NSURLRequest,
            completionHandler: (NSURLRequest?) -> Unit,
        ) = completionHandler(null)
    }
}

/** UIKit alerts on the main thread, shown over whatever the app shows. Cancelling a call dismisses its alert. */
internal class IosApprovalDialogs : ApprovalDialogs {
    private class Button<T>(val label: String, val style: UIAlertActionStyle, val value: T)

    override suspend fun confirm(prompt: DevicePrompt): Boolean =
        alert(prompt.title, prompt.message, listOf(Button(prompt.accept, UIAlertActionStyleDefault, true)), Button(prompt.decline, UIAlertActionStyleCancel, false))

    override suspend fun ask(prompt: ApprovalPrompt): ApprovalOption? = alert(
        prompt.title,
        prompt.message,
        prompt.choices.map { Button<ApprovalOption?>(it.label, if (it.destructive) UIAlertActionStyleDestructive else UIAlertActionStyleDefault, it.option) },
        Button(prompt.dismiss, UIAlertActionStyleCancel, null),
    )

    override suspend fun tell(notice: ApprovalNotice) =
        alert(notice.title, notice.message, emptyList(), Button(notice.button, UIAlertActionStyleCancel, Unit))

    private suspend fun <T> alert(title: String, message: String, buttons: List<Button<T>>, cancel: Button<T>): T = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val alert = UIAlertController.alertControllerWithTitle(title, message, UIAlertControllerStyleAlert)
            for (button in buttons + cancel) {
                alert.addAction(UIAlertAction.actionWithTitle(button.label, button.style) { _ -> if (continuation.isActive) continuation.resume(button.value) })
            }
            val presenter = topViewController()
            if (presenter == null) {
                continuation.resume(cancel.value)
                return@suspendCancellableCoroutine
            }
            presenter.presentViewController(alert, animated = true, completion = null)
            continuation.invokeOnCancellation {
                dispatch_async(dispatch_get_main_queue()) { alert.presentingViewController?.dismissViewControllerAnimated(true, completion = null) }
            }
        }
    }

    /** The view controller on top of the key window, which presents the alert. */
    private fun topViewController(): UIViewController? {
        val windows = UIApplication.sharedApplication.connectedScenes.mapNotNull { it as? UIWindowScene }
            .flatMap { scene -> scene.windows.mapNotNull { it as? UIWindow } }
        var top = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }
}
