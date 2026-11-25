package com.landosol.toolbox.automation.session

import com.landosol.toolbox.automation.ScreenPoint
import com.landosol.toolbox.labyrinth.vision.EntryAnchorId
import com.landosol.toolbox.labyrinth.vision.EntryAnchorMatch
import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleEndConfirmationObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleEndConfirmationStage
import com.landosol.toolbox.labyrinth.vision.LabyrinthBattleFailureObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryFrameResult
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthEntryPageState
import com.landosol.toolbox.labyrinth.vision.LabyrinthNodeMoveConfirmationObservation
import com.landosol.toolbox.labyrinth.vision.LabyrinthShopRefreshDialogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionExpiryFrameTrackerTest {
    private fun result(
        state: LabyrinthEntryPageState,
        failure: LabyrinthBattleFailureObservation? = null,
        anchors: Map<String, EntryAnchorMatch> = emptyMap(),
        endConfirmation: LabyrinthBattleEndConfirmationObservation? = null,
        moveConfirmation: LabyrinthNodeMoveConfirmationObservation? = null,
    ) = LabyrinthEntryFrameResult(
        observation = LabyrinthEntryPageObservation(
            state = state,
            confidence = 1.0,
            anchorScores = LabyrinthAnchorScores(anchors.mapValues { it.value.score }),
            stateScores = emptyMap(),
        ),
        matchedFeatures = emptyList(),
        elapsedMillis = 0L,
        anchorMatches = anchors,
        battleFailure = failure,
        battleEndConfirmation = endConfirmation,
        nodeMoveConfirmation = moveConfirmation,
    )

    private val tab = EntryAnchorId.DAWN_HOME_MY_HOME_TAB to
        EntryAnchorMatch(score = 0.97, rect = EntryPixelRect(85, 945, 175, 125))

    @Test
    fun `labyrinth home trigger is the recognised 我的主页 tab and nothing when it is weak or absent`() {
        val tracker = SessionExpiryFrameTracker()

        tracker.record(result(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE, anchors = mapOf(tab)))
        val trigger = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(172.5f, trigger.x, 0.01f)
        assertEquals(1007.5f, trigger.y, 0.01f)

        tracker.record(result(LabyrinthEntryPageState.DAWN_REALM_HOME_ACTIVE, anchors = mapOf(tab)))
        assertEquals(trigger, tracker.sessionInvalidationTrigger)

        val weak = tab.first to tab.second.copy(score = 0.5)
        tracker.record(result(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE, anchors = mapOf(weak)))
        assertNull(tracker.sessionInvalidationTrigger)

        // Same anchor score on a page that is not the labyrinth home: never a trigger.
        tracker.record(result(LabyrinthEntryPageState.NODE_SELECTION, anchors = mapOf(tab)))
        assertNull(tracker.sessionInvalidationTrigger)
        tracker.record(result(LabyrinthEntryPageState.UNKNOWN, anchors = mapOf(tab)))
        assertNull(tracker.sessionInvalidationTrigger)
    }

    @Test
    fun `end confirmation dialog is never mistaken for the session expiry popup`() {
        // 2026-09-17 batch 195521: the 结束确认 title bar scored as the expiry popup, so the
        // terminator tapped 返回标题's coordinates, which is 撤退 on that dialog.
        val tracker = SessionExpiryFrameTracker()
        val errorTitle = EntryAnchorId.SESSION_ERROR_TITLE to
            EntryAnchorMatch(score = 0.72, rect = EntryPixelRect(485, 259, 948, 66))
        val choice = LabyrinthBattleEndConfirmationObservation(
            stage = LabyrinthBattleEndConfirmationStage.CHOICE,
            confidence = 0.9,
            advanceButtonRect = EntryPixelRect(835, 700, 255, 90),
        )

        tracker.record(result(LabyrinthEntryPageState.BATTLE_FAILED, anchors = mapOf(errorTitle), endConfirmation = choice))
        assertEquals(false, tracker.popupVisible)
        // The chain keeps driving the dialog instead.
        assertEquals(962.5f, requireNotNull(tracker.sessionInvalidationTrigger).x, 0.01f)

        tracker.record(result(LabyrinthEntryPageState.UNKNOWN, anchors = mapOf(errorTitle)))
        assertEquals(true, tracker.popupVisible)
    }

    @Test
    fun `three-button end confirmation takes priority over node move confirmation`() {
        val tracker = SessionExpiryFrameTracker()
        val choice = LabyrinthBattleEndConfirmationObservation(
            stage = LabyrinthBattleEndConfirmationStage.CHOICE,
            confidence = 0.9,
            advanceButtonRect = EntryPixelRect(835, 700, 255, 90),
        )
        val rightButton = LabyrinthNodeMoveConfirmationObservation(
            confidence = 0.88,
            confirmButtonRect = EntryPixelRect(1120, 700, 265, 90),
            cancelButtonRect = EntryPixelRect(535, 700, 255, 90),
        )

        for (state in listOf(LabyrinthEntryPageState.BATTLE_FAILED, LabyrinthEntryPageState.NODE_SELECTION)) {
            tracker.record(result(state, endConfirmation = choice, moveConfirmation = rightButton))
            assertEquals(ScreenPoint(962.5f, 745f), tracker.sessionInvalidationTrigger)
        }
    }

    @Test
    fun `node selection falls back to the return anchor when retreat is absent`() {
        val tracker = SessionExpiryFrameTracker()
        val returnAnchor = EntryAnchorId.NODE_RETURN_STANDARD to
            EntryAnchorMatch(score = 0.70, rect = EntryPixelRect(1550, 928, 320, 95))

        tracker.record(result(LabyrinthEntryPageState.NODE_SELECTION, anchors = mapOf(returnAnchor)))

        assertEquals(ScreenPoint(1710f, 975.5f), tracker.sessionInvalidationTrigger)
    }

    @Test
    fun `failure page trigger walks 结束 then 撤退 then 确认 by recognised dialog stage`() {
        // 2026-09-17: 重新挑战 only returns to the EX challenge page; the retreat chain is the
        // action that reaches the server.
        val tracker = SessionExpiryFrameTracker()
        val failure = LabyrinthBattleFailureObservation(
            confidence = 0.9,
            endButtonRect = EntryPixelRect(945, 935, 430, 110),
            retryButtonRect = EntryPixelRect(1405, 935, 430, 110),
        )

        tracker.record(result(LabyrinthEntryPageState.BATTLE_FAILED, failure))
        val end = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(1160f, end.x, 0.01f)
        assertEquals(990f, end.y, 0.01f)

        val choice = LabyrinthBattleEndConfirmationObservation(
            stage = LabyrinthBattleEndConfirmationStage.CHOICE,
            confidence = 0.9,
            advanceButtonRect = EntryPixelRect(835, 700, 255, 90),
        )
        tracker.record(result(LabyrinthEntryPageState.BATTLE_FAILED, failure, endConfirmation = choice))
        val retreat = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(962.5f, retreat.x, 0.01f)
        assertEquals(745f, retreat.y, 0.01f)

        // The dimmed page may drop the failure detection; the dialog alone still drives the chain.
        val confirm = LabyrinthBattleEndConfirmationObservation(
            stage = LabyrinthBattleEndConfirmationStage.RETREAT_CONFIRM,
            confidence = 0.9,
            advanceButtonRect = EntryPixelRect(1120, 700, 265, 90),
        )
        tracker.record(result(LabyrinthEntryPageState.UNKNOWN, null, endConfirmation = confirm))
        val confirmPoint = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(1252.5f, confirmPoint.x, 0.01f)

        // A bare UNKNOWN frame with no dialog never taps the (unrecognised) end button.
        tracker.record(result(LabyrinthEntryPageState.UNKNOWN, null))
        assertNull(tracker.sessionInvalidationTrigger)

        tracker.record(result(LabyrinthEntryPageState.DAWN_REALM_HOME_IDLE, null))
        assertNull(tracker.sessionInvalidationTrigger)
    }

    @Test
    fun `team selection and challenge pages walk cancel then view map then retreat confirmation`() {
        val tracker = SessionExpiryFrameTracker()
        val cancel = EntryAnchorId.BATTLE_TEAM_CANCEL_BUTTON to
            EntryAnchorMatch(score = 0.99, rect = EntryPixelRect(1285, 850, 270, 110))
        val viewMap = EntryAnchorId.BATTLE_CHALLENGE_VIEW_MAP_BUTTON to
            EntryAnchorMatch(score = 0.91, rect = EntryPixelRect(975, 850, 440, 125))
        val retreat = EntryAnchorId.NODE_RETREAT_STANDARD to
            EntryAnchorMatch(score = 0.93, rect = EntryPixelRect(1200, 928, 323, 95))
        val errorTitle = EntryAnchorId.SESSION_ERROR_TITLE to
            EntryAnchorMatch(score = 0.70, rect = EntryPixelRect(859, 272, 200, 44))
        val twoButtonConfirm = LabyrinthNodeMoveConfirmationObservation(
            confidence = 0.88,
            confirmButtonRect = EntryPixelRect(975, 690, 415, 105),
            cancelButtonRect = EntryPixelRect(545, 690, 415, 105),
        )

        tracker.record(result(LabyrinthEntryPageState.BATTLE_TEAM_SELECTION, anchors = mapOf(cancel)))
        val cancelPoint = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(1420f, cancelPoint.x, 0.01f)
        assertEquals(905f, cancelPoint.y, 0.01f)

        // Weak challenge anchor never fires a blind tap.
        tracker.record(
            result(
                LabyrinthEntryPageState.BATTLE_CHALLENGE,
                anchors = mapOf(viewMap.first to viewMap.second.copy(score = 0.55)),
            ),
        )
        assertNull(tracker.sessionInvalidationTrigger)

        tracker.record(result(LabyrinthEntryPageState.BATTLE_CHALLENGE, anchors = mapOf(viewMap)))
        val viewMapPoint = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(1195f, viewMapPoint.x, 0.01f)
        assertEquals(912.5f, viewMapPoint.y, 0.01f)

        tracker.record(result(LabyrinthEntryPageState.NODE_MAP_VIEW, anchors = mapOf(retreat)))
        val retreatPoint = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(1361.5f, retreatPoint.x, 0.01f)
        assertEquals(975.5f, retreatPoint.y, 0.01f)

        // Two-button confirmation over NODE_MAP_VIEW / NODE_SELECTION clicks 确认 and suppresses popup false positives.
        tracker.record(
            result(
                LabyrinthEntryPageState.NODE_MAP_VIEW,
                anchors = mapOf(retreat, errorTitle),
                moveConfirmation = twoButtonConfirm,
            ),
        )
        assertEquals(false, tracker.popupVisible)
        val confirmPoint = requireNotNull(tracker.sessionInvalidationTrigger)
        assertEquals(1182.5f, confirmPoint.x, 0.01f)
        assertEquals(742.5f, confirmPoint.y, 0.01f)
    }

    @Test
    fun `map retreat confirmation still wins over the session error title on an unknown page`() {
        val tracker = SessionExpiryFrameTracker()
        val errorTitle = EntryAnchorId.SESSION_ERROR_TITLE to
            EntryAnchorMatch(score = 0.55, rect = EntryPixelRect(859, 272, 200, 44))
        val confirmation = LabyrinthNodeMoveConfirmationObservation(
            confidence = 0.88,
            confirmButtonRect = EntryPixelRect(975, 690, 415, 105),
            cancelButtonRect = EntryPixelRect(545, 690, 415, 105),
        )

        tracker.record(
            result(
                LabyrinthEntryPageState.UNKNOWN,
                anchors = mapOf(errorTitle),
                moveConfirmation = confirmation,
            ),
        )

        assertEquals(false, tracker.popupVisible)
        assertEquals(ScreenPoint(1182.5f, 742.5f), tracker.sessionInvalidationTrigger)
    }

    @Test
    fun `return title anchor distinguishes an expiry popup from a node confirmation`() {
        val tracker = SessionExpiryFrameTracker()
        val errorTitle = EntryAnchorId.SESSION_ERROR_TITLE to
            EntryAnchorMatch(score = 0.55, rect = EntryPixelRect(859, 272, 200, 44))
        val returnTitle = EntryAnchorId.SESSION_RETURN_TITLE to
            EntryAnchorMatch(score = 0.45, rect = EntryPixelRect(756, 692, 410, 94))
        val confirmation = LabyrinthNodeMoveConfirmationObservation(
            confidence = 0.88,
            confirmButtonRect = EntryPixelRect(975, 690, 415, 105),
            cancelButtonRect = EntryPixelRect(545, 690, 415, 105),
        )

        tracker.record(
            result(
                LabyrinthEntryPageState.UNKNOWN,
                anchors = mapOf(errorTitle, returnTitle),
                moveConfirmation = confirmation,
            ),
        )

        assertEquals(true, tracker.popupVisible)
        assertNull(tracker.sessionInvalidationTrigger)
    }

    @Test
    fun `error prompt OCR distinguishes an expiry popup without either session anchor`() {
        val tracker = SessionExpiryFrameTracker()
        val confirmation = LabyrinthNodeMoveConfirmationObservation(
            confidence = 0.88,
            confirmButtonRect = EntryPixelRect(975, 690, 415, 105),
            cancelButtonRect = EntryPixelRect(545, 690, 415, 105),
        )
        val detected = result(LabyrinthEntryPageState.UNKNOWN, moveConfirmation = confirmation)

        for (text in listOf("错 误\n提示", "通信 错误", "重新\n连接", "返回　标题")) {
            val dialog = LabyrinthShopRefreshDialogText.parseRegions(text, 1, null, 0)
            tracker.record(detected.copy(shopRefreshDialog = dialog))

            assertEquals(text, true, dialog.isErrorPrompt)
            assertEquals(text, true, tracker.popupVisible)
            assertNull(text, tracker.sessionInvalidationTrigger)
        }

        tracker.record(detected)
        assertEquals(false, tracker.popupVisible)
        assertEquals(ScreenPoint(1182.5f, 742.5f), tracker.sessionInvalidationTrigger)
    }

    @Test
    fun `reset clears evidence and dimensions from the previous round`() {
        val tracker = SessionExpiryFrameTracker()
        val errorTitle = EntryAnchorId.SESSION_ERROR_TITLE to
            EntryAnchorMatch(score = 0.55, rect = EntryPixelRect(859, 272, 200, 44))
        val dialog = LabyrinthShopRefreshDialogText.parseRegions("错误提示", 1, null, 0)
        tracker.trackFrame(1920, 1080)
        tracker.record(
            result(LabyrinthEntryPageState.TITLE_WAITING_TAP, anchors = mapOf(errorTitle))
                .copy(shopRefreshDialog = dialog),
        )

        assertEquals(true, tracker.titleReached)
        assertEquals(true, tracker.popupVisible)
        assertEquals(ScreenPoint(959f, 294f), tracker.anchorCenter(errorTitle.first))

        tracker.reset()

        assertEquals(0 to 0, tracker.frameSize)
        assertEquals(false, tracker.titleReached)
        assertEquals(false, tracker.popupVisible)
        assertNull(tracker.anchorCenter(errorTitle.first))
        assertNull(tracker.sessionInvalidationTrigger)
    }
}
