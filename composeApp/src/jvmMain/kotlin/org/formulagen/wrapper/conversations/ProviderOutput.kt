package org.formulagen.wrapper.conversations

import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.providers.findClaudeExecutable
import org.formulagen.wrapper.providers.claudeWorkEnvironment

/** Immutable origin of one output; never serialized into shared history. */
@Serializable
internal data class OutputOrigin(val projectId: String, val threadId: String, val runtimeId: RuntimeId, val sessionId: String)

/** Resolve only recorded provenance, refusing active sessions and old outputs with no origin. */
internal fun openProviderOutput(record: ConversationRecord, args: ProjectOpenOutputArgs,
    launch: (OutputOrigin) -> Boolean = ::launchProviderOutput): ProjectOpenOutputResult {
    val origin = record.outputOrigins[args.artifactId]
        ?.takeIf { it.projectId == args.projectId } ?: return ProjectOpenOutputResult("unavailable")
    val thread = record.threads[origin.threadId] ?: return ProjectOpenOutputResult("unavailable")
    if (thread.outputs.none { it.id == args.artifactId }) return ProjectOpenOutputResult("unavailable")
    if (thread.state in setOf(BrainAttemptState.RUNNING, BrainAttemptState.QUEUED, BrainAttemptState.AWAITING_APPROVAL))
        return ProjectOpenOutputResult("busy")
    return ProjectOpenOutputResult(if (launch(origin)) "opened" else "unavailable")
}

/** Launch fixed provider commands with a validated saved session ID, without a shell or prompt. */
private fun launchProviderOutput(origin: OutputOrigin): Boolean {
    if (!System.getProperty("os.name").startsWith("Mac") || !SharedRules.isRecordId(origin.sessionId)) return false
    val env = System.getenv()
    val command = when (origin.runtimeId) {
        RuntimeId.CODEX -> listOf("/usr/bin/open", "codex://threads/${origin.sessionId}")
        RuntimeId.CLAUDE_CODE -> {
            val executable = findClaudeExecutable(env) ?: return false
            listOf(executable.toString(), "--desktop", "--resume", origin.sessionId)
        }
        else -> return false
    }
    val builder = ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
    if (origin.runtimeId == RuntimeId.CLAUDE_CODE) {
        val clean = claudeWorkEnvironment(env, null, java.nio.file.Path.of(command.first()))
        builder.environment().clear()
        builder.environment().putAll(clean)
    }
    val process = builder.start()
    try {
        if (!process.waitFor(10, TimeUnit.SECONDS)) return false
        return process.exitValue() == 0
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
