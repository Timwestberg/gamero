package org.formulagen.wrapper.bridge

import android.Manifest
import android.webkit.PermissionRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidMediaGrantTest {
    private val trusted = "https://hovel.example.test"
    private val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
    private val video = PermissionRequest.RESOURCE_VIDEO_CAPTURE

    /** A request that records its one answer: the granted resources, or "denied". */
    private class FakeRequest(override val origin: String, override val resources: List<String>) : MediaPermissionRequest {
        var answer: Any? = null
        override fun grant(resources: List<String>) { check(answer == null); answer = resources.toSet() }
        override fun deny() { check(answer == null); answer = "denied" }
    }

    private val held = mutableSetOf<String>()
    private val prompts = mutableListOf<List<String>>()
    private val reports = mutableListOf<String>()
    private val media = AndroidMediaRequests(TrustedOrigin("$trusted/hovel"), held::contains) { message, _ -> reports += message }
        .also { it.ask = { permissions -> prompts += permissions.toList() } }

    private fun ask(vararg resources: String, origin: String = trusted, mainFrameUrl: String? = "$trusted/meet/abcdefghij") =
        FakeRequest(origin, resources.toList()).also { media.request(it, mainFrameUrl) }

    @Test
    fun webViewResourcesMapToKinds() {
        assertEquals(MediaKind.Microphone, AndroidMediaRequests.mediaKindOf(audio))
        assertEquals(MediaKind.Camera, AndroidMediaRequests.mediaKindOf(video))
        assertNull(AndroidMediaRequests.mediaKindOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
        assertNull(AndroidMediaRequests.mediaKindOf(PermissionRequest.RESOURCE_MIDI_SYSEX))
    }

    @Test
    fun heldPermissionsAreGrantedWithoutAPrompt() {
        held += listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        assertEquals(setOf(audio, video), ask(audio, video).answer)
        assertEquals(setOf(audio), ask(audio).answer)
        assertEquals(emptyList(), prompts)
    }

    @Test
    fun deniedRequestsNeverPrompt() {
        held += listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        assertEquals("denied", ask(audio, origin = "https://attacker.test").answer)
        assertEquals("denied", ask(audio, video, PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID).answer)
        assertEquals("denied", ask().answer)
        assertEquals(emptyList(), prompts)
        assertEquals(3, reports.size)
    }

    @Test
    fun theTrustedPageInsideAnotherPageIsDenied() {
        held += Manifest.permission.RECORD_AUDIO
        assertEquals("denied", ask(audio, mainFrameUrl = "https://attacker.test/frames-hovel").answer)
        assertEquals("denied", ask(audio, mainFrameUrl = null).answer)
    }

    @Test
    fun aPromptAsksOnlyForMissingPermissionsAndGrantsWhatAndroidAllows() {
        held += Manifest.permission.RECORD_AUDIO
        val request = ask(audio, video)
        assertEquals(listOf(listOf(Manifest.permission.CAMERA)), prompts)
        assertNull(request.answer)
        // The person says no to the camera: only the microphone is granted.
        media.answered()
        assertEquals(setOf(audio), request.answer)
    }

    @Test
    fun aRefusedPromptDeniesTheRequest() {
        val request = ask(audio)
        media.answered()
        assertEquals("denied", request.answer)
    }

    @Test
    fun requestsWaitForTheOpenPromptInOrder() {
        val first = ask(audio)
        val second = ask(video)
        assertEquals(listOf(listOf(Manifest.permission.RECORD_AUDIO)), prompts)
        held += Manifest.permission.RECORD_AUDIO
        media.answered()
        assertEquals(setOf(audio), first.answer)
        assertEquals(listOf(Manifest.permission.CAMERA), prompts.last())
        held += Manifest.permission.CAMERA
        media.answered()
        assertEquals(setOf(video), second.answer)
    }

    @Test
    fun aCanceledRequestGetsNoAnswer() {
        val prompting = ask(audio)
        val queued = ask(video)
        media.canceled(queued)
        media.canceled(prompting)
        held += Manifest.permission.RECORD_AUDIO
        media.answered()
        assertNull(prompting.answer)
        assertNull(queued.answer)
        assertEquals(1, prompts.size)
    }

    @Test
    fun withoutAPromptARequestThatNeedsOneIsDenied() {
        media.ask = null
        assertEquals("denied", ask(audio).answer)
    }

    @Test
    fun aPromptThatFailsToOpenDeniesAndMovesOn() {
        held += Manifest.permission.CAMERA
        media.ask = { error("no activity") }
        val failed = ask(audio)
        val next = ask(video)
        assertEquals("denied", failed.answer)
        assertEquals(setOf(video), next.answer)
    }

    @Test
    fun closeDeniesEveryOpenRequest() {
        val prompting = ask(audio)
        val queued = ask(video)
        media.close()
        assertEquals("denied", prompting.answer)
        assertEquals("denied", queued.answer)
        media.answered()
        assertEquals(1, prompts.size)
    }
}
