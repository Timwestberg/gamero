package org.formulagen.wrapper.managers

import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformRemoveAppSplashScreen() {
    log(LogLevel.Info, AppManager.TAG, "Remove splash requested (Android stub)")
}

actual fun platformVerifyBridges() {
    log(LogLevel.Info, AppManager.TAG, "Verify bridges requested (Android stub)")
}


