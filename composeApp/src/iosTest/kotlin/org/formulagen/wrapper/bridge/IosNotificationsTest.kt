package org.formulagen.wrapper.bridge

import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import kotlin.test.Test
import kotlin.test.assertEquals

class IosNotificationsTest {
    @Test
    fun aGrantThatDeliversCountsAsGranted() {
        for (status in listOf(UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral)) {
            assertEquals(NotificationPermission.GRANTED, notificationPermissionOf(status), status.toString())
        }
    }

    @Test
    fun notAskedYetIsUndeterminedAndARefusalIsDenied() {
        assertEquals(NotificationPermission.UNDETERMINED, notificationPermissionOf(UNAuthorizationStatusNotDetermined))
        assertEquals(NotificationPermission.DENIED, notificationPermissionOf(UNAuthorizationStatusDenied))
    }

    @Test
    fun aStatusThisBuildDoesNotKnowIsNotReliedOn() {
        assertEquals(NotificationPermission.DENIED, notificationPermissionOf(null))
        assertEquals(NotificationPermission.DENIED, notificationPermissionOf(99))
    }
}
