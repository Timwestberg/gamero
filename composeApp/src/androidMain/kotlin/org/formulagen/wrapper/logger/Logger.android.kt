package org.formulagen.wrapper.logger

import android.util.Log

actual fun log(
    level: LogLevel,
    tag: String,
    message: String,
    throwable: Throwable?
) {
    when(level){
        LogLevel.Debug -> if (throwable != null ) Log.d(tag, message, throwable) else Log.d(tag, message)
        LogLevel.Info -> if (throwable != null ) Log.i(tag, message, throwable) else Log.i(tag, message)
        LogLevel.Warn -> if (throwable != null ) Log.w(tag, message, throwable) else Log.w(tag, message)
        LogLevel.Error -> if (throwable != null ) Log.e(tag, message, throwable) else Log.e(tag, message)
    }
}