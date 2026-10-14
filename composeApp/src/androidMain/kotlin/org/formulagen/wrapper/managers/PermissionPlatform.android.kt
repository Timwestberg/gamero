package org.formulagen.wrapper.managers

import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformRequestPermissions() {
    log(LogLevel.Info, PermissionManager.TAG, "Request permissions")
}

actual fun platformVerifyPermissions() {
    log(LogLevel.Info, PermissionManager.TAG, "Verify permissions")
}
