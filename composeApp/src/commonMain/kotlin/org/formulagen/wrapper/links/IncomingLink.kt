package org.formulagen.wrapper.links

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.formulagen.wrapper.bridge.TrustedOrigin

/** A code's link: `/link/`, then the flow and the code. Letters, digits, `-`, `_` and `/` only. */
private val LINK_PATH = Regex("^/link/[A-Za-z0-9_-]+(/[A-Za-z0-9_-]+)*$")

/** Longer than any code's link. A longer link is not one of ours. */
private const val LINK_LIMIT = 512

/**
 * The page to open for [url], a link the system handed to the app, or null when the app must not open it.
 *
 * A QR code for sign-in is an https link on the web app's own address, under `/link/`. The phone's camera hands such
 * a link to the app, and this decides what the web view loads for it: the same path on the web app's own origin,
 * with the query and the fragment dropped. Anything else (another host, another port, http, another path, a path
 * with characters a code never has) is refused, so a link can never point the web view, which holds the person's
 * session, at a page that is not the sign-in question.
 *
 * [webUrl] is the web app's address, the one the app loads when it starts.
 */
fun incomingLinkTarget(url: String, webUrl: String): String? {
    if (url.length > LINK_LIMIT || !url.startsWith("https://", ignoreCase = true)) return null
    val web = runCatching { TrustedOrigin(webUrl) }.getOrNull() ?: return null
    if (!web.matches(url)) return null
    val pathStart = url.indexOf('/', startIndex = "https://".length)
    if (pathStart < 0) return null
    val pathEnd = url.indexOfAny(charArrayOf('?', '#'), pathStart).takeIf { it >= 0 } ?: url.length
    val path = url.substring(pathStart, pathEnd)
    return if (LINK_PATH.matches(path)) web.rule + path else null
}

/**
 * Links the system handed to the app, kept until the web view takes them. There is one for the process: the system
 * calls the app's entry (Swift's `onOpenURL`, Android's `onNewIntent`), which has no web view to give the link to.
 * Only the newest link is kept. A person who scans two codes wants the second.
 */
object IncomingLinks {
    private val links = Channel<String>(Channel.CONFLATED)

    /** Keeps [url], as the system gave it, for the web view. Nothing is checked here; the taker checks. */
    fun offer(url: String) {
        links.trySend(url)
    }

    /** Each link, once, to the one collector. */
    val pending: Flow<String> = links.receiveAsFlow()
}

/**
 * The page the web view shows: [first] or [webUrl] as the app starts, then the page for each link the system hands
 * over that [incomingLinkTarget] accepts. A refused link changes nothing.
 *
 * The web view loads a page when this value changes. A link for the page it was last sent to changes nothing here,
 * so [reopen] is called with it instead: the person may have moved on inside the web app since.
 */
@Composable
fun rememberLinkedPage(webUrl: String, first: String? = null, reopen: (String) -> Unit = {}): State<String> {
    val page = remember(webUrl) { mutableStateOf(first?.let { incomingLinkTarget(it, webUrl) } ?: webUrl) }
    val currentReopen = rememberUpdatedState(reopen)
    LaunchedEffect(webUrl) {
        IncomingLinks.pending.collect { link ->
            val target = incomingLinkTarget(link, webUrl) ?: return@collect
            if (page.value == target) currentReopen.value(target) else page.value = target
        }
    }
    return page
}
