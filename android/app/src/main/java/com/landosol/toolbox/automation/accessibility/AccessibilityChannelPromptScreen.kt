package com.landosol.toolbox.automation.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.landosol.toolbox.automation.session.ChannelPromptBounds
import com.landosol.toolbox.automation.session.ChannelPromptClick
import com.landosol.toolbox.automation.session.ChannelPromptMatch
import com.landosol.toolbox.automation.session.ChannelPromptNode
import com.landosol.toolbox.automation.session.ChannelPromptScreen
import com.landosol.toolbox.automation.session.ChannelPromptWindow
import com.landosol.toolbox.automation.session.resolveChannelPromptClickOwner
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 用无障碍窗口树实现 [ChannelPromptScreen]。
 *
 * 读取：遍历所有交互窗口（服务已声明 flagRetrieveInteractiveWindows），每个窗口拍成只读快照，
 * 节点数和深度都有上限，避免游戏或网页窗口的大树拖慢轮询。
 * 点击：只对重新找到的活节点发 ACTION_CLICK，找不到或位置变了就不点。这里从不发手势，也不做
 * 会话校验——[com.landosol.toolbox.automation.session.ChannelLoginPromptAssistant] 把节点点击和
 * 手势都放进任务自己的 SessionBoundActionExecutor，带任务会话、暂停（手势另有前台包）校验。
 */
class AccessibilityChannelPromptScreen : ChannelPromptScreen {
    override fun windows(): List<ChannelPromptWindow> {
        val service = LandosolAccessibilityService.current() ?: return emptyList()
        return roots(service).mapNotNull { root ->
            val packageName = root.packageName?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val budget = intArrayOf(MAX_NODES_PER_WINDOW)
            ChannelPromptWindow(packageName, snapshot(root, depth = 0, budget = budget))
        }
    }

    override fun foregroundPackage(): String? = LandosolAccessibilityService.foregroundPackage()

    override suspend fun click(match: ChannelPromptMatch): ChannelPromptClick {
        val service = LandosolAccessibilityService.current() ?: return ChannelPromptClick.GONE
        val wanted = match.clickTarget?.bounds ?: return ChannelPromptClick.GONE
        val node = findLiveNode(service, match)
            ?.let { target ->
                resolveChannelPromptClickOwner(
                    start = target,
                    hops = match.clickTargetHops,
                    wanted = wanted,
                    parent = { runCatching { it.parent }.getOrNull() },
                    bounds = { it.boundsInScreen() },
                    clickable = { it.isClickable },
                )
            }
            ?: return ChannelPromptClick.GONE
        val clicked = onMain { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
        return if (clicked) ChannelPromptClick.CLICKED else ChannelPromptClick.REFUSED
    }

    private fun roots(service: LandosolAccessibilityService): List<AccessibilityNodeInfo> =
        runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
            .ifEmpty { listOfNotNull(runCatching { service.rootInActiveWindow }.getOrNull()) }

    /** 快照里不保留 AccessibilityNodeInfo，点击前按包名、文字和位置重新找活节点；必须正好一个。 */
    private fun findLiveNode(service: LandosolAccessibilityService, match: ChannelPromptMatch): AccessibilityNodeInfo? {
        val wanted = match.target.bounds
        return roots(service)
            .filter { it.packageName?.toString() == match.packageName }
            .flatMap { root ->
                runCatching { root.findAccessibilityNodeInfosByText(match.rule.target) }.getOrDefault(emptyList())
            }
            .filter { node -> node.boundsInScreen() == wanted }
            .singleOrNull()
    }

    private fun AccessibilityNodeInfo.boundsInScreen(): ChannelPromptBounds {
        val rect = Rect().also(::getBoundsInScreen)
        return ChannelPromptBounds(rect.left, rect.top, rect.right, rect.bottom)
    }

    private fun snapshot(node: AccessibilityNodeInfo, depth: Int, budget: IntArray): ChannelPromptNode {
        budget[0]--
        val rect = Rect().also(node::getBoundsInScreen)
        val children = if (depth >= MAX_DEPTH) {
            emptyList()
        } else {
            (0 until node.childCount).mapNotNull { index ->
                if (budget[0] <= 0) return@mapNotNull null
                runCatching { node.getChild(index) }.getOrNull()?.let { child -> snapshot(child, depth + 1, budget) }
            }
        }
        return ChannelPromptNode(
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            clickable = node.isClickable,
            bounds = ChannelPromptBounds(rect.left, rect.top, rect.right, rect.bottom),
            children = children,
        )
    }

    private suspend fun onMain(action: () -> Boolean): Boolean =
        suspendCancellableCoroutine { continuation ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (continuation.isActive) continuation.resume(runCatching(action).getOrDefault(false))
            }
        }

    private companion object {
        const val MAX_NODES_PER_WINDOW = 600
        const val MAX_DEPTH = 40
    }
}
