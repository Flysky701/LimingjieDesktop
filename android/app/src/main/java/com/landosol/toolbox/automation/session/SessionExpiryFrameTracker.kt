package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState

/**
 * 装配层共享的最新识别快照：workflow 的帧回调写入，SessionExpiry 终止器的
 * 弹窗/标题检测与坐标映射读取。跨对象共享识别结果，避免重复注册截图帧。
 */
class SessionExpiryFrameTracker {
    @Volatile
    private var scores: Map<String, Double> = emptyMap()
    @Volatile
    private var matches: Map<String, EntryAnchorMatch> = emptyMap()
    @Volatile
    private var pageState: LabyrinthEntryPageState = LabyrinthEntryPageState.UNKNOWN
    @Volatile
    private var battleFailureEndPoint: ScreenPoint? = null
    @Volatile
    private var battleEndConfirmationPoint: ScreenPoint? = null
    @Volatile
    private var nodeMoveConfirmationPoint: ScreenPoint? = null
    @Volatile
    private var errorPromptTextDetected: Boolean = false
    @Volatile
    private var width: Int = 0
    @Volatile
    private var height: Int = 0

    /** 由 workflow 的帧处理器在每次识别后调用 */
    fun record(result: LabyrinthEntryFrameResult) {
        scores = result.observation.anchorScores.values
        matches = result.anchorMatches
        pageState = result.observation.state
        battleFailureEndPoint = result.battleFailure?.endButtonRect?.let(::centre)
        battleEndConfirmationPoint = result.battleEndConfirmation?.advanceButtonRect?.let(::centre)
        nodeMoveConfirmationPoint = result.nodeMoveConfirmation?.confirmButtonRect?.let(::centre)
        errorPromptTextDetected = result.shopRefreshDialog?.isErrorPrompt == true
    }

    fun reset() {
        scores = emptyMap()
        matches = emptyMap()
        pageState = LabyrinthEntryPageState.UNKNOWN
        battleFailureEndPoint = null
        battleEndConfirmationPoint = null
        nodeMoveConfirmationPoint = null
        errorPromptTextDetected = false
        width = 0
        height = 0
    }

    private fun centre(rect: EntryPixelRect) = ScreenPoint(
        x = rect.left + rect.width / 2f,
        y = rect.top + rect.height / 2f,
    )

    /**
     * 会话失效触发点：来自已识别页面的触发控件或当前帧中已识别的确认弹窗。
     *
     * - 黎明界主页：底栏「我的主页」标签模板。点已选中的「冒险」不联网（2026-09-17 实测），
     *   「我的主页」会请求主页数据，旧 enter 被拒即弹会话失效提示；
     * - 战斗失败页：没有底栏。「重新挑战」只回到 EX 挑战页、不联网（2026-09-17 实测），所以走
     *   结束 → 撤退（无报酬） → 确认 三步：确认才向服务端撤退，旧 enter 被拒即弹会话失效提示。
     *   每一步都按当前帧识别到的对话框阶段给出按钮，识别不到不点；
     * - 编组页 / 挑战页 / 地图页：编组页点「取消」回到挑战页，挑战页点「查看地图」打开地图视图，
     *   地图页优先走「撤退（无报酬）」→「确认」，也可用「返回」回黎明界主页。
     */
    val sessionInvalidationTrigger: ScreenPoint?
        get() = when (pageState) {
            LabyrinthEntryPageState.BATTLE_FAILED, LabyrinthEntryPageState.UNKNOWN ->
                battleEndConfirmationPoint
                    ?: nodeMoveConfirmationPoint.takeUnless { explicitSessionErrorPopup }
                    ?: battleFailureEndPoint.takeIf { pageState == LabyrinthEntryPageState.BATTLE_FAILED }
            LabyrinthEntryPageState.BATTLE_TEAM_SELECTION ->
                anchorCenter(EntryAnchorId.BATTLE_TEAM_CANCEL_BUTTON, TRIGGER_MIN_SCORE)
            LabyrinthEntryPageState.BATTLE_CHALLENGE ->
                anchorCenter(EntryAnchorId.BATTLE_CHALLENGE_VIEW_MAP_BUTTON, TRIGGER_MIN_SCORE)
            LabyrinthEntryPageState.NODE_SELECTION,
            LabyrinthEntryPageState.NODE_MAP_VIEW,
            -> battleEndConfirmationPoint
                ?: nodeMoveConfirmationPoint.takeUnless { explicitSessionErrorPopup }
                ?: anchorCenter(EntryAnchorId.NODE_RETREAT_STANDARD, TRIGGER_MIN_SCORE)
                ?: anchorCenter(EntryAnchorId.NODE_RETREAT, TRIGGER_MIN_SCORE)
                ?: anchorCenter(EntryAnchorId.NODE_RETURN_STANDARD, TRIGGER_MIN_SCORE)
                ?: anchorCenter(EntryAnchorId.NODE_RETURN, TRIGGER_MIN_SCORE)
            LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE,
            LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE,
            -> anchorCenter(EntryAnchorId.DAWN_HOME_MY_HOME_TAB, TRIGGER_MIN_SCORE)
            else -> null
        }

    /** 记录实际帧尺寸，供坐标映射与支持性判断 */
    fun trackFrame(w: Int, h: Int) {
        width = w
        height = h
    }

    val frameSize: Pair<Int, Int> get() = width to height

    private val explicitSessionErrorPopup: Boolean
        get() = errorPromptTextDetected ||
            (scores[EntryAnchorId.SESSION_RETURN_TITLE] ?: 0.0) >= ANCHOR_ACTION_MIN_SCORE

    /**
     * 「错误提示」弹窗出现：标题条锚点达到置信度门槛，或 OCR 读到错误提示文案。
     *
     * 「结束确认」和地图上的双按钮确认框也可能命中蓝色标题条。
     * 弹窗使背景变暗时，确认框仍可识别，而底层页面可能被判为 UNKNOWN。
     */
    val popupVisible: Boolean
        get() = battleEndConfirmationPoint == null &&
            (nodeMoveConfirmationPoint == null || explicitSessionErrorPopup) &&
            ((scores[EntryAnchorId.SESSION_ERROR_TITLE] ?: 0.0) >= POPUP_MIN_SCORE || errorPromptTextDetected)

    /** 已回到标题页（点击屏幕开始游戏） */
    val titleReached: Boolean
        get() = pageState == LabyrinthEntryPageState.TITLE_WAITING_TAP

    /**
     * The trigger request succeeded instead of being rejected as stale. Reaching the native
     * home after tapping 黎明界's「我的主页」proves the client is already synchronized with the
     * server, so waiting forever for an expiry popup would be both incorrect and destructive.
     */
    val clientSynchronizedWithoutExpiry: Boolean
        get() = pageState == LabyrinthEntryPageState.HOME

    fun anchorCenter(anchorId: String, minScore: Double = ANCHOR_ACTION_MIN_SCORE): ScreenPoint? =
        matches[anchorId]
            ?.takeIf { it.score >= minScore }
            ?.rect
            ?.let { rect ->
                ScreenPoint(
                    x = rect.left + rect.width / 2f,
                    y = rect.top + rect.height / 2f,
                )
            }

    private companion object {
        const val POPUP_MIN_SCORE = 0.55
        const val ANCHOR_ACTION_MIN_SCORE = 0.45
        /** Trigger taps are unconditional network actions; demand a clearly matched template. */
        const val TRIGGER_MIN_SCORE = 0.70
    }
}
