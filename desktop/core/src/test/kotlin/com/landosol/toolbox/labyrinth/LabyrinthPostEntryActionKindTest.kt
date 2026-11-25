package com.landosol.toolbox.labyrinth

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LabyrinthPostEntryActionKindTest {
    /**
     * Phase two acceptance: the session must never branch on the human-readable label. Any
     * `label.startsWith`, `label ==` or `when (label)` in the session source reintroduces the
     * copy-coupled state machine this refactor removed.
     */
    @Test fun `session source never compares a dispatched label against UI copy`() {
        val source = File("src/main/kotlin/com/landosol/toolbox/labyrinth/LabyrinthEntryRecognitionSession.kt").readText()
        val offenders = source.lines().withIndex().filter { (_, line) ->
            Regex("""label\.(startsWith|endsWith|contains|equals)\(\s*"""").containsMatchIn(line) ||
                Regex("""label\s*==\s*"""").containsMatchIn(line) ||
                Regex("""when\s*\(\s*label\s*\)""").containsMatchIn(line)
        }
        assertTrue(
            "label string comparisons must key on LabyrinthPostEntryActionKind instead:\n" +
                offenders.joinToString("\n") { (i, l) -> "  ${i + 1}: ${l.trim()}" },
            offenders.isEmpty(),
        )
    }
}
