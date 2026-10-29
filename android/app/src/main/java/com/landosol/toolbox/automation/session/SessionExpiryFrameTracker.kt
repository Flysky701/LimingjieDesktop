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
    }

    private fun centre(rect: EntryPixelRect) = ScreenPoint(
        x = rect.left + rect.width / 2f,
        y = rect.top + rect.height / 2f,
    )

    /**
     * 会话失效触发点：只在当前帧识别为已知页面、且该页的触发控件被模板命中时返回。
     * 任何其他页面都返回 null，终止器因此不会点击（此前用固定坐标点底栏，在别的页面也会点）。
     *
     * - 黎明界主页：底栏「我的主页」标签模板。点已选中的「冒险」不联网（2026-09-17 实测），
     *   「我的主页」会请求主页数据，旧 enter 被拒即弹会话失效提示；
     * - 战斗失败页：没有底栏。「重新挑战」只回到 EX 挑战页、不联网（2026-09-17 实测），所以走
     *   结束 → 撤退（无报酬） → 确认 三步：确认才向服务端撤退，旧 enter 被拒即弹会话失效提示。
     *   每一步都按当前帧识别到的对话框阶段给出按钮，识别不到不点。
     */
    val sessionInvalidationTrigger: ScreenPoint?
        get() = when (pageState) {
            LabyrinthEntryPageState.BATTLE_FAILED, LabyrinthEntryPageState.UNKNOWN ->
                battleEndConfirmationPoint ?: battleFailureEndPoint.takeIf { pageState == LabyrinthEntryPageState.BATTLE_FAILED }
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

    /**
     * 「错误提示」弹窗出现：标题条锚点达到置信度门槛。
     *
     * 失败页上的「结束确认」对话框用同一种蓝色标题条，位置也几乎重合，标题条锚点同样过线
     * （2026-09-17 批次 195521：点「结束」后立刻判为弹窗，把「返回标题」的坐标点在了中间的
     * 「撤退」上，随后卡在确认页）。只要当前帧结构上识别为结束确认对话框，就不是失效弹窗。
     */
    val popupVisible: Boolean
        get() = battleEndConfirmationPoint == null &&
            (scores[EntryAnchorId.SESSION_ERROR_TITLE] ?: 0.0) >= POPUP_MIN_SCORE

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
