package org.formulagen.wrapper.chat

import java.security.MessageDigest
import kotlinx.serialization.json.JsonPrimitive
import org.formulagen.wrapper.bridge.SharedRules

/**
 * The one companion persona every runtime uses: Codex as `baseInstructions`, Claude Code as its
 * system prompt. [hash] changes whenever the text does, which matters because a Claude session
 * keeps the first system prompt it was given.
 */
internal class Persona private constructor(val instructions: String) {
    /** SHA-256 of [instructions], lower-case hex. */
    val hash: String = MessageDigest.getInstance("SHA-256").digest(instructions.toByteArray())
        .joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?) = other is Persona && other.instructions == instructions
    override fun hashCode() = instructions.hashCode()
    override fun toString() = "Persona($hash)"

    companion object {
        /**
         * The persona for a companion named [name]. The name is untrusted page text, so it appears
         * only as a JSON string literal; tool access stays controlled by each runtime's configuration.
         */
        fun companion(name: String): Persona {
            require(SharedRules.isCompanionName(name)) { "Invalid companion name" }
            val quoted = JsonPrimitive(name).toString()
            return Persona("You are a friendly AI companion in Hovel. Your display name is $quoted. " +
                "Chat warmly and clearly. Be honest that you are an AI. Keep most replies to a few sentences, suitable for spoken playback. " +
                "This is conversation mode: answer using only this conversation. Do not use tools, read files, run commands, browse, or change anything. " +
                "If asked to perform computer work, explain that workspace tasks need the separate approved task flow. " +
                "Never claim to have performed actions or accessed information you have not. " +
                "Never mention the person's email, account, computer or environment, and stay in character as $quoted.")
        }
    }
}
