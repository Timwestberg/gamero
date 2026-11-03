package org.formulagen.wrapper.workspace

import kotlinx.serialization.Serializable

/*
 * The account's workspace profile as it travels: event-contracts `hovel/companions/workspace-profile.schemas.ts`, read
 * from and saved to the native `workspace-profile` routes. The server validates it strictly against its own catalog;
 * this Mac reads it leniently. Tool, tab and limit ids stay strings, and every value is kept as it came, so entries a
 * newer Mac wrote survive this one and are written back unchanged. Only the keys are fixed: the contract decode
 * (`fromWire`) refuses one the contract does not name, as for every contract type. What this Mac can use is resolved
 * from these separately.
 */

/** `workspaceProfile`. Ids stay strings so a newer Mac's entries survive this Mac. */
@Serializable
internal data class WireWorkspaceProfile(
    val limits: Map<String, Int> = emptyMap(),
    val prompt: String,
    val tabs: List<String>,
    val tools: Map<String, WireToolChoice>,
    val version: Int,
)

/** `workspaceToolChoice`: Hovel's copy or the installed one, and Hovel's config or the player's own. */
@Serializable
internal data class WireToolChoice(val binary: String, val config: String)

/** `workspaceProfileDocument`: the profile at its revision, 0 until the account's first save. */
@Serializable
internal data class WireWorkspaceProfileDocument(val profile: WireWorkspaceProfile, val revision: Long)

/** `workspaceProfileSave`: the whole profile, and the revision it was edited from. */
@Serializable
internal data class WireWorkspaceProfileSave(val expectedRevision: Long, val profile: WireWorkspaceProfile)
