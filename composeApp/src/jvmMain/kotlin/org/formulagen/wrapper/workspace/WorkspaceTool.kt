package org.formulagen.wrapper.workspace

/**
 * The workspace catalog's tools and the commands each provides (project-workspace.ARCH.md, "Tool
 * catalog"). The tool manager adds the pinned `catalog.json` beside it.
 */
enum class WorkspaceTool(val id: String, val commands: List<String>) {
    DELTA("delta", listOf("delta")), FD("fd", listOf("fd")), FZF("fzf", listOf("fzf")),
    LAZYGIT("lazygit", listOf("lazygit")), NEOVIM("neovim", listOf("nvim")), RIPGREP("ripgrep", listOf("rg")),
    STARSHIP("starship", listOf("starship")), YAZI("yazi", listOf("yazi", "ya")),
}
