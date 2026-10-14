package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

private const val TAG = "AndroidPlatform"

private fun stub(name: String) = log(LogLevel.Info, TAG, "$name (Android stub)")

actual fun platformMonitorError(event: AppEvent.Analytic.OnMonitorError) = stub("platformMonitorError")

actual fun platformOnNewIntent(event: AppEvent.DeepLink.OnNewIntentEvent) = stub("platformOnNewIntent")

actual fun platformFetchDeviceInfo() = stub("platformFetchDeviceInfo")

actual fun platformStoreDeviceInfo(info: AppEvent.DeviceInfo) = stub("platformStoreDeviceInfo")

actual fun platformPauseGame() = stub("platformPauseGame")

actual fun platformResumeGame() = stub("platformResumeGame")

actual fun platformQuitGame() = stub("platformQuitGame")

actual fun platformRequestPermissions() = stub("platformRequestPermissions")

actual fun platformVerifyPermissions() = stub("platformVerifyPermissions")
