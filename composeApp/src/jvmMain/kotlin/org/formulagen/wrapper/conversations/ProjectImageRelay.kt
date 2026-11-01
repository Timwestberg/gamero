package org.formulagen.wrapper.conversations

import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.formulagen.wrapper.bridge.FolderRole
import org.formulagen.wrapper.util.loggingDaemonThreads

/** Bounded, memory-only file previews. They never enter ConversationStore, checkpoint delivery or retry journals. */
internal class ProjectImageRelay(
    private val api: ConversationApi,
    private val folders: ProjectFolderStore,
    private val ownerId: String,
    private val hostId: String,
) : AutoCloseable {
    private val active = ConcurrentHashMap.newKeySet<String>()
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(2),
        loggingDaemonThreads("hovel-project-image"))

    /** Queue without blocking the host stream; overflow expires at the requester rather than growing a queue. */
    fun request(requestId: String) {
        if (!active.add(requestId)) return
        runCatching {
            executor.execute {
                try {
                    runCatching { send(requestId) }.onFailure {
                        if (!Thread.currentThread().isInterrupted) runCatching {
                            api.imageChunk(requestId, buildJsonObject { put("kind", "error") })
                        }
                    }
                } finally { active.remove(requestId) }
            }
        }.onFailure { active.remove(requestId) }
    }

    /** Fetch fresh ownership, read one original image, then deliver bounded chunks while the request lives. */
    internal fun send(requestId: String) {
        val work = api.imageWork(requestId)
        val deadline = Instant.parse(work.expiresAt)
        check(Instant.now().isBefore(deadline)) { "Image request expired" }
        check(api.project(work.projectId).computerId == hostId) { "Project moved" }
        val root = folders.folders(ownerId, work.projectId).singleOrNull { it.role == FolderRole.WORKING }?.path
            ?: error("Working folder unavailable")
        val image = readProjectImage(root, work.path)
        for (start in image.base64.indices step CHUNK_CHARACTERS) {
            check(!Thread.currentThread().isInterrupted && Instant.now().isBefore(deadline)) { "Image request expired" }
            val end = minOf(start + CHUNK_CHARACTERS, image.base64.length)
            api.imageChunk(requestId, buildJsonObject {
                put("kind", "chunk")
                put("offset", start / 4 * 3)
                put("base64", image.base64.substring(start, end))
                put("mediaType", image.mediaType)
                put("done", end == image.base64.length)
            })
        }
    }

    override fun close() { executor.shutdownNow(); active.clear() }

    companion object {
        /** Matches ARTIFACT_IMAGE_LIMITS.chunk; divisible by four for base64 boundaries. */
        const val CHUNK_CHARACTERS = 131072
    }
}
