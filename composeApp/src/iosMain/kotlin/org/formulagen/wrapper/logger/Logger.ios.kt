package org.formulagen.wrapper.logger

import platform.Foundation.NSLog

actual fun log(
    level: LogLevel,
    tag: String,
    message: String,
    throwable: Throwable?
) {
    val prefix = when (level){
        LogLevel.Debug -> "Debug"
        LogLevel.Info -> "Info"
        LogLevel.Warn -> "Warn"
        LogLevel.Error -> "Err"
    }
    val formatted = "[$prefix][$tag] $message" + (throwable?.let { "\n$it" } ?: "")
    NSLog(formatted)
}