package org.formulagen.wrapper.hovel

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE

/** `scheme://host[:port]` of [url]. The Mac keys what it keeps for a site by this, so the page's path can change. */
internal fun appOrigin(url: String): String =
    URI(url).let { URI(it.scheme, null, it.host, it.port, null, null, null).toASCIIString() }

/** Hovel pages whose full URL named the state folder before it was keyed by origin. `/hovel.html` was the default. */
private val LEGACY_PAGES = listOf("/hovel.html")

/**
 * The folder under [support] holding Hovel's registration, journals and outputs for the site at [appUrl]. It is keyed
 * by origin, so `/hovel.html` and `/hovel` share one host registration. A folder keyed by the full URL, from before,
 * moves here once; if that move fails, the old folder is used as it is.
 */
internal fun hovelStateFolder(support: Path, appUrl: String): Path {
    val origin = appOrigin(appUrl)
    val folder = support.resolve("hovel-${HovelCompanion.sha256(origin)}")
    if (Files.exists(folder)) return folder
    val legacy = (listOf(appUrl) + LEGACY_PAGES.map { origin + it })
        .map { support.resolve("hovel-${HovelCompanion.sha256(it)}") }
        .firstOrNull { Files.isDirectory(it) } ?: return folder
    return runCatching { Files.move(legacy, folder, ATOMIC_MOVE) }.getOrDefault(legacy)
}
