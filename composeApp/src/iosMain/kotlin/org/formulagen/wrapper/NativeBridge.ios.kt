package org.formulagen.wrapper

import platform.Foundation.NSNotificationCenter

internal fun postNotification(
    name: String,
    userInfo: Map<String, Any?>? = null,
) {
    NSNotificationCenter.defaultCenter.postNotificationName(
        name,
        null,
        userInfo?.toMap<Any?, Any?>()
    )
}
