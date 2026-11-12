package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.runtimes.ProjectFolders

/*
 * What the coordinator is shown of a project's files without reading any for it: a short brief Hovel builds for each
 * message. The design is in docs/companion-folders/companion-folders-step2.ARCH.md.
 */

/** What a brief may hold. Starting values, to be tuned from use. */
internal object BriefLimits {
    const val CHARS = 10_000
    const val README_CHARS = 1_500
    const val FOLDERS = 60
    const val FILES_PER_FOLDER = 12

    /** A folder with more entries than this is not counted past it, so one huge folder cannot slow a message. */
    const val ENTRIES_READ = 500
    const val MANIFEST_BYTES = 200_000L
    const val DEPENDENCIES = 30
}

/**
 * What is never read for the coordinator and never named to it, whatever it asks: the same in every project. It does
 * not follow a project's `.gitignore`.
 */
internal object SkipList {
    val FOLDERS = setOf(".git", "node_modules", "build", "dist", "out", ".next", ".gradle", ".idea", "DerivedData", "target", ".venv", "__pycache__")
    private val NAMES = setOf(".ds_store", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa")
    private val ENDINGS = listOf(".pem", ".key", ".p12", ".pfx", ".keystore", ".jks")

    fun folder(name: String): Boolean = name in FOLDERS

    /** A file of secrets, by its name: an environment file, a key, a keystore. */
    fun file(name: String): Boolean {
        val lower = name.lowercase()
        return lower in NAMES || lower == ".env" || lower.startsWith(".env.") || ENDINGS.any(lower::endsWith) ||
            lower.startsWith("id_rsa") || lower.startsWith("id_ed25519")
    }
}

/** Build files that say what a project is, in the order they are named in a brief. */
private val MANIFESTS = listOf("package.json", "settings.gradle.kts", "settings.gradle", "build.gradle.kts", "build.gradle", "pom.xml",
    "Cargo.toml", "go.mod", "pyproject.toml", "requirements.txt", "Package.swift", "Gemfile", "composer.json", "Makefile")

private val READMES = listOf("readme.md", "readme", "readme.txt", "readme.rst")

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * A short description of the working folder of [folders]: what its build files say, the start of its README, and
 * its folders with their first files. Null when the project has no working folder, or it cannot be read. Built for
 * each message and never kept: the walk is bounded ([BriefLimits]), so its cost does not grow with the project.
 * Skipped folders and files ([SkipList]) are neither read nor named.
 */
internal fun projectBrief(folders: ProjectFolders): String? {
    val root = folders.working?.takeIf { Files.isDirectory(it) } ?: return null
    return runCatching {
        val top = entries(root)
        val parts = listOfNotNull(manifests(root, top.files), readme(root, top.files), listing(root))
        parts.joinToString("\n\n").take(BriefLimits.CHARS).trimEnd().ifEmpty { null }
    }.getOrNull()
}

/** A folder's entries a brief may name: its files and its folders, sorted, without what is skipped or linked. */
private class Entries(val files: List<String>, val folders: List<String>, val more: Boolean)

private fun entries(dir: Path): Entries {
    val files = ArrayList<String>()
    val folders = ArrayList<String>()
    var seen = 0
    var more = false
    Files.newDirectoryStream(dir).use { stream ->
        for (entry in stream) {
            if (++seen > BriefLimits.ENTRIES_READ) { more = true; break }
            val name = entry.fileName.toString()
            when {
                Files.isSymbolicLink(entry) -> Unit
                Files.isDirectory(entry, NOFOLLOW_LINKS) -> if (!SkipList.folder(name)) folders += name
                Files.isRegularFile(entry, NOFOLLOW_LINKS) -> if (!SkipList.file(name)) files += name
            }
        }
    }
    return Entries(files.sorted(), folders.sorted(), more)
}

private fun manifests(root: Path, files: List<String>): String? {
    val found = MANIFESTS.filter { it in files }
    if (found.isEmpty()) return null
    val lines = mutableListOf("Build files: ${found.joinToString(", ")}")
    if ("package.json" in found) packageJson(root.resolve("package.json"))?.let(lines::add)
    return lines.joinToString("\n")
}

/** A `package.json` as one line: its name, its scripts and what it depends on. Null when it cannot be read. */
private fun packageJson(file: Path): String? = runCatching {
    if (Files.size(file) > BriefLimits.MANIFEST_BYTES) return null
    val json = lenient.parseToJsonElement(Files.readString(file)).jsonObject
    fun keys(name: String) = (json[name] as? JsonObject)?.keys.orEmpty()
    val depends = (keys("dependencies") + keys("devDependencies")).toList()
    listOfNotNull(
        json["name"]?.jsonPrimitive?.content?.let { "name $it" },
        keys("scripts").takeIf { it.isNotEmpty() }?.let { "scripts ${it.joinToString(", ")}" },
        depends.takeIf { it.isNotEmpty() }?.let { names ->
            "depends on ${names.take(BriefLimits.DEPENDENCIES).joinToString(", ")}" + if (names.size > BriefLimits.DEPENDENCIES) " and ${names.size - BriefLimits.DEPENDENCIES} more" else ""
        },
    ).takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "package.json: ")
}.getOrNull()

private fun readme(root: Path, files: List<String>): String? {
    val name = READMES.firstNotNullOfOrNull { wanted -> files.firstOrNull { it.lowercase() == wanted } } ?: return null
    val text = runCatching { Files.newBufferedReader(root.resolve(name)).use { reader ->
        CharArray(BriefLimits.README_CHARS + 1).let { buffer -> String(buffer, 0, maxOf(reader.read(buffer), 0)) }
    } }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val cut = text.length > BriefLimits.README_CHARS
    return "$name, ${if (cut) "its first ${BriefLimits.README_CHARS} characters" else "in full"}:\n" + text.take(BriefLimits.README_CHARS)
}

/** The project's folders, nearest the top first, each with its first files and how many it holds. */
private fun listing(root: Path): String {
    val lines = ArrayList<String>()
    val waiting = ArrayDeque(listOf(root))
    var left = 0
    while (waiting.isNotEmpty()) {
        val dir = waiting.removeFirst()
        if (lines.size == BriefLimits.FOLDERS) { left = waiting.size + 1; break }
        val found = runCatching { entries(dir) }.getOrNull() ?: continue
        val shown = found.files.take(BriefLimits.FILES_PER_FOLDER)
        val count = if (found.more) "${BriefLimits.ENTRIES_READ}+ entries" else "${found.files.size} ${if (found.files.size == 1) "file" else "files"}"
        val name = if (dir == root) "./" else root.relativize(dir).toString() + "/"
        lines += "  $name ($count)" + if (shown.isEmpty()) "" else ": " + shown.joinToString(", ") + if (found.files.size > shown.size) ", …" else ""
        waiting += found.folders.map(dir::resolve)
    }
    return "Folders, nearest the top first:\n" + lines.joinToString("\n") + if (left > 0) "\n  … and at least $left more folders" else ""
}
