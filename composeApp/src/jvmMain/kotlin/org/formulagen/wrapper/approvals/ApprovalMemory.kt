package org.formulagen.wrapper.approvals

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.storage.readBounded
import org.formulagen.wrapper.storage.writeDurably

/** What an approval was given for. Any field that differs is another approval, and asks again. */
@Serializable
internal data class Approved(
    val conversationId: String,
    val projectId: String,
    val folder: String,
    val runtimeId: RuntimeId,
    val modelId: String,
    val purpose: ApprovedFor,
) {
    /**
     * A name for this approval that says nothing of the folder, so a page can ask for it to be withdrawn: the
     * SHA-256 of its fields. The same approval always has the same name.
     */
    val id: String get() = MessageDigest.getInstance("SHA-256")
        .digest(listOf(conversationId, projectId, folder, runtimeId.name, modelId, purpose.name).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }
}

@Serializable
internal enum class ApprovedFor {
    /** A specialist works in the folder. */
    @SerialName("work") WORK,
    /** The coordinator is read files from the folder. */
    @SerialName("read") READ,
}

@Serializable
private data class ApprovalFile(val version: Int = 1, val approved: List<Approved> = emptyList())

private const val MAX_BYTES = 2_000_000L
private const val MAX_KEPT = 2_000
private val json = Json { encodeDefaults = true }

/**
 * The approvals an account gave on this Mac, at `<root>/<accountId>/approvals.json`, so a restart does not ask again.
 * It holds only a yes: a no is never kept. A file that cannot be read is left as it is and counts as no approvals,
 * so the person is asked again and nothing is assumed.
 */
internal class ApprovalMemory(root: Path, accountId: String) {
    private val file: Path

    init {
        require(SharedRules.isRecordId(accountId)) { "Approvals are keyed by an account id" }
        file = root.resolve(accountId).resolve("approvals.json")
    }

    @Synchronized fun has(approved: Approved): Boolean = approved in all()

    @Synchronized fun remember(approved: Approved) {
        val current = read() ?: return
        if (approved in current) return
        write((current + approved).takeLast(MAX_KEPT))
    }

    /** Takes [approved] back: the next time, the person is asked. */
    @Synchronized fun withdraw(approved: Approved) {
        val current = read() ?: return
        if (approved in current) write(current - approved)
    }

    /** Every approval kept, oldest first. */
    @Synchronized fun all(): List<Approved> = read().orEmpty()

    /** The approvals kept, or null when the file is there and cannot be trusted. */
    private fun read(): List<Approved>? {
        if (!Files.exists(file)) return emptyList()
        return runCatching {
            json.decodeFromString(ApprovalFile.serializer(), readBounded(file, MAX_BYTES).decodeToString())
                .also { require(it.version == 1) { "Unknown approvals version" } }.approved
        }.onFailure { log(LogLevel.Warn, "approvals", "The kept approvals cannot be read, so each is asked again", it) }.getOrNull()
    }

    private fun write(approved: List<Approved>) =
        writeDurably(file, json.encodeToString(ApprovalFile.serializer(), ApprovalFile(approved = approved)).toByteArray())
}
