package org.formulagen.wrapper.chat

import java.util.concurrent.ThreadFactory
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

/**
 * Daemon threads for chat and voice workers. A failure that escapes a task is logged here instead
 * of reaching the app's default handler, which treats any uncaught failure as fatal and exits.
 */
internal fun loggingDaemonThreads(name: String): ThreadFactory = ThreadFactory { task ->
    Thread(task, name).apply {
        isDaemon = true
        setUncaughtExceptionHandler { _, error -> log(LogLevel.Error, name, "Worker task failed", error) }
    }
}
