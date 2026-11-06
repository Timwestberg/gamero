package org.formulagen.wrapper.providers

import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import org.formulagen.wrapper.bridge.BrainLimits
import org.formulagen.wrapper.bridge.ProviderSkill
import org.formulagen.wrapper.bridge.ProviderSkillText
import org.formulagen.wrapper.util.shortened
import org.formulagen.wrapper.util.takeWhole

/*
 * The skills a provider has installed on this Mac: one folder per skill, each holding a `SKILL.md` whose YAML front
 * matter describes it. The provider directory lists them ([readInstalledSkills]); a specialist that uses one gets its
 * text from the Mac ([readSkillText]), because companion turns never let a provider load skills itself.
 */

/** Claude Code's personal skills: `skills` in the player's `CLAUDE_CONFIG_DIR` when it is set, else `~/.claude/skills`. */
internal fun claudeSkillsRoot(environment: Map<String, String>): Path {
    val configured = environment["CLAUDE_CONFIG_DIR"]?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Path.of(it) }.getOrNull() }?.takeIf { it.isAbsolute }
    return (configured ?: Path.of(environment["HOME"] ?: System.getProperty("user.home"), ".claude")).resolve("skills")
}

/**
 * The skills in [root] a model may use, by name, at most [BrainLimits.PROVIDER_SKILLS]. Each is a folder (or a link to
 * one) holding a `SKILL.md` whose front matter opens and closes with `---`. It is named by its folder, which is what
 * Claude Code invokes it by; a front matter `name` is only the label Claude Code shows beside it. Its `description` is
 * shortened to [BrainLimits.USE_TEXT]. Left out: a folder whose name is not a provider skill name (hidden ones too), an
 * unreadable or malformed `SKILL.md`, and a skill only the person may start (`disable-model-invocation: true`). A
 * missing or unreadable [root] has none.
 */
internal fun readInstalledSkills(root: Path): List<ProviderSkill> {
    val names = try { Files.list(root).use { entries -> entries.toList().map { it.fileName.toString() } } }
        catch (_: Exception) { return emptyList() }
    return names.filter(ProviderSkill::isName).sorted().asSequence()
        .mapNotNull { name -> readSkill(root, name) { _, skill -> skill } }
        .take(BrainLimits.PROVIDER_SKILLS)
        .toList()
}

/**
 * Skill [name]'s text in [root], when [readInstalledSkills] would list it: its description, and its `SKILL.md` after the
 * front matter, trimmed and cut to [ProviderSkillText.INSTRUCTIONS] characters. The file is named by its real path, so a
 * linked skill folder is named where it is. Null when the skill is not there or cannot be read now.
 */
internal fun readSkillText(root: Path, name: String): ProviderSkillText? = readSkill(root, name) { reader, skill ->
    val read = CharArray(SKILL_TEXT_READ)
    var length = 0
    while (length < read.size) { val count = reader.read(read, length, read.size - length); if (count < 0) break; length += count }
    val more = length == read.size && reader.read() >= 0
    val body = String(read, 0, length).trim()
    val file = root.resolve(name).resolve(SKILL_FILE).toRealPath()
    ProviderSkillText(skill.description, body.takeWhole(ProviderSkillText.INSTRUCTIONS),
        more || body.length > ProviderSkillText.INSTRUCTIONS, file.toString())
}

/** [then] with [name]'s `SKILL.md` in [root] positioned after its front matter, and the skill it describes; null when it is none. */
private fun <T> readSkill(root: Path, name: String, then: (BufferedReader, ProviderSkill) -> T): T? {
    if (!ProviderSkill.isName(name)) return null
    return try {
        Files.newBufferedReader(root.resolve(name).resolve(SKILL_FILE)).use { reader ->
            val frontMatter = readFrontMatter(reader) ?: return null
            if (frontMatter["disable-model-invocation"].equals("true", ignoreCase = true)) return null
            then(reader, ProviderSkill(name, frontMatter["description"].orEmpty().shortened(BrainLimits.USE_TEXT)))
        }
    } catch (_: Exception) { null }
}

/**
 * The top-level keys of the YAML front matter [reader] starts with, each value on one line with its whitespace
 * collapsed, leaving [reader] after it; null when it does not open with `---` or does not close it within
 * [FRONT_MATTER_LINES] lines.
 */
private fun readFrontMatter(reader: BufferedReader): Map<String, String>? {
    if (reader.readLine()?.removePrefix("﻿")?.trimEnd() != DELIMITER) return null
    val lines = mutableListOf<String>()
    while (lines.size <= FRONT_MATTER_LINES) {
        val line = reader.readLine() ?: return null
        if (line.trimEnd() == DELIMITER) return frontMatterKeys(lines)
        lines += line
    }
    return null
}

/**
 * The top-level `key: value` pairs of front matter [lines], enough YAML for a skill's description: a plain, quoted or
 * block (`|`, `>`) value, continued on the indented lines after it. Comments and nested values are read as text.
 */
internal fun frontMatterKeys(lines: List<String>): Map<String, String> = buildMap {
    var index = 0
    while (index < lines.size) {
        val key = KEY.matchEntire(lines[index])
        index++
        if (key == null) continue
        val parts = mutableListOf(key.groupValues[2])
        while (index < lines.size && (lines[index].isBlank() || lines[index].first().isWhitespace())) parts += lines[index++]
        val first = parts.first().trim()
        val text = if (BLOCK.matches(first)) parts.drop(1) else parts
        putIfAbsent(key.groupValues[1], text.joinToString(" ").trim().unquoted().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" "))
    }
}

/** A YAML scalar without its surrounding quotes: `''` in a single-quoted one is `'`, and `\"` in a double-quoted one `"`. */
private fun String.unquoted(): String = when {
    length >= 2 && startsWith('\'') && endsWith('\'') -> substring(1, length - 1).replace("''", "'")
    length >= 2 && startsWith('"') && endsWith('"') -> substring(1, length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    else -> this
}

private const val SKILL_FILE = "SKILL.md"
private const val DELIMITER = "---"
/** The longest front matter read; a skill's whole description fits well within it. */
private const val FRONT_MATTER_LINES = 200
/** How much of a skill's body is read: room for leading blank lines before the [ProviderSkillText.INSTRUCTIONS] kept. */
private const val SKILL_TEXT_READ = ProviderSkillText.INSTRUCTIONS * 2
private val KEY = Regex("^([A-Za-z0-9_-]+):(?:\\s+(.*)|)$")
/** A block scalar's header: `|` or `>`, with an optional chomping and indentation indicator. */
private val BLOCK = Regex("^[|>][-+]?[1-9]?[-+]?$")
private val WHITESPACE = Regex("\\s+")
