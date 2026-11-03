package org.formulagen.wrapper.workspace

/**
 * The workspace catalog's tools: each one's catalog [id], the [label] a tab shows, and the commands it
 * provides (project-workspace.ARCH.md, "Tool catalog"). The tool manager adds the pinned
 * `catalog.json` beside it.
 */
enum class WorkspaceTool(val id: String, val label: String, val commands: List<String>) {
    DELTA("delta", "delta", listOf("delta")),
    FD("fd", "fd", listOf("fd")),
    FZF("fzf", "fzf", listOf("fzf")),
    LAZYGIT("lazygit", "lazygit", listOf("lazygit")),
    NEOVIM("neovim", "Neovim", listOf("nvim")),
    RIPGREP("ripgrep", "ripgrep", listOf("rg")),
    STARSHIP("starship", "Starship", listOf("starship")),
    YAZI("yazi", "yazi", listOf("yazi", "ya")),
}
