package org.formulagen.wrapper.providers

/**
 * Claude is offered only when a development build ([development]: `-Dgamero.dev=true`, which only
 * local launches set) asks for it with `HOVEL_CLAUDE_PROVIDER=1`. A release build ([release]) never
 * offers it, until Anthropic confirms a companion persona on a player's own subscription is allowed.
 * Anything else, including an unmarked build, fails closed.
 */
internal fun claudeProviderEnabled(env: Map<String, String>, release: Boolean, development: Boolean): Boolean =
    development && !release && env["HOVEL_CLAUDE_PROVIDER"] == "1"

/**
 * Roles bound to Codex may work in a project folder only when a development build asks for it with
 * `HOVEL_CODEX_WORK=1`: Codex's approval protocol is read from its schema only until it is verified
 * live. A release build never enables it; anything else fails closed. The page learns the result
 * from `providers.list.working`, and the conversations' working runtimes come from the same answer.
 */
internal fun codexWorkEnabled(env: Map<String, String>, release: Boolean, development: Boolean): Boolean =
    development && !release && env["HOVEL_CODEX_WORK"] == "1"

/** Grok subscription chat is development-only and verifies its installed runtime before use. */
internal fun grokProviderEnabled(env: Map<String, String>, release: Boolean, development: Boolean): Boolean =
    development && !release && env["HOVEL_GROK_PROVIDER"] == "1"

/** Project work is a separate opt-in; a release or hidden provider can never advertise it. */
internal fun grokWorkEnabled(env: Map<String, String>, release: Boolean, development: Boolean): Boolean =
    grokProviderEnabled(env, release, development) && env["HOVEL_GROK_WORK"] == "1"
