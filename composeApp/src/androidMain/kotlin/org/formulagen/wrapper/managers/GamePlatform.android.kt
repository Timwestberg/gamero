package org.formulagen.wrapper.managers

import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformLoadGame() {
    log(LogLevel.Info, GameManager.TAG, "Load game (Android stub)")
}

actual fun platformStartGame() {
    log(LogLevel.Info, GameManager.TAG, "Start game (Android stub)")
}

actual fun platformCompleteGame() {
    log(LogLevel.Info, GameManager.TAG, "Complete game (Android stub)")
}


