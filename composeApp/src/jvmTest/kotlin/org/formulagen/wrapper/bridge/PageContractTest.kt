package org.formulagen.wrapper.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.cef.callback.CefQueryCallback
import org.formulagen.wrapper.desktopProductPacks
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import kotlin.test.Test
import kotlin.test.assertEquals

/** A page from before contract 3 (`companionsOnly`) is asked to update by the handshake and by the methods that changed. */
@OptIn(ExperimentalCoroutinesApi::class)
class PageContractTest {
    private val trusted = "https://hovel.example/hovel"
    private val owner = "6f1c2d3e-4a5b-4c6d-8e7f-901234567890"

    private class Answers : CefQueryCallback {
        val successes = mutableListOf<String>()
        override fun success(response: String) { successes += response }
        override fun failure(errorCode: Int, errorMessage: String) = error("A v2 query answers with an envelope: $errorCode")
        fun reply(): JsonObject = Json.parseToJsonElement(successes.single()).jsonObject
    }

    private class FakeCompanion : CompanionCapability {
        override suspend fun prepareConnection(ownerId: String) = CompanionRegistration(ownerId, "My Mac", "a".repeat(64))
        override suspend fun activateConnection(ownerId: String, hostId: String) = buildJsonObject { put("id", hostId); put("connected", true) }
    }

    private class FakeProjects : ProjectCapability {
        override suspend fun chooseFolder(args: ProjectChooseFolderArgs) = ProjectFoldersStatus(emptyList())
        override suspend fun forgetFolder(args: ProjectForgetFolderArgs) = ProjectFoldersStatus(emptyList())
        override suspend fun folderStatus(scope: ProjectScope) = ProjectFoldersStatus(emptyList())
    }

    private fun TestScope.page(): (String, Boolean) -> JsonObject {
        val contract = DesktopHostContract()
        val transport = JcefHostBridge(HostBridge(
            product = HOVEL_PRODUCT,
            trustedUrl = trusted,
            capabilities = desktopHostCapabilities(),
            products = desktopProductPacks(HOVEL_PRODUCT, FakeCompanion(), FakeProviderCapability(), FakePetCapability(),
                FakeGameCapability(), FakeProjects(), contract, FakeConversationVoiceCapability(), pageCheck = contract::requireCurrentPage),
            context = backgroundScope.coroutineContext,
        ))
        var queryId = 0L
        return { request, persistent ->
            val answers = Answers()
            transport.onQuery(request, persistent, true, "https://hovel.example/companions/6f1c2d3e", queryId++, answers)
            advanceUntilIdle(); runCurrent()
            answers.reply()
        }
    }

    private fun call(context: String, method: String, args: String = "{}") =
        """{"version":2,"contextId":"$context","method":"$method","args":$args}"""

    /** The calls a pre-step-E page could make, each from a method contract 3 changed. */
    private fun changedCalls(context: String) = listOf(
        call(context, "companion.capabilities"),
        call(context, "companion.prepareConnection", """{"ownerId":"$owner"}"""),
        call(context, "providers.list"),
        call(context, "project.folderStatus", """{"projectId":"$owner"}"""),
    )

    @Test fun aPageBelowContractThreeIsAskedToUpdateEverywhereThatChanged() = runTest {
        val send = page()
        val context = send("""{"version":2,"method":"watchContext","args":{}}""", true).getValue("contextId").jsonPrimitive.content
        val handshake = send(call(context, "host.contract", """{"contract":2}"""), false)
        assertEquals(BridgeUpdateRequiredException.MESSAGE, handshake.getValue("error").jsonObject.getValue("message").jsonPrimitive.content)
        for (request in changedCalls(context)) {
            val error = send(request, false).getValue("error").jsonObject
            assertEquals("failed", error.getValue("code").jsonPrimitive.content, request)
            assertEquals(BridgeUpdateRequiredException.MESSAGE, error.getValue("message").jsonPrimitive.content, request)
        }
        // What did not change still answers, so the page can show its update message.
        assertEquals(true, send(call(context, "host.info"), false).getValue("ok").jsonPrimitive.boolean)
    }

    @Test fun aPageAtContractThreeGetsEveryAnswer() = runTest {
        val send = page()
        val context = send("""{"version":2,"method":"watchContext","args":{}}""", true).getValue("contextId").jsonPrimitive.content
        val handshake = send(call(context, "host.contract", """{"contract":${HovelContract.COMPANIONS_ONLY}}"""), false)
        assertEquals(HovelContract.CURRENT, handshake.getValue("result").jsonObject.getValue("contract").jsonPrimitive.int)
        for (request in changedCalls(context)) assertEquals(true, send(request, false).getValue("ok").jsonPrimitive.boolean, request)
    }
}
