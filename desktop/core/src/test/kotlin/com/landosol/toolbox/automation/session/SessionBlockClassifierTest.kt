package com.landosol.toolbox.automation.session

import com.landosol.toolbox.labyrinth.vision.LabyrinthAnchorScores
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBlockClassifierTest {
    private val classifier = SessionBlockClassifier()

    private fun scores(
        relogin: Double = 0.0,
        reconnect: Double = 0.0,
        prompt: Double = 0.0,
    ) = SessionBlockAnchorScores(relogin = relogin, reconnect = reconnect, prompt = prompt)

    @Test
    fun `all anchors below threshold is none`() {
        assertEquals(SessionBlockKind.NONE, classifier.classify(scores(0.3, 0.2, 0.1), pageTitleScore = 0.0))
    }

    @Test
    fun `relogin anchor dominates`() {
        assertEquals(
            SessionBlockKind.RELOGIN_REQUIRED,
            classifier.classify(scores(relogin = 0.9, reconnect = 0.2, prompt = 0.1), pageTitleScore = 0.3),
        )
    }

    @Test
    fun `reconnect anchor dominates`() {
        assertEquals(
            SessionBlockKind.RECONNECT_PROMPTED,
            classifier.classify(scores(relogin = 0.2, reconnect = 0.9, prompt = 0.1), pageTitleScore = 0.3),
        )
    }

    @Test
    fun `toSessionBlockScores reads new error title key`() {
        val mapped = LabyrinthAnchorScores(
            mapOf(
                "session.error.title" to 0.9,
                "session.return.title" to 0.8,
            ),
        ).toSessionBlockScores()
        assertEquals(SessionBlockAnchorScores(0.0, 0.9, 0.0), mapped)
    }

    @Test
    fun `ambiguous anchors become unknown prompt`() {
        assertEquals(
            SessionBlockKind.UNKNOWN_PROMPT,
            classifier.classify(scores(relogin = 0.85, reconnect = 0.82, prompt = 0.1), pageTitleScore = 0.3),
        )
    }

    @Test
    fun `strong page title overrides weak block signals`() {
        assertEquals(
            SessionBlockKind.NONE,
            classifier.classify(scores(relogin = 0.6, reconnect = 0.2, prompt = 0.1), pageTitleScore = 0.9),
        )
    }

    @Test
    fun `toSessionBlockScores maps missing templates to zero`() {
        val mapped = LabyrinthAnchorScores(mapOf("home.adventure" to 0.8)).toSessionBlockScores()
        assertEquals(SessionBlockAnchorScores(0.0, 0.0, 0.0), mapped)
    }

    @Test
    fun `toSessionBlockScores reads session anchors`() {
        val mapped = LabyrinthAnchorScores(
            mapOf(
                "session.relogin" to 0.9,
                "session.error.title" to 0.2,
                "session.prompt" to 0.1,
            ),
        ).toSessionBlockScores()
        assertEquals(SessionBlockAnchorScores(0.9, 0.2, 0.1), mapped)
    }
}
