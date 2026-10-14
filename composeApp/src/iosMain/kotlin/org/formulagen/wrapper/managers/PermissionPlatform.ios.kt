package org.formulagen.wrapper.managers

import org.formulagen.wrapper.postNotification

actual fun platformRequestPermissions() {
    postNotification(name = "onRequestPermissions")
}

actual fun platformVerifyPermissions() {
    postNotification(name = "onVerifyPermissions")
}
