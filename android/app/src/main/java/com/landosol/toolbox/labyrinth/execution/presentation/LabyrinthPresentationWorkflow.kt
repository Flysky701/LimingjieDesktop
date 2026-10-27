package com.landosol.toolbox.labyrinth.execution.presentation

import com.landosol.toolbox.labyrinth.*
import com.landosol.toolbox.automation.AutomationSessionId
import com.landosol.toolbox.automation.capture.CaptureFrameBus
import com.landosol.toolbox.automation.capture.CaptureStateRegistry
import com.landosol.toolbox.automation.overlay.AutomationOverlayBox
import com.landosol.toolbox.automation.overlay.AutomationOverlayPresentation
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleTeamRecognitionState
import com.landosol.toolbox.labyrinth.vision.LabyrinthRelicMatch
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopDialogState
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemKind
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopItemStatus
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeSession
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeActionPlanner
import com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes
import kotlinx.coroutines.flow.StateFlow
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.DEBUG_CLICK_MARKER_SIZE_PX
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.DEBUG_NODE_BOX_MARGIN_X_PX
import com.landosol.toolbox.labyrinth.LabyrinthExecutionLimits.DEBUG_NODE_BOX_MARGIN_Y_PX

internal interface LabyrinthPresentationPort {
    val _state: StateFlow<LabyrinthEntryRecognitionSessionState>
    val battleTeamRecommendationUnavailableReason: String?
    val captureActive: () -> Boolean
    val nodeActionPlanner: LabyrinthNodeActionPlanner
    val nodeSession: LabyrinthNodeSession?
    val relicChoicePolicy: LabyrinthRelicChoicePolicy
    val relicFocusMark: LabyrinthRelicMark?
    val relicMarkStackCalibration: Map<LabyrinthRelicMark, Int>
    val relicStackLedger: LabyrinthRelicStackLedger
    val routeNodeCount: Int
    fun currentShopDecision(result: LabyrinthEntryFrameResult): LabyrinthShopDecision?
}

/** 只读展示投影；不接收动作执行端口，也不改变运行状态。 */
internal class LabyrinthPresentationWorkflow(private val port: LabyrinthPresentationPort) : LabyrinthPresentationPort by port {
    fun buildOverlayPresentation(sessionId: AutomationSessionId): AutomationOverlayPresentation {
        val current = _state.value
        val result = current.lastResult
        val pageOwner = result?.observation?.state
            ?.let(::labyrinthPageUiOwner)
            ?: LabyrinthPageUiOwner.NONE
        val detail = buildList {
            if (pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION) {
                if (current.paused) {
                    current.battleTeamSelectionPlan?.overlayLines()?.let(::addAll)
                    current.battleTeamRecommendation?.overlayLines()?.let(::addAll)
                } else {
                    current.battleTeamSelectionPlan?.compactOverlayLines()?.let(::addAll)
                    current.battleTeamRecommendation?.compactOverlayLine()?.let(::add)
                }
                current.battleTeamRecommendationUnavailableReason?.let {
                    add("第一队建议不可用：$it")
                }
            }
            add("原始帧：${current.receivedFrameCount}")
            add("识别帧：${current.frameCount}")
            if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                nodeRelicStackSummary(result)?.let { add("地图遗物：$it") }
            }
            if (!current.dryRun) {
                add("动作：${current.actionCount}")
                add("输入显示器：${CaptureStateRegistry.gestureDisplayId()}")
            }
            if (result == null) {
                add("页面：等待首个游戏画面")
                add("捕获：${if (captureActive()) "运行中" else "已停止"}")
                add("帧消费者：${CaptureFrameBus.currentOwner() ?: "无"}")
                if (!captureActive()) {
                    CaptureStateRegistry.lastStopReason()?.let { add("捕获停止原因：$it") }
                }
                current.message?.takeIf(String::isNotBlank)?.let { add("状态：$it") }
            } else {
                add("页面：${result.observation.state.name}")
                add("置信度：${"%.3f".format(result.observation.confidence)}")
                add("耗时：${result.elapsedMillis} ms")
                if (result.observation.state == LabyrinthEntryPageState.UNKNOWN) {
                    val scores = result.observation.anchorScores
                    val nodeHeader = maxOf(
                        scores[EntryAnchorId.NODE_HEADER],
                        scores[EntryAnchorId.NODE_HEADER_STANDARD],
                    )
                    val nodeLeft = maxOf(
                        scores[EntryAnchorId.NODE_CHARACTERS],
                        scores[EntryAnchorId.NODE_CHARACTERS_STANDARD],
                        scores[EntryAnchorId.NODE_RELICS],
                        scores[EntryAnchorId.NODE_RELICS_STANDARD],
                    )
                    val nodeRight = maxOf(
                        scores[EntryAnchorId.NODE_RETREAT],
                        scores[EntryAnchorId.NODE_RETREAT_STANDARD],
                        scores[EntryAnchorId.NODE_RETURN],
                        scores[EntryAnchorId.NODE_RETURN_STANDARD],
                    )
                    val nodeSelectionScore =
                        result.observation.stateScores[LabyrinthEntryPageState.NODE_SELECTION] ?: 0.0
                    val nodeMapViewScore =
                        result.observation.stateScores[LabyrinthEntryPageState.NODE_MAP_VIEW] ?: 0.0
                    add(
                        "节点诊断：选=${"%.2f".format(nodeSelectionScore)} · " +
                            "看=${"%.2f".format(nodeMapViewScore)} · " +
                            "头=${"%.2f".format(nodeHeader)} · 左=${"%.2f".format(nodeLeft)} · " +
                            "右=${"%.2f".format(nodeRight)}",
                    )
                }
                result.matchedFeatures.take(3).takeIf(List<String>::isNotEmpty)?.let {
                    add("特征：${it.joinToString()}")
                }
                result.nodeClassifications
                    .groupingBy { it.blockType }
                    .eachCount()
                    .entries
                    .sortedBy { it.key }
                    .takeIf { it.isNotEmpty() }
                    ?.let { nodes ->
                        add(
                            "节点：" + nodes.joinToString("、") { (type, count) ->
                                "${LabyrinthNodeTypes.labelOf(type)}×$count"
                            },
                        )
                    }
                result.characterMatches
                    .takeIf(List<*>::isNotEmpty)
                    ?.let { characters ->
                        add(
                            "角色：" + characters.joinToString("、") { character ->
                                character.displayName ?: "未收录"
                            },
                        )
                }
                if (pageOwner == LabyrinthPageUiOwner.CHARACTER_SELECTION) {
                    when (val decision = current.roleRewardChoiceDecision) {
                        is LabyrinthRoleRewardChoiceDecision.Select -> {
                            add(
                                "角色三选一建议：${decision.characterId} ${decision.displayName}" +
                                    if (decision.actionSafe) "" else "（仅推荐）",
                            )
                            decision.safetyNote?.let { add("推荐安全状态：$it") }
                        }
                        is LabyrinthRoleRewardChoiceDecision.Wait ->
                            add("角色三选一等待：${decision.reason}")
                        null -> Unit
                    }
                }
                result.battleTeamSelection?.let { team ->
                    if (team.recognitionState == LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY) {
                        add("编组：${team.currentFilter.label} · 角色列表变化，等待稳定")
                    } else {
                        val recognized = team.recognizedCharacterCount
                        val total = team.visibleCharacters.size + team.selectedCharacters.size
                        add(
                            "编组：${team.currentFilter.label} · 角色 $recognized/$total · " +
                                if (team.scrollbar.canScroll) "可滚动" else "滚动条未测得",
                        )
                        team.selectedCharacters
                            .mapNotNull { it.displayName }
                            .takeIf(List<String>::isNotEmpty)
                            ?.let { add("当前队伍：${it.joinToString("、")}") }
                    }
                }
                result.linkChoiceSelection?.let { link ->
                    add(
                        "连结印记：" + link.choices.joinToString("、") { choice ->
                            val rank = choice.priorityRank.takeIf { it != Int.MAX_VALUE }?.let { "#$it" } ?: "#?"
                            "$rank${choice.element.label}(${"%.2f".format(choice.confidence)})"
                        },
                    )
                    add("属性优先级：${link.priority.label}")
                    link.preferredChoice?.let { preferred ->
                        add(
                            "当前建议：${preferred.slotId.removePrefix("link_choice_")}号卡 · " +
                                "${preferred.element.label}（优先${preferred.priorityRank}）",
                        )
                    }
                }
                result.eventChoiceSelection?.let { event ->
                    add(
                        "事件OCR：${event.event.id} · ${"%.2f".format(event.confidence)}" +
                            " / 差${"%.2f".format(event.rivalMargin)} · 稳定${event.stableFrames}" +
                            if (event.trusted) " · 可信" else " · 待确认",
                    )
                    when (val decision = current.eventChoiceDecision) {
                        is LabyrinthEventChoiceDecision.Select -> add(
                            "事件建议：${decision.choiceId} ${decision.label} · ${"%.2f".format(decision.utility)}" +
                                if (decision.actionSafe) " · 可执行" else " · 仅推荐",
                        )
                        is LabyrinthEventChoiceDecision.Wait -> add("事件等待：${decision.reason}")
                        null -> Unit
                    }
                }
                result.relicChoiceSelection?.let { relics ->
                    add(
                        "迷宫遗物：" + relics.choices.joinToString("、") { relic ->
                            val identity = relic.displayName ?: relic.suspectedDisplayName ?: "未确认"
                            val attribute = relic.attribute ?: relic.suspectedAttribute ?: "未知属性"
                            val bonus = relic.attributeBonus
                                ?.takeIf(String::isNotBlank)
                                ?.let { "+$it" }
                                .orEmpty()
                            val prefix = if (relic.recognized) "" else "疑似"
                            val currentStacks = relic.currentMarkStacks?.let { "/当前$it" }.orEmpty()
                            "$prefix$identity[$attribute$bonus$currentStacks](${"%.2f".format(relic.confidence)}/差${"%.2f".format(relic.rivalMargin)})"
                        },
                    )
                    relicChoicePolicy.choose(
                        acquired = current.observedRelics,
                        candidates = relics.choices,
                        calibratedStacks = relicMarkStackCalibration,
                        currentArea = current.routeProgress?.currentArea,
                        lockedFocus = relicFocusMark,
                    )?.let { decision ->
                        add("当前建议：${decision.choice.displayName ?: "未收录"} · ${decision.reason}")
                    }
                }
                result.nodeRelicStackObservation
                    ?.entries
                    ?.takeIf(List<*>::isNotEmpty)
                    ?.let { entries ->
                        add(
                            "地图遗物层数：" + entries.joinToString("、") { entry ->
                                val mark = entry.attribute ?: "未知"
                                val stacks = entry.stacks?.toString() ?: "?"
                                "$mark$stacks"
                            },
                        )
                    }
                result.shopObservation?.let { shop ->
                    val dialog = shop.dialogState.takeIf { it != LabyrinthShopDialogState.NONE }
                        ?.let { " · 弹窗=${it.name}" }
                        .orEmpty()
                    add(
                            "商店：购买位${shop.items.size} · 可购买${shop.availableItemCount} · " +
                                "按钮识别${shop.recognizedButtonCount}${dialog}",
                    )
                    if (shop.dialogState == LabyrinthShopDialogState.NONE) {
                        add(
                            "商店商品：" + shop.items.joinToString("、") { item ->
                                if (item.kind == LabyrinthShopItemKind.ROLE_IMPRINT) {
                                    "${item.roleImprintLabel ?: "职能印记"}(OCR确认)"
                                } else {
                                    val relic = item.relicMatch
                                    val identity = relic?.displayName ?: relic?.suspectedDisplayName ?: "非遗物/未确认"
                                    val prefix = if (relic?.recognized == true) "" else "疑似"
                                    "$prefix$identity(${"%.2f".format(relic?.confidence ?: 0.0)})"
                                }
                            },
                        )
                        currentShopDecision(result)?.let { decision ->
                            add(
                                "商店策略：" + when (decision) {
                                    is LabyrinthShopDecision.BuyRelic ->
                                        "购买${decision.relicDecision.choice.displayName ?: decision.relicDecision.choice.relicId} · " +
                                            decision.relicDecision.reason
                                    is LabyrinthShopDecision.BuyRoleImprint ->
                                        "购买${decision.label} · ${decision.reason}"
                                    is LabyrinthShopDecision.Refresh -> "刷新 · ${decision.reason}"
                                    is LabyrinthShopDecision.Close -> "关闭 · ${decision.reason}"
                                    is LabyrinthShopDecision.Wait -> "等待 · ${decision.reason}"
                                },
                            )
                        }
                    }
                    shop.purchaseCandidate?.let { candidate ->
                        add(
                            "待购买遗物：${candidate.displayName ?: "未收录"} · " +
                                "${candidate.attribute ?: "未知属性"} · " +
                                "${"%.2f".format(candidate.confidence)}",
                        )
                    }
                }
                current.joinedCharacters
                    .takeIf(List<LabyrinthJoinedCharacter>::isNotEmpty)
                    ?.let { characters ->
                        add("已加入：" + characters.joinToString("、") { it.displayName })
                    }
                if (relicMarkStackCalibration.isNotEmpty()) {
                    add("系列记录：" + relicMarkStackCalibration.entries.joinToString("、") { "${it.key.label}${it.value}" })
                }
                if (relicStackLedger.needsAudit) {
                    add("地图数字与记录冲突，保留已确认值；可打开系列效果一览核对，不阻塞路线")
                }
                current.observedRelics
                    .takeIf(List<LabyrinthObservedRelic>::isNotEmpty)
                    ?.let { relics ->
                        add(
                            "已观察遗物：" + relics.joinToString("、") {
                                "${it.displayName}[${it.attribute}]×${it.observationCount}"
                            },
                        )
                    }
                current.pendingAcquiredCharacterId?.let { add("待确认获得角色：$it") }
                current.routeProgress?.let { progress ->
                    add(
                        "路线：区域${progress.currentArea} · 已走${progress.visitedCount}/${progress.routeNodeCount}" +
                            if (progress.complete) " · 已完成" else "",
                    )
                    if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                        progress.nextNodeLabel?.let { add("下一节点：$it") }
                    }
                }
            }
        }.joinToString("\n")
        return AutomationOverlayPresentation(
            sessionId = sessionId,
            title = if (current.dryRun) "黎明界入口识别" else "黎明界入口流程",
            status = if (current.paused) {
                "已暂停"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamSelectionPlan?.teamReady == true
            ) {
                "第一队已一致 · 准备开始战斗"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamSelectionPlan != null
            ) {
                val plan = current.battleTeamSelectionPlan
                "自动编组 · 保持${plan.alreadyCorrectIds.size}/取消${plan.needDeselectIds.size}/选择${plan.needSelectIds.size}"
            } else if (
                pageOwner == LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION &&
                current.battleTeamRecommendation != null
            ) {
                val recommendation = current.battleTeamRecommendation
                "推荐第一队 · ${recommendation.damageTypeLabel} · ${"%.2f".format(recommendation.score)}"
            } else if (
                pageOwner == LabyrinthPageUiOwner.EVENT_CHOICE &&
                current.eventChoiceDecision is LabyrinthEventChoiceDecision.Select
            ) {
                val decision = current.eventChoiceDecision
                if (decision.actionSafe) {
                    "事件${decision.eventId}建议 · ${decision.label}"
                } else {
                    "事件${decision.eventId}推荐 · ${decision.label} · 仅建议"
                }
            } else if (
                pageOwner == LabyrinthPageUiOwner.CHARACTER_SELECTION &&
                current.roleRewardChoiceDecision is LabyrinthRoleRewardChoiceDecision.Select
            ) {
                val decision = current.roleRewardChoiceDecision
                if (decision.actionSafe) {
                    "角色三选一建议 · ${decision.displayName}"
                } else {
                    "角色三选一推荐 · ${decision.displayName} · 仅建议"
                }
            } else if (pageOwner == LabyrinthPageUiOwner.NODE_SELECTION) {
                val stacks = nodeRelicStackSummary(result)
                when {
                    stacks != null && current.dryRun -> "只读运行中 · 遗物 $stacks"
                    stacks != null -> "点击运行中 · 遗物 $stacks"
                    current.dryRun -> "只读运行中 · 遗物识别中"
                    else -> "点击运行中 · 遗物识别中"
                }
            } else if (current.dryRun) {
                "只读运行中"
            } else {
                "点击运行中"
            },
            detail = detail,
            dryRun = current.dryRun,
            paused = current.paused,
            boxes = buildPageOwnedOverlayBoxes(pageOwner, current, result),
            renderBoxesOnDevice = false,
        )
    }

    fun nodeRelicStackSummary(result: LabyrinthEntryFrameResult?): String? =
        result?.nodeRelicStackObservation
            ?.entries
            ?.takeIf(List<*>::isNotEmpty)
            ?.joinToString(" ") { entry ->
                val mark = entry.attribute ?: "?"
                val stacks = entry.stacks?.toString() ?: "?"
                "$mark$stacks"
            }

    fun buildPageOwnedOverlayBoxes(
        owner: LabyrinthPageUiOwner,
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> = when (owner) {
        LabyrinthPageUiOwner.NODE_SELECTION ->
            buildNextNodeOverlayBoxes(current, result) + buildPausedNodeDiagnosticsBoxes(current, result)
        LabyrinthPageUiOwner.CHARACTER_SELECTION,
        LabyrinthPageUiOwner.CHARACTER_JOINED,
        -> buildCharacterPageOverlayBoxes(current, result)
        LabyrinthPageUiOwner.BATTLE_TEAM_SELECTION -> buildBattleTeamOverlayBoxes(current, result)
        LabyrinthPageUiOwner.LINK_CHOICE -> buildLinkChoiceOverlayBoxes(result)
        LabyrinthPageUiOwner.RELIC_CHOICE -> buildRelicChoiceOverlayBoxes(result)
        LabyrinthPageUiOwner.SHOP -> buildShopOverlayBoxes(result)
        LabyrinthPageUiOwner.EVENT_CHOICE -> buildEventChoiceOverlayBoxes(current, result)
        else -> emptyList()
    }

    fun buildEventChoiceOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val event = result?.eventChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: return emptyList()
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: return emptyList()
        val selectedChoiceId = (current.eventChoiceDecision as? LabyrinthEventChoiceDecision.Select)?.choiceId
        return event.choices.mapNotNull { visual ->
            val rect = visual.buttonRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) return@mapNotNull null
            val selected = visual.choice.id == selectedChoiceId
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = buildString {
                    append(if (selected) "推荐 " else "事件 ")
                    append(visual.choice.id)
                    append(' ')
                    append(visual.choice.name)
                    append(" / 蓝${"%.2f".format(visual.buttonConfidence)}")
                    append(" / 亮蓝${"%.2f".format(visual.enabledConfidence)}")
                },
                recognized = event.trusted && visual.enabledConfidence >= 0.35,
                selected = selected,
                labelAbove = true,
            )
        }
    }

    fun buildCharacterPageOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        result ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val chosenId = (current.roleRewardChoiceDecision as? LabyrinthRoleRewardChoiceDecision.Select)
            ?.characterId
            ?.let(::canonicalLabyrinthRoleId)
        val roleMatches = result.characterMatches.map { match ->
            CharacterOverlaySource(
                characterId = match.characterId,
                suspectedId = match.suspectedCharacterId,
                displayName = match.displayName ?: match.suspectedDisplayName,
                confidence = match.confidence,
                rivalMargin = match.rivalMargin,
                // The reward page has a stricter irreversible-action gate than the shared icon
                // matcher.  Reflect that in the overlay too: a weak shared "trusted" match must
                // still be shown as tentative until the icon is strong or name OCR confirms it.
                trusted = labyrinthRoleRewardIdentityIsActionSafe(match),
                selected = match.characterId?.let(::canonicalLabyrinthRoleId) == chosenId,
                rect = match.screenRect,
                nameEvidenceText = match.nameEvidenceText,
                nameEvidenceScore = match.nameEvidenceScore,
                nameEvidenceMargin = match.nameEvidenceMargin,
                nameAssisted = match.nameAssisted,
            )
        }
        val openingMatches = result.openingCharacterMatches.map { match ->
            CharacterOverlaySource(
                characterId = match.characterId,
                suspectedId = match.suspectedCharacterId,
                displayName = match.displayName ?: match.suspectedDisplayName,
                confidence = match.confidence,
                rivalMargin = match.rivalMargin,
                trusted = match.trusted,
                selected = match.selected,
                rect = match.screenRect,
            )
        }
        return (roleMatches + openingMatches).mapNotNull { source ->
            val rect = source.rect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) return@mapNotNull null
            val identity = source.displayName ?: source.suspectedId ?: "未识别"
            val nameEvidence = source.nameEvidenceText
                ?.replace("\n", "")
                ?.replace("\r", "")
                ?.take(8)
                ?.takeIf(String::isNotBlank)
                ?.let { text ->
                    " / 字${"%.2f".format(source.nameEvidenceScore)}" +
                        " 差${"%.2f".format(source.nameEvidenceMargin)}[$text]"
                }
                .orEmpty()
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = if (source.trusted) {
                    buildString {
                        append(identity)
                        if (source.nameAssisted) append(" ✓字")
                        append(" ${"%.2f".format(source.confidence)} / 差${"%.2f".format(source.rivalMargin)}")
                        append(nameEvidence)
                    }
                } else {
                    "疑似$identity，不点击 ${"%.2f".format(source.confidence)} / 差${"%.2f".format(source.rivalMargin)}$nameEvidence"
                },
                recognized = source.trusted,
                selected = source.selected,
                labelAbove = true,
            )
        }
    }

    /**
     * Show enlarged node diagnostics only after the user pauses the session. The annotation layer
     * is visible to MediaProjection, so drawing these boxes while recognition is running would
     * make the boxes themselves possible node candidates.
     */
    fun buildPausedNodeDiagnosticsBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        if (!labyrinthShowsPausedNodeDiagnostics(current.paused)) return emptyList()
        if (result?.observation?.state != LabyrinthEntryPageState.NODE_SELECTION) return emptyList()

        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val matchResult = nodeSession?.getLastMatchResult()
        val boxes = mutableListOf<AutomationOverlayBox>()
        result.nodeClassifications.forEach { classification ->
            val sourceRect = classification.screenRect ?: return@forEach
            val rect = expandNodeDiagnosticsRect(sourceRect, frameWidth, frameHeight) ?: return@forEach
            val mapping = matchResult?.visibleNodes?.firstOrNull { it.screenRect == sourceRect }
            val conflict = matchResult?.conflicts?.firstOrNull { it.screenRect == sourceRect }
            val topology = matchResult?.topologyMappings?.firstOrNull { it.screenRect == sourceRect }
            val mappingLabel = when {
                conflict != null -> " → TYPE_CONFLICT ${conflict.blockId} " +
                    "路线=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}"
                topology != null -> " → 拓扑 ${topology.blockId} " +
                    "${LabyrinthNodeTypes.labelOf(topology.expectedBlockType)} " +
                    "T=${"%.2f".format(topology.topologyConfidence)}"
                mapping != null -> " → ${mapping.blockId} ${LabyrinthNodeTypes.labelOf(mapping.blockType)}"
                else -> " → 未映射"
            }
            toNodeDiagnosticsOverlayBox(
                rect = rect,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                label = "候选 ${classification.column}/${classification.row} 视觉=" +
                    "${LabyrinthNodeTypes.labelOf(classification.blockType)} " +
                    "类别${"%.2f".format(classification.typeConfidence)} " +
                    "结构${"%.2f".format(classification.confidence)}" +
                    (if (classification.isClickable) " 可点" else " 不可点") +
                    mappingLabel,
                recognized = classification.isClickable,
                selected = conflict != null,
            )?.let(boxes::add)
        }

        matchResult?.conflicts
            ?.filter { conflict ->
                conflict.screenRect != null &&
                    result.nodeClassifications.none { it.screenRect == conflict.screenRect }
            }
            ?.forEach { conflict ->
                val sourceRect = conflict.screenRect ?: return@forEach
                val rect = expandNodeDiagnosticsRect(sourceRect, frameWidth, frameHeight) ?: return@forEach
                toNodeDiagnosticsOverlayBox(
                    rect = rect,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "TYPE_CONFLICT ${conflict.blockId} " +
                        "视觉=${LabyrinthNodeTypes.labelOf(conflict.detectedBlockType)} " +
                        "路线=${LabyrinthNodeTypes.labelOf(conflict.expectedBlockType)}",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }

        val targetRect = current.routeProgress?.nextNodeRect
        val targetLabel = current.routeProgress?.nextNodeLabel
        if (targetRect != null && targetLabel != null) {
            expandNodeDiagnosticsRect(targetRect, frameWidth, frameHeight)?.let { rect ->
                toNodeDiagnosticsOverlayBox(
                    rect = rect,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "目标 $targetLabel",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }

            val (clickX, clickY) = nodeActionPlanner.getBaseClickPosition(targetRect)
            val markerSize = minOf(DEBUG_CLICK_MARKER_SIZE_PX, frameWidth, frameHeight)
            if (markerSize > 0) {
                val marker = EntryPixelRect(
                    left = (clickX - markerSize / 2).coerceIn(0, frameWidth - markerSize),
                    top = (clickY - markerSize / 2).coerceIn(0, frameHeight - markerSize),
                    width = markerSize,
                    height = markerSize,
                )
                toNodeDiagnosticsOverlayBox(
                    rect = marker,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    label = "点击点 $clickX,$clickY",
                    recognized = true,
                    selected = true,
                )?.let(boxes::add)
            }
        }
        return boxes
    }

    fun expandNodeDiagnosticsRect(
        rect: EntryPixelRect,
        frameWidth: Int,
        frameHeight: Int,
    ): EntryPixelRect? {
        if (frameWidth <= 0 || frameHeight <= 0) return null
        val left = (rect.left - DEBUG_NODE_BOX_MARGIN_X_PX).coerceAtLeast(0)
        val top = (rect.top - DEBUG_NODE_BOX_MARGIN_Y_PX).coerceAtLeast(0)
        val right = (rect.left + rect.width + DEBUG_NODE_BOX_MARGIN_X_PX).coerceAtMost(frameWidth)
        val bottom = (rect.top + rect.height + DEBUG_NODE_BOX_MARGIN_Y_PX).coerceAtMost(frameHeight)
        if (right <= left || bottom <= top) return null
        return EntryPixelRect(left, top, right - left, bottom - top)
    }

    fun toNodeDiagnosticsOverlayBox(
        rect: EntryPixelRect,
        frameWidth: Int,
        frameHeight: Int,
        label: String,
        recognized: Boolean,
        selected: Boolean,
    ): AutomationOverlayBox? {
        val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
        val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
        val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
        if (width <= 0f || height <= 0f) return null
        return AutomationOverlayBox(
            left = left,
            top = top,
            width = width,
            height = height,
            label = label,
            recognized = recognized,
            selected = selected,
        )
    }

    fun buildNextNodeOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        // MediaProjection includes this overlay in later frames. During execution a cyan target
        // rectangle would therefore become new node-recognition input and reinforce a bad match.
        if (!labyrinthShowsNextNodeOverlayBox(current.dryRun)) return emptyList()
        val progress = current.routeProgress ?: return emptyList()
        val rect = progress.nextNodeRect ?: return emptyList()
        val label = progress.nextNodeLabel ?: return emptyList()
        val frameWidth = result?.frameWidth?.takeIf { it > 0 } ?: 1920
        val frameHeight = result?.frameHeight?.takeIf { it > 0 } ?: 1080
        val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
        val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
        val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
        val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
        if (width <= 0f || height <= 0f) return emptyList()
        return listOf(
            AutomationOverlayBox(
                left = left,
                top = top,
                width = width,
                height = height,
                label = if (current.dryRun) "将点击：$label" else "下一节点：$label",
                recognized = true,
                selected = true,
            ),
        )
    }

    fun buildBattleTeamOverlayBoxes(
        current: LabyrinthEntryRecognitionSessionState,
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val team = result?.battleTeamSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        val plan = current.battleTeamSelectionPlan
        return (team.visibleCharacters + team.selectedCharacters).mapNotNull { character ->
            val rect = character.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val selectTarget = plan?.visibleSelectTargets?.firstOrNull { it.slotId == character.slotId }
                val deselectTarget = plan?.visibleDeselectTargets?.firstOrNull { it.slotId == character.slotId }
                val unsafeTarget = plan?.unsafeVisibleMatches?.firstOrNull { it.slotId == character.slotId }
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = when {
                        selectTarget != null -> "将选择：${selectTarget.displayName}"
                        deselectTarget != null -> "将取消：${deselectTarget.displayName}"
                        unsafeTarget != null -> "低置信度，不点击：${unsafeTarget.displayName}"
                        else -> character.displayName ?: buildString {
                            append("未确认")
                            character.suspectedDisplayName?.let { append("（疑似$it）") }
                            append(" ${"%.2f".format(character.confidence)} / 差${"%.2f".format(character.rivalMargin)}")
                        }
                    },
                    recognized = character.characterId != null,
                    selected = character.selected,
                    labelAbove = true,
                )
            }
        }
    }

    fun buildLinkChoiceOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val link = result?.linkChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return link.choices.mapNotNull { choice ->
            // Show the same lower selection button that the action executor will tap. The card
            // and badge remain available in the recognition result for diagnostics.
            val rect = choice.selectionButtonRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val rank = choice.priorityRank.takeIf { it != Int.MAX_VALUE }?.let { "优先$it" } ?: "未识别"
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = "${choice.element.label} · $rank · ${"%.2f".format(choice.confidence)}",
                    recognized = choice.recognized,
                    selected = false,
                )
            }
        }
    }

    fun buildRelicChoiceOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val relics = result?.relicChoiceSelection ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return relics.choices.mapNotNull { relic ->
            val rect = relic.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val identity = relic.displayName ?: relic.suspectedDisplayName ?: "未确认"
                val attribute = relic.attribute ?: relic.suspectedAttribute ?: "未知属性"
                val currentStacks = relic.currentMarkStacks?.let { " · 当前$it" }.orEmpty()
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = if (relic.recognized) {
                        "$identity · $attribute$currentStacks · ${"%.2f".format(relic.confidence)} / 差${"%.2f".format(relic.rivalMargin)}"
                    } else {
                        "疑似$identity · $attribute$currentStacks · ${"%.2f".format(relic.confidence)} / 差${"%.2f".format(relic.rivalMargin)}"
                    },
                    recognized = relic.recognized,
                    selected = false,
                )
            }
        }
    }

    fun buildShopOverlayBoxes(
        result: LabyrinthEntryFrameResult?,
    ): List<AutomationOverlayBox> {
        val shop = result?.shopObservation ?: return emptyList()
        val frameWidth = result.frameWidth.takeIf { it > 0 } ?: 1920
        val frameHeight = result.frameHeight.takeIf { it > 0 } ?: 1080
        return shop.items.mapNotNull { item ->
            val rect = item.screenRect
            val left = (rect.left.toFloat() / frameWidth).coerceIn(0f, 1f)
            val top = (rect.top.toFloat() / frameHeight).coerceIn(0f, 1f)
            val width = (rect.width.toFloat() / frameWidth).coerceAtMost(1f - left)
            val height = (rect.height.toFloat() / frameHeight).coerceAtMost(1f - top)
            if (width <= 0f || height <= 0f) {
                null
            } else {
                val status = when (item.status) {
                    LabyrinthShopItemStatus.AVAILABLE -> "可购买"
                    LabyrinthShopItemStatus.DISABLED -> "不可购买/余额不足"
                    LabyrinthShopItemStatus.UNKNOWN -> "按钮未确认"
                }
                val relic = item.relicMatch?.takeIf(LabyrinthRelicMatch::recognized)
                val relicLabel = relic?.displayName?.let { " · $it" }.orEmpty()
                AutomationOverlayBox(
                    left = left,
                    top = top,
                    width = width,
                    height = height,
                    label = "${item.slotId.removePrefix("shop_item_")}号商品$relicLabel · $status · " +
                        "模板${"%.2f".format(item.buyButtonScore)} / 可用态${"%.2f".format(item.buyButtonEnabledEvidence)}",
                    recognized = item.purchasable,
                    selected = false,
                )
            }
        }
    }
}
