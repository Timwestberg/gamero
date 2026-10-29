package org.formulagen.wrapper.companion

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Stop a child and everything it started: ask nicely (destroy), wait up to two seconds, then
 * force-kill what is left. Retain observed handles so parent exit cannot hide a still-running child.
 * This blocks for up to the grace period; UI callers use [terminateProcessTreeInBackground].
 */
internal fun terminateProcessTree(process: Process, observed: Set<ProcessHandle> = emptySet()) =
    reapProcessTree(process, signalProcessTree(process, observed))

/** How long [terminateProcessTree] waits for a polite exit before force-killing what is left. */
internal val PROCESS_TREE_GRACE: Duration = 2.seconds

/**
 * The same stop for a caller on a UI thread: the polite signal goes out before this returns, and
 * the wait and force-kill run on a daemon thread named [name].
 */
internal fun terminateProcessTreeInBackground(process: Process, name: String) {
    val tree = signalProcessTree(process)
    Thread.ofPlatform().daemon().name(name).start { reapProcessTree(process, tree) }
}

/** Ask the process and every current descendant to exit; return the handles to watch. */
private fun signalProcessTree(process: Process, observed: Set<ProcessHandle> = emptySet()): Set<ProcessHandle> {
    val descendants = observed.toMutableSet()
    process.descendants().use { handles -> handles.forEach { descendants.add(it) } }
    descendants.forEach { if (it.isAlive) it.destroy() }
    if (process.isAlive) process.destroy()
    return descendants
}

/** Wait for the tree to exit within the grace period, then force-kill anything still running. */
private fun reapProcessTree(process: Process, descendants: Set<ProcessHandle>) {
    val deadline = System.nanoTime() + PROCESS_TREE_GRACE.inWholeNanoseconds
    try {
        while ((process.isAlive || descendants.any { it.isAlive }) && System.nanoTime() < deadline) Thread.sleep(20)
    } finally {
        descendants.forEach { if (it.isAlive) it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
    }
}
