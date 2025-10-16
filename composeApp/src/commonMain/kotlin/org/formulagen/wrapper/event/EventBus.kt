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
        data class OnHapticFeedback(val effect: String): Haptic
        data class OnManagerStart(val name: String, val platform: String? = null): Haptic
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

}