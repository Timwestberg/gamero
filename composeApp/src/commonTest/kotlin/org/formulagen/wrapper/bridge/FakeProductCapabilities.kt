package org.formulagen.wrapper.bridge
import org.formulagen.wrapper.conversation.DictationState
import org.formulagen.wrapper.conversation.HearingState

/** Protocol fixtures exercise dispatch without starting a provider, pet or game process. */
class FakeProviderCapability : ProviderCapability {
    override suspend fun usage(scope: ProviderScope) = ProviderUsage(scope.runtimeId, 1793200000000, ProviderUsageWindow(0.0, 1793203600), ProviderUsageWindow(76.5))
    override suspend fun setup(scope: ProviderScope) = ProviderSetup(scope.runtimeId, ProviderSetupState.READY)
    override suspend fun startLogin(scope: ProviderScope) = ProviderLogin(scope.runtimeId, true)

    override suspend fun perform(action: ProviderAction, scope: ProviderScope) =
        ProviderStatus(scope.runtimeId, if (action == ProviderAction.CONNECT) ProviderState.CONNECTED else ProviderState.DISCONNECTED)
    override suspend fun list() = ProviderList(listOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE), listOf(RuntimeId.CLAUDE_CODE))
    override suspend fun models(scope: ProviderScope) = ProviderModels(listOf(ProviderModel("Sonnet", true, "sonnet")), scope.runtimeId)
}

class FakePetCapability : PetCapability {
    override suspend fun status() = PetStatus(PetState.STOPPED)
    override suspend fun show() = PetStatus(PetState.RUNNING)
    override suspend fun hide() = PetStatus(PetState.STOPPED)
    override suspend fun configure(companion: PetCompanion) = PetStatus(PetState.STOPPED, companion.id, companion.name)
}

class FakeGameCapability : GameCapability {
    /** The listener the router subscribed, or null when none is; call it to simulate the player returning. */
    var closed: ((GameId) -> Unit)? = null
    /** Each launch's game and companion, in order. */
    val launches = mutableListOf<Pair<GameId, PetCompanion?>>()
    override suspend fun launch(gameId: GameId, companion: PetCompanion?): GameLaunchResult {
        launches += gameId to companion
        return GameLaunchResult(gameId)
    }
    override fun observe(onClosed: (GameId) -> Unit): AutoCloseable {
        closed = onClosed
        return AutoCloseable { closed = null }
    }
}

class FakeProjectCapability : ProjectCapability {
    private val working = ProjectFolderStatus(true, "70000000-0000-4000-8000-000000000002", "hovel-notes", emptyList(), FolderRole.WORKING)
    override suspend fun chooseFolder(args: ProjectChooseFolderArgs) = ProjectFoldersStatus(listOf(working))
    override suspend fun forgetFolder(args: ProjectForgetFolderArgs) = ProjectFoldersStatus(emptyList())
    override suspend fun folderStatus(scope: ProjectScope) = ProjectFoldersStatus(listOf(working))
}

class FakeConversationVoiceCapability : ConversationVoiceCapability {
    override suspend fun dictation(args: ConversationDictationArgs) =
        ConversationVoiceStatus(args.conversationId, dictation = ConversationDictation(DictationState.LISTENING))
    override suspend fun hearing(args: ConversationHearingArgs) =
        ConversationVoiceStatus(args.conversationId, hearing = ConversationHearing(HearingState.LISTENING))
    override suspend fun voiceStatus() = ConversationVoiceStatus(null)
}

class FakeApprovalCapability : ApprovalCapability {
    val presented = mutableListOf<String>()
    override suspend fun present(approvalId: String): ApprovalPresentResult {
        presented += approvalId
        return ApprovalPresentResult(ApprovalPresentOutcome.ANSWERED)
    }
}

class FakeApprovalDeviceCapability : ApprovalDeviceCapability {
    override suspend fun prepare(ownerId: String) =
        ApprovalDeviceRegistration("79a0000b-0000-4000-8000-000000000001", "iPhone", "ios", "0f".repeat(32))
    override suspend fun activate(ownerId: String, deviceId: String) = ApprovalDeviceActivated(deviceId)
    override suspend fun pair() = ApprovalDevicePaired(ApprovalDevicePairOutcome.DISMISSED)
    override suspend fun forget() = ApprovalDeviceForgotten(true)
    override suspend fun status(): ApprovalDeviceRef? = null
}

class FakeHostContractCapability : HostContractCapability {
    val pageContracts = mutableListOf<Int>()
    override suspend fun contract(pageContract: Int): Int {
        pageContracts += pageContract
        return 1
    }
}
