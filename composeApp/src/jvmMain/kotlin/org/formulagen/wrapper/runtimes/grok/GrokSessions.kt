package org.formulagen.wrapper.runtimes.grok

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.providers.grok.validGrokSessionId

internal enum class GrokMode { TALK, WORK }

/** Exact ownership required to resume, including the verified account's fingerprint. */
@Serializable
internal data class GrokSessionScope(
    val accountId: String,
    val companionId: String,
    val mode: GrokMode,
    val resolvedModel: String,
    val accountFingerprint: String,
    val workspaceFingerprint: String? = null,
) {
    /** Validate Hovel ownership before a provider identifier can be stored or looked up. */
    fun validate() {
        require(SharedRules.isRecordId(accountId) && canonicalUuid(companionId))
        require(SharedRules.isModelId(resolvedModel) && resolvedModel != SharedRules.RUNTIME_DEFAULT_MODEL)
        require(FINGERPRINT.matches(accountFingerprint))
        require(if (mode == GrokMode.WORK) workspaceFingerprint?.let(FINGERPRINT::matches) == true else workspaceFingerprint == null)
    }
}

/** A private mapping: only [handle] may be returned to Hovel's shared history. */
@Serializable
internal data class GrokSessionBinding(val version: Int = 1, val handle: String, val providerSessionId: String, val scope: GrokSessionScope)

/** Missing permits pre-submission context recovery; a mismatch must never silently resume another account. */
internal sealed interface GrokSessionLookup {
    data class Found(val binding: GrokSessionBinding) : GrokSessionLookup
    data object Missing : GrokSessionLookup
    data object Mismatch : GrokSessionLookup
    data object ModelChanged : GrokSessionLookup
    data object Unavailable : GrokSessionLookup
}

/** UUID-keyed private sidecars, with an in-memory copy of each binding this process created or found. */
internal class GrokSessions(private val root: Path) {
    private val local = LinkedHashMap<String, GrokSessionBinding>()

    init { require(root.isAbsolute) }

    /** Commit the mapping before publishing its Hovel handle. Raw ACP IDs never become filenames. */
    @Synchronized fun create(providerSessionId: String, scope: GrokSessionScope): GrokSessionBinding {
        scope.validate()
        require(validGrokSessionId(providerSessionId))
        check(local.size < 256) { "Grok session binding limit reached" }
        val binding = GrokSessionBinding(handle = UUID.randomUUID().toString(), providerSessionId = providerSessionId, scope = scope)
        Files.createDirectories(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"))
        val target = root.resolve("${binding.handle}.json")
        check(!Files.exists(target, NOFOLLOW_LINKS))
        val temp = Files.createTempFile(root, ".grok-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        try {
            Files.writeString(temp, bindingJson.encodeToString(binding))
            Files.move(temp, target, ATOMIC_MOVE)
        } finally { Files.deleteIfExists(temp) }
        local[binding.handle] = binding
        return binding
    }

    /** Exact handle and scope only; corrupt, future-version and foreign bindings are preserved. */
    @Synchronized fun find(handle: String, scope: GrokSessionScope): GrokSessionLookup {
        scope.validate()
        if (!canonicalUuid(handle)) return GrokSessionLookup.Unavailable
        val binding = local[handle] ?: run {
            val file = root.resolve("$handle.json")
            if (!Files.exists(file, NOFOLLOW_LINKS)) return GrokSessionLookup.Missing
            readBinding(handle) ?: return GrokSessionLookup.Unavailable
        }
        return when {
            binding.scope == scope -> GrokSessionLookup.Found(binding)
            binding.scope.copy(resolvedModel = scope.resolvedModel) == scope -> GrokSessionLookup.ModelChanged
            else -> GrokSessionLookup.Mismatch
        }
    }

    /** Drop one binding whose provider session can no longer be restored, so its handle cannot repeat the failure. */
    @Synchronized fun forget(handle: String) {
        require(canonicalUuid(handle))
        local.remove(handle)
        val file = root.resolve("$handle.json")
        if (Files.isDirectory(root, NOFOLLOW_LINKS) && Files.isRegularFile(file, NOFOLLOW_LINKS)) Files.deleteIfExists(file)
    }

    /** Drop the in-memory copies on teardown without deleting persisted bindings. */
    @Synchronized fun clearLocal() { local.clear() }

    /**
     * Remove [accountId]'s orphan bindings, keeping every handle in [retainedHandles]: the ones its conversations hold
     * and the active ones. A failed or partial read of those must never call this method. Unknown or corrupt files are preserved.
     * More than [limit] entries deletes nothing; cleanup runs off the UI thread.
     */
    @Synchronized fun prune(accountId: String, retainedHandles: Set<String>, limit: Int = 256) {
        require(SharedRules.isRecordId(accountId) && retainedHandles.all(::canonicalUuid))
        require(limit in 1..4_096)
        val files = ArrayList<Path>()
        if (Files.exists(root, NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(root, NOFOLLOW_LINKS)) return
            Files.newDirectoryStream(root).use { entries ->
                for (file in entries) {
                    if (files.size == limit) return
                    files.add(file)
                }
            }
        }
        for (file in files) {
            val name = file.fileName.toString()
            if (!name.endsWith(".json")) continue
            val handle = name.removeSuffix(".json")
            if (!canonicalUuid(handle) || handle in retainedHandles) continue
            val binding = readBinding(handle) ?: continue
            if (binding.scope.accountId == accountId && Files.deleteIfExists(file)) local.remove(handle)
        }
        local.entries.removeIf { (handle, binding) -> binding.scope.accountId == accountId && handle !in retainedHandles }
    }

    /** Read only a supported private sidecar; validation is shared by resume and orphan cleanup. */
    private fun readBinding(handle: String): GrokSessionBinding? {
        val file = root.resolve("$handle.json")
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) || !Files.isRegularFile(file, NOFOLLOW_LINKS)) return null
        return try {
            if (Files.size(file) > 16_384) return null
            val value = bindingJson.parseToJsonElement(Files.readString(file)) as? JsonObject ?: return null
            if (value["version"] != JsonPrimitive(1)) return null
            bindingJson.decodeFromJsonElement<GrokSessionBinding>(value).also {
                it.scope.validate()
                require(it.handle == handle && validGrokSessionId(it.providerSessionId))
            }
        } catch (_: Exception) { null }
    }
}

/** Hovel handles are canonical UUIDs; accepting path-like identifiers here would escape private storage. */
private fun canonicalUuid(value: String): Boolean = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
private val bindingJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }
