package org.formulagen.wrapper.bridge

/** Protocol fixtures exercise dispatch without starting a provider, pet or game process. */
class FakeProviderCapability : ProviderCapability {
    override suspend fun perform(action: ProviderAction, scope: ProviderScope) =
        ProviderStatus(scope.runtimeId, if (action == ProviderAction.CONNECT) ProviderState.SIGNING_IN else ProviderState.DISCONNECTED)
    override suspend fun list(profile: String) = ProviderList(RuntimeId.entries)
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
    override suspend fun launch(gameId: GameId) = GameLaunchResult(gameId)
    override fun observe(onClosed: (GameId) -> Unit): AutoCloseable {
        closed = onClosed
        return AutoCloseable { closed = null }
    }
}

class FakeWorkspaceCapability : WorkspaceCapability {
    override suspend fun choose(scope: WorkspaceScope) = WorkspaceStatus(WorkspaceFolder(true, "hovel-notes"))
    override suspend fun clear(scope: WorkspaceScope) = WorkspaceStatus(null)
    override suspend fun status(scope: WorkspaceScope) = WorkspaceStatus(WorkspaceFolder(true, "hovel-notes"))
}
