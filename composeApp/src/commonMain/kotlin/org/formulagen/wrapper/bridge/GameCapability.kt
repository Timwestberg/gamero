package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Installed games a page may launch. Native configuration owns each game's project, scene and command.
 * One game is open at a time: a launch while one is open throws [BridgeBusyException].
 */
interface GameCapability {
    /** Starts [gameId] as [companion] (its id, name and colors), or as the game's own Ember when it is null. */
    suspend fun launch(gameId: GameId, companion: PetCompanion?): GameLaunchResult

    /**
     * Calls [onClosed] at most once per launch that opened, when the player returns from that game.
     * A launch that throws calls nothing.
     */
    fun observe(onClosed: (GameId) -> Unit): AutoCloseable
}

@Serializable
enum class GameId {
    @SerialName("ember-overclock") EMBER_OVERCLOCK,
    @SerialName("ember-tower") EMBER_TOWER,
    @SerialName("hovel-companions") HOVEL_COMPANIONS,
}

/**
 * The game, and the companion to play as: absent, never null. A page sends a companion, or names Tower or
 * Overclock, only to a host at contract 4 or later.
 */
@Serializable
data class GameLaunchArgs(val gameId: GameId, val companion: PetCompanion? = null)

@Serializable
data class GameLaunchResult(val gameId: GameId, val started: Boolean = true)

@Serializable
data class GameClosed(val gameId: GameId)

/** Methods and events of the games capability. Mirrors event-contracts/host-bridge/games.ts. */
object GameMethods {
    /** A companion is checked by the contract's petCompanion rules, as `pet.configure` checks it. */
    val LAUNCH = BridgeMethod("games.launch", GameLaunchArgs.serializer(), GameLaunchResult.serializer()) { args ->
        args.companion?.let(PetMethods::isValid) ?: true
    }
    val CLOSED = BridgeEvent("games.closed", GameClosed.serializer())

    fun pack(games: Capability<GameCapability>): CapabilityPack = binder.pack(games)

    internal val binder = CapabilityBinder(
        name = "games",
        handlers = listOf<CapabilityHandler<GameCapability>>(
            LAUNCH.handle { games, args -> games.launch(args.gameId, args.companion) },
        ),
        events = listOf(CLOSED),
        observe = { games, events ->
            games.observe(onClosed = { gameId -> events.emit(CLOSED, GameClosed(gameId)) })
        },
    )
}
