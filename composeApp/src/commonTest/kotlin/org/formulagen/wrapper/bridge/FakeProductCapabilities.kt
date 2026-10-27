package org.formulagen.wrapper.bridge

/** Protocol fixtures exercise dispatch without starting a provider, pet or game process. */
class FakeProviderCapability : ProviderCapability {
    override suspend fun perform(action: ProviderAction, scope: ProviderScope) =
        ProviderStatus(scope.providerId, if (action == ProviderAction.CONNECT) ProviderState.SIGNING_IN else ProviderState.DISCONNECTED)
}

class FakePetCapability : PetCapability {
    override suspend fun status() = PetStatus(PetState.STOPPED)
    override suspend fun show() = PetStatus(PetState.RUNNING)
    override suspend fun hide() = PetStatus(PetState.STOPPED)
    override suspend fun configure(companion: PetCompanion) = PetStatus(PetState.STOPPED, companion.id, companion.name)
}

class FakeGameCapability : GameCapability {
    /** The listener the router subscribed; call it to simulate the player returning. */
    var closed: (GameId) -> Unit = {}
    override suspend fun launch(gameId: GameId) = GameLaunchResult(gameId)
    override fun observeClosed(onClosed: (GameId) -> Unit): AutoCloseable {
        closed = onClosed
        return AutoCloseable { closed = {} }
    }
}
