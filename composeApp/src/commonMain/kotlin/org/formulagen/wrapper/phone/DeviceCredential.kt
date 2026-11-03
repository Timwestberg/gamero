package org.formulagen.wrapper.phone

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.util.takeWhole

/**
 * This phone's approval device: its id, the account it answers for, its name and platform, and its device token.
 * The platform keeps it whole in one secret item ([DeviceVault]); the token never leaves native code but in the
 * `x-hovel-device-token` header, and the server keeps only its SHA-256.
 */
@Serializable
internal data class DeviceCredential(val id: String, val ownerId: String, val name: String, val platform: String, val token: String) {
    /** True when every part has the form the server checks, so a damaged item reads as no device. */
    val valid: Boolean
        get() = SharedRules.isRecordId(id) && SharedRules.isRecordId(ownerId) && TOKEN.matches(token) &&
            name.length in 1..DEVICE_NAME_LIMIT && SharedRules.isDialogName(name) && platform in APPROVAL_DEVICE_PLATFORMS

    companion object {
        private val TOKEN = Regex("[0-9a-f]{64}")
        private val json = Json { ignoreUnknownKeys = false }

        /** The stored item [text] as a credential, or null when it is missing or damaged. */
        fun read(text: String?): DeviceCredential? =
            text?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() }?.takeIf { it.valid }

        fun write(credential: DeviceCredential): String = json.encodeToString(serializer(), credential)
    }
}

/** `COMPANION_LIMITS.deviceName`. */
internal const val DEVICE_NAME_LIMIT = 80

/** `APPROVAL_DEVICE_PLATFORMS`. */
internal val APPROVAL_DEVICE_PLATFORMS = setOf("ios", "android")

/**
 * Where the platform keeps the credential: one Keychain item on iOS, one Keystore-encrypted preference on Android.
 * Calls block on the platform store, so callers run them off the main thread.
 */
internal interface DeviceVault {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** The platform's cryptography: secure random bytes and SHA-256 (SecRandomCopyBytes and CommonCrypto, or Java's). */
internal interface DeviceCrypto {
    fun randomBytes(count: Int): ByteArray
    fun sha256(bytes: ByteArray): ByteArray
}

/** A new device token: 32 random bytes in lower-case hex, the form the server checks. */
internal fun DeviceCrypto.newToken(): String = randomBytes(TOKEN_BYTES).toHex()

/** The SHA-256 of [token] in lower-case hex: all the server keeps of it. */
internal fun DeviceCrypto.tokenHash(token: String): String = sha256(token.encodeToByteArray()).toHex()

/** A new random (version 4) UUID in lower case, for a device id. */
internal fun DeviceCrypto.newId(): String {
    val bytes = randomBytes(UUID_BYTES)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.toHex()
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

/**
 * The name a device registers under: the platform's own name for it as event-contracts `dialogName` takes it (one
 * line, no control, format or separator characters, trimmed), cut to the limit without splitting a character, or
 * [fallback] when nothing is left.
 */
internal fun deviceName(raw: String?, fallback: String): String {
    return SharedRules.dialogName(SharedRules.dialogName(raw.orEmpty(), fallback).takeWhole(DEVICE_NAME_LIMIT), fallback)
}

private const val TOKEN_BYTES = 32
private const val UUID_BYTES = 16

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
