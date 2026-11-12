package org.formulagen.wrapper.bridge

import kotlinx.serialization.Serializable

/** Opens an HTTPS URL in the system browser. The router has already checked the URL. */
fun interface LinkOpener {
    suspend fun open(url: String)
}

@Serializable
data class LinkOpenArgs(val url: String)

object LinkMethods {
    val OPEN = BridgeMethod("link.open", LinkOpenArgs.serializer(), NoResult.serializer()) { isSafeHttpsUrl(it.url) }

    internal val binder = CapabilityBinder(
        "links",
        listOf(OPEN.handle { links: LinkOpener, args -> links.open(args.url); NoResult }),
    )
}

/** Where a link that asks for a new window goes in a host whose web view has one window. */
enum class NewWindowTarget {
    /** The app's own page: load it in the web view, where the person is signed in. */
    HERE,
    /** Another site: hand it to the system, which opens the browser or the site's own app. */
    OUTSIDE,
    /** Not a link this host opens. Nothing happens, as before. */
    IGNORE,
}

/**
 * Decides a `target="_blank"` link or `window.open` from the frame at [sourceOrigin]. Only the trusted page's own
 * frames may open anything, and what leaves the app passes the same check as `link.open`: HTTPS, a public host,
 * no credentials. With no trusted [origin] (a page that is not Hovel) nothing opens.
 */
fun newWindowTarget(origin: TrustedOrigin?, sourceOrigin: String, url: String?): NewWindowTarget = when {
    origin == null || url == null || !origin.matches(sourceOrigin) -> NewWindowTarget.IGNORE
    origin.matches(url) -> NewWindowTarget.HERE
    isSafeHttpsUrl(url) -> NewWindowTarget.OUTSIDE
    else -> NewWindowTarget.IGNORE
}
