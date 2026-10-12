package org.formulagen.wrapper.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject


object EventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AppEvent> = _events

    fun tryEmit(event: AppEvent) { _events.tryEmit(event)}
}

sealed interface AppEvent {
    sealed interface App : AppEvent {
        data object OnSplashAnimationStart: App
        data object OnWebContentReady: App
        data object OnVerifyManagers: App
        data class OnManagerStart(val name: String, val platform: String? = null): App
    }

    // DeepLink
    sealed interface DeepLink: AppEvent {
        data class OnNewIntentEvent(val url: String, val source: String? = null): DeepLink
    }

    // Device
    sealed interface Device: AppEvent {
        data object OnFetchDeviceInfo: Device
        data class OnStoreDeviceInfo(val info: DeviceInfo): Device
    }

    data class DeviceInfo(
        val deviceModel: String? = null,
        val osName: String? = null,
        val osVersion: String? = null,
        val appVersion: String? = null,
        val locale: String? = null,
        val advertisingId: String? = null,
        val deviceId: String? = null,
    )

    // Preference
    sealed interface Preference: AppEvent {
        data class OnSetPushNotificationStateEvent(val isAllowed: Boolean): Preference
        data class OnSetOnboardingStateEvent(val hasEverSeenOnboardingScreen: Boolean): Preference
        data class OnSetPerformanceWarningStateEvent(val enabled: Boolean): Preference
        data class OnSetDeviceIDEvent(val deviceId: String): Preference
        data class OnSetAuthTokenEvent(val loginToken: String): Preference
        data class OnSetUserIdEvent(val userId: String): Preference
        data class OnSetUsernameEvent(val username: String): Preference
        data class OnSetBrazeUserIDEvent(val brazeUserId: String): Preference
        data class OnSetAppEnvStateEvent(val env: String): Preference
        data class OnSetAttributionStateEvent(val attributionsAFJson: String): Preference
        data class OnSetCustomResponsiveWebBaseURLEvent(val customUrl: String): Preference
        data class OnSetNetworkLatencyThresholdEvent(val threshold: Int): Preference
        data class OnSetGamePerformanceFPSThresholdEvent(val threshold: Int): Preference
        data class OnAddGameFpsEntryEvent(val gameName: String, val averageFPS: Double): Preference
        data class OnSetGameTrackCountEvent(val trackCount: Int): Preference
        data object OnClearSessionEvent: Preference
    }

    // Analytics/Marketing: Amplitude
    sealed interface Amplitude: AppEvent {
        @Serializable
        data class OnResponsiveWebReadyEvent(val isReady: Boolean = true): Amplitude

        @Serializable
        data class OnUserReadyEvent(val data: UserReady): Amplitude {
            @Serializable
            data class UserReady(val preferences: Preferences, val user: User) {
                @Serializable
                data class Preferences(val music: Boolean, val soundEffects: Boolean, val vibrations: Boolean)
                @Serializable
                data class User(
                    val userId: String,
                    val userName: String,
                    val legacyUserId: Int? = null,
                    val brazeExternalId: String,
                )
            }
        }

        @Serializable
        data class OnUserLogoutEvent(val username: String?): Amplitude

        @Serializable
        data class OnUpdateCatalogContentEvent(val catalogVersionId: String, val catalogVersion: Int): Amplitude

        @Serializable
        data class OnStartGameEvent(val o: String, val tournamentInstanceEntryId: String): Amplitude

        @Serializable
        data class OnGameEndedEvent(val tournamentId: String): Amplitude

        @Serializable
        data class OnSilentErrorEvent(
            val tag: String?,
            val sentryLevel: String?,
            val errorLevel: String?,
            val message: String?,
            val error: String?,
            val errorCode: Int = 0,
            val stackTrace: String? = null,
        ): Amplitude

        @Serializable
        data class OnAnalyticsQueueEvent(val eventName: String?, val eventObject: String?): Amplitude

        @Serializable
        data class OnLogAmplitudeEvent(val eventName: String, val eventData: Map<String, Any>?): Amplitude
    }

    // Analytics/Attribution: AppsFlyer
    sealed interface AppsFlyer: AppEvent {
        @Serializable
        data class OnInstallEvent(val isInstalled: Boolean = true): AppsFlyer

        @Serializable
        data class OnUserReadyEvent(val data: UserReady): AppsFlyer {
            @Serializable
            data class UserReady(val preferences: Preferences, val user: User) {
                @Serializable
                data class Preferences(val music: Boolean, val soundEffects: Boolean, val vibrations: Boolean)
                @Serializable
                data class User(
                    val userId: String,
                    val userName: String,
                    val legacyUserId: Int? = null,
                    val brazeExternalId: String,
                )
            }
        }

        @Serializable
        data class OnUserLogoutEvent(val username: String?): AppsFlyer

        @Serializable
        data class OnLogAFEvent(val eventName: String, val eventData: Map<String, Any>?): AppsFlyer

        @Serializable
        data class OnGameStartedEvent(
            val tournamentInstanceEntryId: String,
            val realCash: Double,
            val userCashGamesPlayed: Int,
            val isCashTournament: Boolean,
        ): AppsFlyer
    }

    // Marketing/Engagement: Braze
    sealed interface Braze: AppEvent {
        @Serializable
        data class OnUserReadyEvent(val data: UserReady): Braze {
            @Serializable
            data class UserReady(val preferences: Preferences, val user: User) {
                @Serializable
                data class Preferences(val music: Boolean, val soundEffects: Boolean, val vibrations: Boolean)
                @Serializable
                data class User(
                    val userId: String,
                    val userName: String,
                    val legacyUserId: Int? = null,
                    val brazeExternalId: String,
                )
            }
        }

        @Serializable
        data class OnUserLogoutEvent(val username: String?): Braze

        @Serializable
        data class OnPushNotificationStateChangeEvent(val isAllowed: Boolean): Braze
    }

    sealed interface WebContent: AppEvent {
        data object OnAppReady: WebContent
        data class OnManagerStart(val name: String, val platform: String? = null): WebContent

    }

    sealed interface Permission: AppEvent {
        data object OnPermissionRequest: Permission
        data class OnManagerStart(val name: String, val platform: String? = null): Permission
    }

    sealed interface Session: AppEvent {
        data object OnUserLogOut: Session
        data object OnClearSession: Session

        @Serializable
        data class OnSessionReady(val userId: String, val userName: String): Session
        data class OnSetSessionToken(val token: String?): Session
        data class OnSetSessionInfo(val sessionId: Long, val deviceId: String?): Session
        data class OnManagerStart(val name: String, val platform: String? = null): Session
    }

    sealed interface ExternalLink: AppEvent {
        @Serializable
        data class OnOpenExternalLink(val url: String, val target: String?): ExternalLink
        data class OnManagerStart(val name: String, val platform: String? = null): ExternalLink
    }

    sealed interface Analytic: AppEvent {
        @Serializable
        data class OnLogAnalyticEvent(val eventName: String, val eventData: JsonObject?, val type: AnalyticEventType): Analytic
        data class OnMonitorError(
            val tag: String,
            val message: String,
            val level: String?,
            val error: String?,
            val stackTrace: String?,
            val version: String?
        )
        data class OnManagerStart(val name: String, val platform: String? = null): Analytic

    }

    sealed interface Haptic: AppEvent{
        @Serializable
        data class OnUserReadyEvent(val enabled: Boolean?): Haptic
        @Serializable
        data class OnSetHapticFeedbackSettingEvent(val enabled: Boolean?): Haptic
        @Serializable
        data class OnPlayHapticEffectEvent(val effect: HapticFeedbackEffect): Haptic
        @Serializable
        data class OnHapticFeedback(val effect: String): Haptic
        data class OnManagerStart(val name: String, val platform: String? = null): Haptic
        @Serializable
        data class OnCustomHaptic(val segments: List<CustomHapticSegment>): Haptic
    }

    sealed interface Game: AppEvent {
        data class OnLoadGame(val loadGameData: Map<String, Any?>?): Game
        data class OnStartGame(val startGameData: Map<String, Any?>?): Game
        data class OnGameComplete(val completeGameData: Map<String, Any?>?): Game
        data object OnQuitGame
        data object OnPauseGame
        data object OnResumeGame
        data class OnManagerStart(val name: String, val platform: String? = null): Game
    }

    enum class AnalyticEventType {
        GENERAL,
        ATTRIBUTION,
        FUNNEL
    }

    enum class HapticFeedbackEffect(val type: String) {
        LIGHT("light"),
        MEDIUM("medium"),
        HEAVY("heavy"),
        RIGID("rigid"),
        SOFT("soft"),
        SELECTION("selection"),
        SUCCESS("success"),
        WARNING("warning"),
        ERROR("error"),
        FAILURE("failure");

        companion object {
            private val hapticMap = entries.associateBy(HapticFeedbackEffect::type)

            fun fromType(type: String?): HapticFeedbackEffect {
                return hapticMap[type] ?: SOFT
            }
        }
        override fun toString(): String {
            return type
        }
    }

    @Serializable
    data class CustomHapticSegment(
        val durationMs: Long,
        val amplitude: Int // 0..255 where supported; platforms may clamp
    )

}