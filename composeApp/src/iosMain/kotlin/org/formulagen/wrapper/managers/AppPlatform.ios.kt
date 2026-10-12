package org.formulagen.wrapper.managers

import org.formulagen.wrapper.postNotification

actual fun platformRemoveAppSplashScreen() {
    postNotification(name = "onRemoveAppSplashScreen")
}

actual fun platformVerifyBridges() {
    postNotification(name = "onVerifyManagers")
}


