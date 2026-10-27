package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Installed games a page may launch. Native configuration owns each game's files and command.
 * One game is open at a time: a launch while one is open throws [BridgeBusyException].
 */
interface GameCapability {
    suspend fun launch(gameId: GameId): GameLaunchResult

    /** Calls [onClosed] once per launch, when the player returns from that game. */
    fun observeClosed(onClosed: (GameId) -> Unit): AutoCloseable
}

@Serializable
enum class GameId {
    @SerialName("hovel-companions") HOVEL_COMPANIONS,
}

@Serializable
data class GameLaunchArgs(val gameId: GameId)

@Serializable
data class GameLaunchResult(val gameId: GameId, val started: Boolean = true)

@Serializable
data class GameClosed(val gameId: GameId)

/** Mirrors event-contracts/host-bridge/games.ts. */
object GameMethods {
    val LAUNCH = BridgeMethod("games.launch", GameLaunchArgs.serializer(), GameLaunchResult.serializer())
    val CLOSED = BridgeEvent("games.closed", GameClosed.serializer())

    fun pack(games: Capability<GameCapability>): CapabilityPack = binder.pack(games)

    internal val binder = CapabilityBinder(
        name = "games",
        handlers = listOf<CapabilityHandler<GameCapability>>(
            LAUNCH.handle { games, args -> games.launch(args.gameId) },
        ),
        events = listOf(CLOSED),
        observe = { games, events -> games.observeClosed { gameId -> events.emit(CLOSED, GameClosed(gameId)) } },
    )
}
