package com.landosol.toolbox.desktop.shared

import com.landosol.toolbox.automation.session.*

/** Uses the upstream click-owner rule on a fresh device tree, never a retained node handle. */
class DesktopChannelPromptScreen(
    private val readWindows: () -> List<ChannelPromptWindow>,
    private val foreground: () -> String?,
    private val tap: suspend (Float, Float, String) -> Boolean,
) : ChannelPromptScreen {
    override fun windows(): List<ChannelPromptWindow> = readWindows()
    override fun foregroundPackage(): String? = foreground()
    override suspend fun click(match: ChannelPromptMatch): ChannelPromptClick {
        val wanted = match.clickTarget?.bounds ?: return ChannelPromptClick.GONE
        val candidates = readWindows().filter { it.packageName == match.packageName }
            .flatMap { it.root.walkWithAncestors().toList() }
            .filter { (node, _) -> node.bounds == match.target.bounds && node.labels.any { it.contains(match.rule.target) } }
        val (target, ancestors) = candidates.singleOrNull() ?: return ChannelPromptClick.GONE
        val chain = ancestors + target
        val owner = resolveChannelPromptClickOwner(target, match.clickTargetHops, wanted,
            parent = { node -> chain.indexOfFirst { it === node }.takeIf { it > 0 }?.let { chain[it - 1] } },
            bounds = { it.bounds }, clickable = { it.clickable }) ?: return ChannelPromptClick.GONE
        return if (tap(owner.bounds.centerX, owner.bounds.centerY, match.packageName)) ChannelPromptClick.CLICKED else ChannelPromptClick.REFUSED
    }
}
