package com.landosol.toolbox.labyrinth

/** Shared execution budgets; values are unchanged from the compatibility baseline. */
internal object LabyrinthExecutionLimits {

        const val CHANNEL_PROMPT_POLL_MILLIS = 500L
        /** How long one sighting keeps frame actions away from the dialog; > one poll interval. */
        const val CHANNEL_PROMPT_HOLD_MILLIS = 1_500L
        const val NODE_LOG_TAG = "LabyrinthNode"
        const val TEAM_RECOMMENDATION_LOG_TAG = "LabyrinthTeamPlan"
        const val TEAM_SELECTION_PLAN_LOG_TAG = "LabyrinthTeamSelection"
        const val OWNER = "labyrinth-entry-recognition"

        // Matches the capture cadence (MediaProjectionCaptureService.FRAME_INTERVAL_MILLIS = 300).
        // Frames land on 0/300/600/900..., so the old 500 ms gate accepted 0, dropped 300, accepted
        // 600 - pinning the recognition cadence at 600 ms while capture kept producing a 300 ms
        // frame that was thrown away. 300 keeps every frame capture already paid to convert.
        const val FRAME_INTERVAL_MILLIS = 300L
        const val FIRST_FRAME_TIMEOUT_MILLIS = 5_000L
        const val NODE_CLICK_STABLE_FRAMES = 3
        /** How long a strong FULL_COLUMN target rect outranks weaker re-bindings without a gesture. */
        const val NODE_STRONG_BINDING_MEMORY_MILLIS = 8_000L
        const val NODE_CLICK_INTERVAL_MILLIS = 2_500L
        const val NODE_ENTRY_CONFIRMATION_TIMEOUT_MILLIS = 6_000L
        const val NODE_MOVE_CONFIRMATION_APPEAR_TIMEOUT_MILLIS = 6_000L
        const val NODE_MOVE_CONFIRMATION_RETRY_INTERVAL_MILLIS = 2_000L
        const val NODE_MOVE_CONFIRMATION_STABLE_FRAMES = 2
        /** Consecutive destination frames that must disagree with the clicked type before stopping. */
        const val NODE_ENTRY_MISMATCH_STABLE_FRAMES = 3
        const val MAX_NODE_MOVE_CONFIRMATION_ATTEMPTS = 3
        const val MAX_NODE_TAP_ATTEMPTS = 3
        const val MAX_NODE_SCROLL_ATTEMPTS = 6
        const val NODE_CONFLICT_NUDGE_STREAK = 4
        const val NODE_WAIT_NUDGE_STREAK = 8
        const val NODE_RECOVERY_NUDGE_INTERVAL_MILLIS = 1_500L
        /** Full scan+nudge rounds before the node is declared unrecognisable. */
        const val MAX_NODE_SEARCH_REARMS = 3
        const val NODE_SCROLL_CURRENT_VIEW_STABLE_FRAMES = 5
        const val NODE_SCROLL_CURRENT_VIEW_MIN_WAIT_MILLIS = 1_500L
        const val NODE_TARGET_VISIBLE_MAX_WAIT_FRAMES = 12
        const val NODE_TARGET_VISIBLE_MAX_WAIT_MILLIS = 6_000L
        const val MAX_NODE_ERROR_STREAK = 20
        const val POST_ENTRY_STABLE_FRAMES = 2
        const val POST_ENTRY_ACTION_INTERVAL_MILLIS = 1_500L

        const val MAX_EVENT_CHOICE_COMMIT_ATTEMPTS = 3
        /** Lost-tap retries allowed on a single-pick choice page before the run stops. */
        const val MAX_SELECTION_COMMIT_ATTEMPTS = 3
        const val SHOP_PURCHASE_TRANSITION_TIMEOUT_MILLIS = 8_000L
        const val SHOP_REFRESH_TRANSITION_TIMEOUT_MILLIS = 8_000L
        const val SHOP_REFRESH_SETTLE_MILLIS = 500L
        const val CHARACTER_ACQUISITION_ACTION_INTERVAL_MILLIS = 650L
        const val NODE_MAP_SETTLE_AFTER_POST_ACTION_MILLIS = 2_000L
        const val MAX_POST_ENTRY_ATTEMPTS = 6
        const val BATTLE_TEAM_CHARACTER_FEEDBACK_TIMEOUT_MILLIS = 4_000L
        /**
         * 「编组页动作派发后，画面还允许原地不动多久」。
         *
         * 取值 4 秒的依据：必须**大于** [LabyrinthBattleWaitPolicy] 自己的 3 秒「注入后可能还是旧编组页」
         * 宽限，否则一次慢一点的正常切换会被误判成「没被收下」；实机帧率约 600ms 一帧，4 秒保证了宽限
         * 过后至少还有一帧可判；而真正被吞掉的那一下在实机上是十几秒原地不动，4 秒离它很远。
         * 总的可重试次数由 [MAX_BATTLE_TEAM_ACTION_UNCONFIRMED] 兜底。
         */
        const val BATTLE_TEAM_ACTION_ACK_TIMEOUT_MILLIS = 4_000L
        /** 连续多少次「编组页动作派发后画面没动」就停机（每次之间都重新规划并重发，不是静默等待）。 */
        const val MAX_BATTLE_TEAM_ACTION_UNCONFIRMED = 3
        const val MAX_BATTLE_TEAM_STEP_ATTEMPTS = 3
        const val MAX_BATTLE_TEAM_SCROLL_ACTIONS = 48
        const val MAX_EFFECTIVE_SCAN_SCROLL_ACTIONS = 48
        const val EFFECTIVE_SCAN_SCROLL_DURATION_MILLIS = 360L
        const val EFFECTIVE_SCAN_UNSAFE_SETTLE_MILLIS = 1_500L
        val EFFECTIVE_SCAN_CONTEXT_IDS = listOf("__effective-effect-scan__")
        const val EX_ENCOUNTER_OPEN_DETAIL_TIMEOUT_MILLIS = 5_000L
        const val EX_ENCOUNTER_DETAIL_TIMEOUT_MILLIS = 10_000L
        const val EX_ENCOUNTER_SINGLE_NAME_TIMEOUT_MILLIS = 10_000L
        const val MAX_EX_SLOT3_PROBE_ATTEMPTS = 2
        const val MAX_EVENT_ACTIONS = 16
        const val MAX_CHARACTER_ACQUISITION_CLICKS = 40
        const val CHARACTER_ACQUISITION_TIMEOUT_MILLIS = 30_000L
        const val MAX_POST_BOSS_UNKNOWN_ATTEMPTS = 40
        const val FINAL_HOME_RETURN_TIMEOUT_MILLIS = 20_000L
        const val UNKNOWN_POST_ENTRY_TIMEOUT_MILLIS = 30_000L
        const val POST_ENTRY_SELECTION_MIN_SCORE = 0.45
        const val POST_ENTRY_ANCHOR_MIN_SCORE = 0.45
        const val SESSION_BLOCK_STABLE_FRAMES = 2
        const val SESSION_RETURN_TITLE_ACTION_INTERVAL_MILLIS = 1_500L
        const val EVENT_FREE_ROLE_INVITE_DISABLED_MIN_SCORE = 0.72
        const val MAX_SESSION_RETURN_TITLE_ATTEMPTS = 6
        const val DEBUG_NODE_BOX_MARGIN_X_PX = 36
        const val DEBUG_NODE_BOX_MARGIN_Y_PX = 48
        const val DEBUG_CLICK_MARKER_SIZE_PX = 56
    }
