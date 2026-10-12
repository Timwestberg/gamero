package org.formulagen.wrapper.preferences

import com.russhwolf.settings.Settings
import com.russhwolf.settings.get
import com.russhwolf.settings.set
import org.formulagen.wrapper.event.AppEvent

interface PreferenceStore {
    fun setPushNotificationAllowed(allowed: Boolean)
    fun getPushNotificationAllowed(): Boolean?

    fun setHasSeenOnboarding(seen: Boolean)
    fun getHasSeenOnboarding(): Boolean?

    fun setPerformanceWarningEnabled(enabled: Boolean)
    fun getPerformanceWarningEnabled(): Boolean?

    fun setDeviceId(deviceId: String)
    fun getDeviceId(): String?

    fun setAuthToken(token: String)
    fun getAuthToken(): String?

    fun setUserId(userId: String)
    fun getUserId(): String?

    fun setUsername(username: String)
    fun getUsername(): String?

    fun setBrazeUserId(brazeUserId: String)
    fun getBrazeUserId(): String?

    fun setAppEnv(env: String)
    fun getAppEnv(): String?

    fun setAttributionsAFJson(json: String)
    fun getAttributionsAFJson(): String?

    fun setCustomResponsiveBaseUrl(url: String)
    fun getCustomResponsiveBaseUrl(): String?

    fun setNetworkLatencyThreshold(threshold: Int)
    fun getNetworkLatencyThreshold(): Int?

    fun setGamePerformanceFpsThreshold(threshold: Int)
    fun getGamePerformanceFpsThreshold(): Int?

    fun addGameFpsEntry(gameName: String, averageFps: Double)
    fun getLastGameFpsEntry(): Pair<String, Double>?

    fun setFpsTrackCount(count: Int)
    fun getFpsTrackCount(): Int?

    // Orientation (stored as string: "PORTRAIT" | "LANDSCAPE" | "AUTO")
    fun setOrientationMode(mode: String)
    fun getOrientationMode(): String?

    fun clear()
}

class SettingsPreferenceStore(
    private val settings: Settings = Settings()
) : PreferenceStore {
    private object Keys {
        const val PUSH_ALLOWED = "push_allowed"
        const val SEEN_ONBOARDING = "seen_onboarding"
        const val PERF_WARNING = "perf_warning"
        const val DEVICE_ID = "device_id"
        const val AUTH_TOKEN = "auth_token"
        const val USER_ID = "user_id"
        const val USERNAME = "username"
        const val BRAZE_USER_ID = "braze_user_id"
        const val APP_ENV = "app_env"
        const val ATTR_AF_JSON = "attr_af_json"
        const val RESP_BASE_URL = "resp_base_url"
        const val LATENCY_THRESHOLD = "latency_threshold"
        const val GAME_FPS_THRESHOLD = "game_fps_threshold"
        const val LAST_GAME_FPS_NAME = "last_game_fps_name"
        const val LAST_GAME_FPS_VALUE = "last_game_fps_value"
        const val FPS_TRACK_COUNT = "fps_track_count"
        const val ORIENTATION_MODE = "orientation_mode"
    }

    override fun setPushNotificationAllowed(allowed: Boolean) { settings[Keys.PUSH_ALLOWED] = allowed }
    override fun getPushNotificationAllowed(): Boolean? = settings.getBooleanOrNull(Keys.PUSH_ALLOWED)

    override fun setHasSeenOnboarding(seen: Boolean) { settings[Keys.SEEN_ONBOARDING] = seen }
    override fun getHasSeenOnboarding(): Boolean? = settings.getBooleanOrNull(Keys.SEEN_ONBOARDING)

    override fun setPerformanceWarningEnabled(enabled: Boolean) { settings[Keys.PERF_WARNING] = enabled }
    override fun getPerformanceWarningEnabled(): Boolean? = settings.getBooleanOrNull(Keys.PERF_WARNING)

    override fun setDeviceId(deviceId: String) { settings[Keys.DEVICE_ID] = deviceId }
    override fun getDeviceId(): String? = settings.getStringOrNull(Keys.DEVICE_ID)

    override fun setAuthToken(token: String) { settings[Keys.AUTH_TOKEN] = token }
    override fun getAuthToken(): String? = settings.getStringOrNull(Keys.AUTH_TOKEN)

    override fun setUserId(userId: String) { settings[Keys.USER_ID] = userId }
    override fun getUserId(): String? = settings.getStringOrNull(Keys.USER_ID)

    override fun setUsername(username: String) { settings[Keys.USERNAME] = username }
    override fun getUsername(): String? = settings.getStringOrNull(Keys.USERNAME)

    override fun setBrazeUserId(brazeUserId: String) { settings[Keys.BRAZE_USER_ID] = brazeUserId }
    override fun getBrazeUserId(): String? = settings.getStringOrNull(Keys.BRAZE_USER_ID)

    override fun setAppEnv(env: String) { settings[Keys.APP_ENV] = env }
    override fun getAppEnv(): String? = settings.getStringOrNull(Keys.APP_ENV)

    override fun setAttributionsAFJson(json: String) { settings[Keys.ATTR_AF_JSON] = json }
    override fun getAttributionsAFJson(): String? = settings.getStringOrNull(Keys.ATTR_AF_JSON)

    override fun setCustomResponsiveBaseUrl(url: String) { settings[Keys.RESP_BASE_URL] = url }
    override fun getCustomResponsiveBaseUrl(): String? = settings.getStringOrNull(Keys.RESP_BASE_URL)

    override fun setNetworkLatencyThreshold(threshold: Int) { settings[Keys.LATENCY_THRESHOLD] = threshold }
    override fun getNetworkLatencyThreshold(): Int? = settings.getIntOrNull(Keys.LATENCY_THRESHOLD)

    override fun setGamePerformanceFpsThreshold(threshold: Int) { settings[Keys.GAME_FPS_THRESHOLD] = threshold }
    override fun getGamePerformanceFpsThreshold(): Int? = settings.getIntOrNull(Keys.GAME_FPS_THRESHOLD)

    override fun addGameFpsEntry(gameName: String, averageFps: Double) {
        settings[Keys.LAST_GAME_FPS_NAME] = gameName
        settings[Keys.LAST_GAME_FPS_VALUE] = averageFps.toRawBits()
    }
    override fun getLastGameFpsEntry(): Pair<String, Double>? {
        val name = settings.getStringOrNull(Keys.LAST_GAME_FPS_NAME) ?: return null
        val bits = settings.getLongOrNull(Keys.LAST_GAME_FPS_VALUE) ?: return null
        return name to Double.fromBits(bits)
    }

    override fun setFpsTrackCount(count: Int) { settings[Keys.FPS_TRACK_COUNT] = count }
    override fun getFpsTrackCount(): Int? = settings.getIntOrNull(Keys.FPS_TRACK_COUNT)

    override fun setOrientationMode(mode: String) { settings[Keys.ORIENTATION_MODE] = mode }
    override fun getOrientationMode(): String? = settings.getStringOrNull(Keys.ORIENTATION_MODE)

    override fun clear() { settings.clear() }
}

private fun Settings.getBooleanOrNull(key: String): Boolean? = if (hasKey(key)) this[key] else null
private fun Settings.getStringOrNull(key: String): String? = if (hasKey(key)) this[key] else null
private fun Settings.getIntOrNull(key: String): Int? = if (hasKey(key)) this[key] else null
private fun Settings.getLongOrNull(key: String): Long? = if (hasKey(key)) this[key] else null


