package org.formulagen.wrapper.phone

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.formulagen.wrapper.conversations.ApprovalOption
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/** The Android app as an approval device for the web app at [appUrl]: Keystore, Java crypto, HttpURLConnection, AlertDialog. */
internal fun androidPhoneApprovals(activity: Activity, appUrl: String): PhoneApprovals = PhoneApprovals(
    platform = "android",
    name = deviceName(Build.MODEL, "Android phone"),
    api = DeviceApprovalApi(AndroidDeviceHttp(), appUrl),
    vault = AndroidDeviceVault(activity.applicationContext),
    crypto = AndroidDeviceCrypto,
    dialogs = AndroidApprovalDialogs(activity),
    clock = System::currentTimeMillis,
)

/**
 * The credential as one private preference, sealed with AES-GCM under a key the Android Keystore holds and never
 * exports. A value the key cannot open (the key went with a reinstall) reads as no device.
 */
internal class AndroidDeviceVault(context: Context) : DeviceVault {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun read(): String? {
        val stored = preferences.getString(ITEM, null) ?: return null
        return runCatching {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
            String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        check(preferences.edit().putString(ITEM, Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()) { "The approval device was not saved" }
    }

    override fun clear() {
        preferences.edit().remove(ITEM).commit()
    }

    /** The Keystore key that seals the credential, made on first use. */
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFERENCES = "hovel_approval_device"
        const val ITEM = "device"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "org.formulagen.gamero.approval-device"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val KEY_BITS = 256
    }
}

/** Secure random bytes and SHA-256 from the platform's Java crypto. */
internal object AndroidDeviceCrypto : DeviceCrypto {
    private val random = SecureRandom()

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * Device calls over HttpURLConnection on the IO dispatcher, without caches or redirects, so the device token goes
 * only to the configured server. Cancelling the call interrupts it.
 */
internal class AndroidDeviceHttp(private val timeoutMillis: Int = 15_000) : DeviceHttp {
    override suspend fun send(request: DeviceRequest): DeviceResponse = withContext(Dispatchers.IO) {
        runInterruptible {
            val connection = URL(request.url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = timeoutMillis
                connection.readTimeout = timeoutMillis
                connection.requestMethod = request.method
                request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                request.body?.let { body ->
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val stream = if (status >= HttpURLConnection.HTTP_BAD_REQUEST) connection.errorStream else connection.inputStream
                DeviceResponse(status, stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty())
            } finally {
                connection.disconnect()
            }
        }
    }
}

/**
 * AlertDialogs on the main thread over [activity]: the message, then one button per answer (a stage approval has
 * four), then the dismiss button. Cancelling a call dismisses its dialog.
 */
internal class AndroidApprovalDialogs(private val activity: Activity) : ApprovalDialogs {
    override suspend fun confirm(prompt: DevicePrompt): Boolean =
        show(prompt.title, prompt.message, listOf(prompt.accept to true), prompt.decline to false)

    override suspend fun ask(prompt: ApprovalPrompt): ApprovalOption? =
        show(prompt.title, prompt.message, prompt.choices.map { it.label to it.option }, prompt.dismiss to null)

    override suspend fun tell(notice: ApprovalNotice) = show(notice.title, notice.message, emptyList(), notice.button to Unit)

    private suspend fun <T> show(title: String, message: String, answers: List<Pair<String, T>>, dismiss: Pair<String, T>): T =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                if (activity.isFinishing || activity.isDestroyed) {
                    continuation.resume(dismiss.second)
                    return@suspendCancellableCoroutine
                }
                val finish = { value: T -> if (continuation.isActive) continuation.resume(value) }
                val padding = (PADDING_DP * activity.resources.displayMetrics.density).toInt()
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(padding, padding / 2, padding, 0)
                    addView(TextView(activity).apply { text = message; setTextIsSelectable(true) })
                }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setView(ScrollView(activity).apply { addView(content) })
                    .setNegativeButton(dismiss.first) { _, _ -> finish(dismiss.second) }
                    .setOnCancelListener { finish(dismiss.second) }
                    .create()
                for ((label, value) in answers) {
                    content.addView(Button(activity).apply {
                        text = label
                        setOnClickListener {
                            finish(value)
                            dialog.dismiss()
                        }
                    })
                }
                dialog.setOnDismissListener { finish(dismiss.second) }
                dialog.show()
                continuation.invokeOnCancellation { activity.runOnUiThread { dialog.dismiss() } }
            }
        }

    private companion object {
        const val PADDING_DP = 24
    }
}
