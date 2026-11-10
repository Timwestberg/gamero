@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.setActive

private const val TAG = "IosAudioSession"

/**
 * The app's own claim on the shared audio session, for dictation and the companion's voice. One use at a time.
 * The app deactivates the session only when it activated it, and tells other apps so their music resumes. While
 * the web view runs a meeting ([meeting]) it claims nothing: the page's call owns the microphone and the speaker,
 * and changing the category under it would cut the call's audio.
 *
 * Main thread only.
 */
internal class IosAudioSession {
    enum class Use { CAPTURE, SPEECH }

    /** True while the web view captures the camera or microphone. The app sets it once the web view exists. */
    var meeting: () -> Boolean = { false }

    var use: Use? = null
        private set

    /** Claims the session for [next]. False when a meeting or a capture holds it, or iOS refuses the category. */
    fun begin(next: Use): Boolean {
        if (use == next) return true
        if (meeting() || (use == Use.CAPTURE && next == Use.SPEECH)) return false
        val session = AVAudioSession.sharedInstance()
        val configured = when (next) {
            Use.CAPTURE -> session.setCategory(AVAudioSessionCategoryRecord, mode = AVAudioSessionModeMeasurement, options = AVAudioSessionCategoryOptionDuckOthers, error = null)
            Use.SPEECH -> session.setCategory(AVAudioSessionCategoryPlayback, mode = AVAudioSessionModeSpokenAudio, options = AVAudioSessionCategoryOptionDuckOthers, error = null)
        }
        if (!configured || !session.setActive(true, error = null)) {
            log(LogLevel.Warn, TAG, "The audio session refused $next")
            return false
        }
        use = next
        return true
    }

    /**
     * Gives the session back if [which] holds it. False when iOS would not deactivate it yet, which it does while
     * audio is still draining; the caller tries again shortly.
     */
    fun end(which: Use): Boolean {
        if (use != which) return true
        if (!AVAudioSession.sharedInstance().setActive(false, withOptions = AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, error = null)) return false
        use = null
        return true
    }

    /** iOS took the session away (a call, Siri, another app). There is nothing left to deactivate. */
    fun lost() {
        use = null
    }
}
