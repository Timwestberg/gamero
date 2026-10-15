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
