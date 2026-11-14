package com.landosol.toolbox.labyrinth.vision

import com.landosol.toolbox.clanbattle.recognition.PixelImage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import org.junit.Assert.*
import org.junit.Test

class Batch5FastFrameObserverTest {
    @Test fun committedPageMapRoleAndModalFixturesUseTheFullPageMath() {
        val templates = Batch5FrameFixtures.templates()
        val fixtures = listOf(
            "date-change-popup-20260910.jpg",
            "final-boss-active-20260909.jpg",
            "opening-dawn-star-1088-20261107.jpg",
            "guild-bonus-joined-20260917.png",
            "boss-team-editor-20260909.jpg",
            "event-single-choice-fishing-20260911.png",
            "event-move-confirmation-20260913.png",
            "battle-failure-two-button-20261004.jpg",
            "relic-effect-result-20260922.png",
            "series-effect-panel-20261012.jpg",
        )
        for (fixture in fixtures) {
            val pixels = Batch5FrameFixtures.fixture(fixture)
            val full = LabyrinthEntryFrameProcessor(templates).process(pixels)
            val fast = LabyrinthFastFrameObserver(templates).process(pixels, 42L, 1234L)
            assertEquals("fixture=$fixture", full.observation, fast.observation)
            assertEquals(full.anchorMatches, fast.anchorMatches)
            assertEquals(full.nodeMoveConfirmation, fast.nodeMoveConfirmation)
            assertEquals(full.battleFailure, fast.battleFailure)
            assertEquals(full.battleEndConfirmation, fast.battleEndConfirmation)
            assertEquals(full.specialEquipment, fast.specialEquipment)
            assertEquals(full.exChallenge, fast.exChallenge)
            assertEquals(42L, fast.frameSequence)
            assertEquals(1234L, fast.timestampMillis)
            assertEquals(pixels.width, fast.width)
            assertEquals(pixels.height, fast.height)
        }
    }

    @Test fun fastProducesNoDetailedPayloadAndNeverConstructsOrInvokesHeavyRecognition() {
        val text = Batch5FrameFixtures.source("labyrinth/vision/LabyrinthFastFrameObserver.kt")
        val androidText = Batch5FrameFixtures.source("labyrinth/vision/AndroidLabyrinthFastFrameObserver.kt")
        val forbidden = listOf("LabyrinthEntryFrameProcessor", "LabyrinthNodeClassifier",
            "FinalBossPlatformLocator", "LabyrinthCharacterRecognizer", "LabyrinthBattleTeamRecognizer",
            "LabyrinthLinkChoiceRecognizer", "LabyrinthRelicRecognizer", "LabyrinthShopRecognizer")
        forbidden.forEach { name ->
            assertFalse("T2 must not construct $name", Regex("$name\\s*\\(").containsMatchIn(text + androidText))
        }
        assertFalse(androidText.contains("Resolver("))
        assertFalse(androidText.contains(".resolve("))
        val fields = LabyrinthFastFrameObservation::class.java.declaredFields.map { it.name }
        listOf("nodeClassifications", "characterMatches", "openingCharacterMatches", "battleTeamSelection",
            "linkChoiceSelection", "relicChoiceSelection", "shopObservation", "eventOcrDiagnostics",
            "nodeRelicStackObservation", "relicDetailObservation").forEach { assertFalse(fields.contains(it)) }
        assertTrue(text.contains("private val matcher = GradientTemplateMatcher()"))
        assertFalse(text.contains("sharePreparedFrame("))
    }

    @Test fun simultaneousFastAndFullDoNotShareMutablePreparedMatcherArrays() {
        val templates = Batch5FrameFixtures.templates()
        val first = Batch5FrameFixtures.fixture("final-boss-active-20260909.jpg")
        val second = Batch5FrameFixtures.fixture("date-change-popup-20260910.jpg")
        val observer = LabyrinthFastFrameObserver(templates)
        val expected = observer.process(first).observation
        val processor = LabyrinthEntryFrameProcessor(templates)
        val gate = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val fastThread = Thread {
            try { gate.await(); repeat(3) { assertEquals(expected, observer.process(first).observation) } }
            catch (error: Throwable) { errors.add(error) }
        }
        val fullThread = Thread {
            try { gate.await(); repeat(3) { processor.process(second) } }
            catch (error: Throwable) { errors.add(error) }
        }
        fastThread.start(); fullThread.start(); gate.countDown()
        fastThread.join(60_000); fullThread.join(60_000)
        assertFalse("fast did not quiesce", fastThread.isAlive)
        assertFalse("full did not quiesce", fullThread.isAlive)
        assertTrue("errors=$errors", errors.isEmpty())
    }

    @Test fun orientationAndPrescalingFollowOriginalFullCore() {
        val templates = Batch5FrameFixtures.templates()
        val portrait = PixelImage(20, 40, IntArray(800))
        val fullPortrait = LabyrinthEntryFrameProcessor(templates).process(portrait)
        val fastPortrait = LabyrinthFastFrameObserver(templates).process(portrait)
        assertEquals(fullPortrait.observation, fastPortrait.observation)
        assertEquals("entry-frame-not-landscape", fastPortrait.observation.reason)
        assertTrue(fastPortrait.anchorMatches.isEmpty())
        val original = Batch5FrameFixtures.fixture("final-boss-active-20260909.jpg")
        val scaled = PixelImageScaler.scale(original, 1280, 720)
        val prescaled = EntryTemplatePrescaler.build(templates, 1280, 720)
        val full = LabyrinthEntryFrameProcessor(templates, preScaledTemplates = prescaled).process(scaled)
        val fast = LabyrinthFastFrameObserver(templates, prescaled).process(scaled)
        assertEquals(full.observation, fast.observation)
        assertEquals(full.anchorMatches, fast.anchorMatches)
        // A stale prescaler must fall back to the original mapping, not guess a geometry.
        assertEquals(LabyrinthEntryFrameProcessor(templates).process(original).observation,
            LabyrinthFastFrameObserver(templates, prescaled).process(original).observation)
    }
}
