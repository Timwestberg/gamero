package org.formulagen.wrapper.bridge

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.voice.HearingActions
import org.formulagen.wrapper.voice.HearingStates
import org.formulagen.wrapper.voice.VoiceConversation
import kotlin.test.Test
import kotlin.test.assertEquals

/** The host's mirror of ember-hearing.v1, checked against the generated contract fixture. */
class HearingContractTest {
    private val contract = Json.parseToJsonElement(EmberHearingFixture.JSON).jsonObject
    private fun contractNames(key: String) = contract.getValue(key).jsonArray.map { it.jsonPrimitive.content }
    private fun <T> wire(serializer: KSerializer<T>, value: T) = bridgeJson.encodeToString(serializer, value).trim('"')

    @Test fun hearingStatesAreTheContractStates() {
        assertEquals(contractNames("states"), HearingState.entries.map { wire(HearingState.serializer(), it) })
        assertEquals(contractNames("states"), HearingStates.ALL)
    }

    @Test fun hearingActionsAreTheContractActions() {
        assertEquals(contractNames("actions"), HearingAction.entries.map { wire(HearingAction.serializer(), it) })
        assertEquals(contractNames("actions"), HearingActions.ALL)
    }

    @Test fun thePermissionSentenceIsTheContractCopy() {
        assertEquals(contract.getValue("permissionCopy").jsonPrimitive.content, VoiceConversation.PERMISSION_COPY)
    }
}
