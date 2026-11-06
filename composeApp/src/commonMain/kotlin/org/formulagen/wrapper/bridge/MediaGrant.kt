package org.formulagen.wrapper.bridge

/** A capture device that a page asks for with `getUserMedia`. */
enum class MediaKind { Microphone, Camera }

/**
 * Grants camera and microphone only to the main frame of the trusted Hovel page.
 * Each platform asks this before it grants. See "Camera and microphone" in docs/host-bridge.md.
 */
object MediaGrant {
    /** What [decide] answers for one request. */
    sealed interface Decision {
        /** Grant exactly [kinds]; the platform denies anything else in the request. */
        data class Grant(val kinds: Set<MediaKind>) : Decision

        /** Deny the whole request. */
        data class Deny(val reason: Reason) : Decision
    }

    /** Why a request is denied: the first rule of [decide] that fails. */
    enum class Reason { OtherOrigin, ChildFrame, OtherResource, NoKinds }

    /**
     * Grants [kinds] only when [requestOrigin] has the trusted [origin], the request comes from the
     * main frame, and it asks for nothing but cameras and microphones. [otherResources] is true when
     * the platform's request also names another resource, such as screen capture or protected media.
     */
    fun decide(
        origin: TrustedOrigin,
        requestOrigin: String,
        mainFrame: Boolean,
        kinds: Set<MediaKind>,
        otherResources: Boolean = false,
    ): Decision = when {
        !origin.matches(requestOrigin) -> Decision.Deny(Reason.OtherOrigin)
        !mainFrame -> Decision.Deny(Reason.ChildFrame)
        otherResources -> Decision.Deny(Reason.OtherResource)
        kinds.isEmpty() -> Decision.Deny(Reason.NoKinds)
        else -> Decision.Grant(kinds)
    }
}
