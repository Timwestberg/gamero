package org.formulagen.wrapper.logger

enum class LogLevel { Debug, Info, Warn, Error }

expect fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)