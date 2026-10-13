package org.formulagen.wrapper.logger

actual fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
    // Callers must never pass credentials or provider payloads to this logger.
    System.err.println("[$level][$tag] $message")
    throwable?.let { System.err.println(it.javaClass.simpleName) }
}
