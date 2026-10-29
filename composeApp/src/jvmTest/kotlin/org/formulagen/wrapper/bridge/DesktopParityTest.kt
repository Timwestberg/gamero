package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.formulagen.wrapper.companion.HostCredentials
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.desktopProductPacks
import org.formulagen.wrapper.hovel.HovelCompanionCapability
import java.nio.file.Files
import kotlin.test.Test

class DesktopParityTest {
    /**
     * Phase C of the host bridge plan ships the platform methods on desktop, and voice in conversations ships
     * conversation voice. Project folders and the contract handshake are the conversation runner's.
     */
    private val pending = setOf(
        "lifecycle.pause", "lifecycle.resume",
        "conversationVoice.dictation", "conversationVoice.hearing", "conversationVoice.voiceStatus",
    )

    private class FakeProjects : ProjectCapability {
        override suspend fun chooseFolder(args: ProjectChooseFolderArgs) = ProjectFoldersStatus(emptyList())
        override suspend fun forgetFolder(args: ProjectForgetFolderArgs) = ProjectFoldersStatus(emptyList())
        override suspend fun folderStatus(scope: ProjectScope) = ProjectFoldersStatus(emptyList())
    }

    @Test
    fun hovelOnDesktopMatchesTheParityTable() {
        val directory = Files.createTempDirectory("parity")
        val credentials = object : HostCredentials {
            override fun save(hostId: String, token: String) = Unit
            override fun read(hostId: String) = ""
        }
        val companion = HovelCompanion("https://example.test/hovel.html", directory, directory.resolve("codex"), credentials = credentials)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val router = BridgeRouter(
            product = HOVEL_PRODUCT,
            capabilities = desktopHostCapabilities(),
            products = desktopProductPacks(HOVEL_PRODUCT, HovelCompanionCapability(companion), FakeChatCapability(),
                FakeProviderCapability(), FakePetCapability(), FakeGameCapability(), FakeWorkspaceCapability(), FakeBrainCapability(),
                FakeProjects(), DesktopHostContract()),
            scope = scope,
            transport = {},
        )
        try {
            assertMatchesParity(HostPlatform.DESKTOP, router, pending)
        } finally {
            router.close()
            scope.cancel()
            companion.close()
        }
    }
}
