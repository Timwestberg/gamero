package org.formulagen.wrapper.managers

import org.formulagen.wrapper.postNotification

actual fun platformLoadGame() {
    postNotification(name = "onLoadGame")
}

actual fun platformStartGame() {
    postNotification(name = "onStartGame")
}

actual fun platformCompleteGame() {
    postNotification(name = "onCompleteGame")
}


actual fun platformPauseGame() {
    postNotification(name = "onPauseGame")
}

actual fun platformResumeGame() {
    postNotification(name = "onResumeGame")
}

actual fun platformQuitGame() {
    postNotification(name = "onQuitGame")
}
