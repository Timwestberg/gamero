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


