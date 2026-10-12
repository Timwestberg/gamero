package org.formulagen.wrapper

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNotificationName

internal fun postNotification(
    name: String,
    userInfo: Map<String, Any?>? = null,
) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        NSNotificationName(name),
        null,
        userInfo
    )
}


