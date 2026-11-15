package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.labyrinth.vision.EntryPixelRect
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.*
import org.junit.Test

class EventChainActionPermitTest {
    private val key = LabyrinthEventActionKey("session-a", 3L, 7L, "1112", "11122", EntryPixelRect(712, 540, 214, 115))

    @Test fun `capture requires current identity and nonnegative originating timestamp`() {
        val permit = LabyrinthEventActionPermit()
        assertNull(permit.capture(key, 1_000L))
        permit.update(key)
        assertNotNull(permit.capture(key, 0L))
        assertNull(permit.capture(key.copy(choiceId = "11121"), 1_000L))
        assertNull(permit.capture(key, -1L))
        assertNull(permit.capture(key, Long.MIN_VALUE))
        assertTrue(requireNotNull(permit.capture(key, 1_000L)).isCurrent(1_000L))
    }

    @Test fun `unchanged key updates do not revoke a still fresh token`() {
        val permit = LabyrinthEventActionPermit()
        permit.update(key)
        val token = requireNotNull(permit.capture(key, 1_000L))
        repeat(20) { permit.update(key.copy()) }
        assertTrue(token.isCurrent(1_001L))
        assertTrue(token.isCurrent(2_200L))
    }

    @Test fun `every identity or rectangle change revokes the old queued token`() {
        val changes = listOf(
            key.copy(session = "session-b"),
            key.copy(generation = 4L),
            key.copy(sceneEpoch = 8L),
            key.copy(eventId = "2315"),
            key.copy(choiceId = "11121"),
            key.copy(buttonRect = key.buttonRect.copy(left = 713)),
            key.copy(buttonRect = key.buttonRect.copy(top = 541)),
            key.copy(buttonRect = key.buttonRect.copy(width = 215)),
            key.copy(buttonRect = key.buttonRect.copy(height = 116)),
        )
        for (changed in changes) {
            val permit = LabyrinthEventActionPermit()
            permit.update(key)
            val token = requireNotNull(permit.capture(key, 1_000L))
            permit.update(changed)
            assertFalse(token.isCurrent(1_001L))
            assertNull(permit.capture(key, 1_001L))
            assertTrue(requireNotNull(permit.capture(changed, 1_001L)).isCurrent(1_002L))
        }
    }

    @Test fun `A to B to A cannot revive a token from the first A`() {
        val permit = LabyrinthEventActionPermit()
        permit.update(key)
        val first = requireNotNull(permit.capture(key, 1_000L))
        permit.update(key.copy(choiceId = "11121"))
        permit.update(key)
        assertFalse(first.isCurrent(1_001L))
        assertTrue(requireNotNull(permit.capture(key, 1_001L)).isCurrent(1_002L))
    }

    @Test fun `clear revokes old permission even if identical event evidence later returns`() {
        val permit = LabyrinthEventActionPermit()
        permit.update(key)
        val first = requireNotNull(permit.capture(key, 1_000L))
        permit.clear()
        assertFalse(first.isCurrent(1_001L))
        assertNull(permit.capture(key, 1_001L))
        permit.clear()
        permit.update(key)
        assertFalse(first.isCurrent(1_002L))
        assertTrue(requireNotNull(permit.capture(key, 1_002L)).isCurrent(1_003L))
    }

    @Test fun `future clock and exact 1200 millisecond expiry boundaries fail closed`() {
        val permit = LabyrinthEventActionPermit()
        permit.update(key)
        val token = requireNotNull(permit.capture(key, 3_000L))
        assertFalse(token.isCurrent(2_999L))
        assertTrue(token.isCurrent(3_000L))
        assertTrue(token.isCurrent(4_199L))
        assertTrue(token.isCurrent(4_200L))
        assertFalse(token.isCurrent(4_201L))
        assertFalse(token.isCurrent(Long.MIN_VALUE))
        assertFalse(token.isCurrent(Long.MAX_VALUE))
    }

    @Test fun `new evidence with the same key does not extend a previously captured frame deadline`() {
        val permit = LabyrinthEventActionPermit()
        permit.update(key)
        val old = requireNotNull(permit.capture(key, 1_000L))
        permit.update(key.copy())
        val newer = requireNotNull(permit.capture(key, 2_000L))
        assertTrue(old.isCurrent(2_200L))
        assertFalse(old.isCurrent(2_201L))
        assertTrue(newer.isCurrent(2_201L))
        assertFalse(old.isCurrent(3_200L))
        assertTrue(newer.isCurrent(3_200L))
        assertFalse(newer.isCurrent(3_201L))
    }

    @Test fun `nonnegative Long extrema do not overflow into fresh evidence`() {
        val permit = LabyrinthEventActionPermit()
        permit.update(key)
        val epoch = requireNotNull(permit.capture(key, 0L))
        assertTrue(epoch.isCurrent(0L))
        assertTrue(epoch.isCurrent(1_200L))
        assertFalse(epoch.isCurrent(1_201L))
        assertFalse(epoch.isCurrent(Long.MAX_VALUE))
        val maximum = requireNotNull(permit.capture(key, Long.MAX_VALUE))
        assertFalse(maximum.isCurrent(Long.MIN_VALUE))
        assertFalse(maximum.isCurrent(Long.MAX_VALUE - 1L))
        assertTrue(maximum.isCurrent(Long.MAX_VALUE))
        val nearMaximum = requireNotNull(permit.capture(key, Long.MAX_VALUE - 1_200L))
        assertTrue(nearMaximum.isCurrent(Long.MAX_VALUE))
        assertFalse(nearMaximum.isCurrent(Long.MIN_VALUE))
        val tooOld = requireNotNull(permit.capture(key, Long.MAX_VALUE - 1_201L))
        assertFalse(tooOld.isCurrent(Long.MAX_VALUE))
    }

    @Test fun `revision crossing signed Long maximum still invalidates earlier tokens`() {
        val permit = LabyrinthEventActionPermit()
        permit.update(key)
        val field = permit.javaClass.getDeclaredField("revision").apply { isAccessible = true }
        val revision = field.get(permit) as AtomicLong
        revision.set(Long.MAX_VALUE - 1L)
        val first = requireNotNull(permit.capture(key, 1_000L))
        permit.update(key.copy(choiceId = "11121"))
        assertEquals(Long.MAX_VALUE, revision.get())
        permit.update(key)
        assertEquals(Long.MIN_VALUE, revision.get())
        assertFalse(first.isCurrent(1_001L))
        assertTrue(requireNotNull(permit.capture(key, 1_001L)).isCurrent(1_002L))
    }

    @Test fun `native token contains only one shared atomic and two immutable Long scalars`() {
        val permit = LabyrinthEventActionPermit()
        permit.update(key)
        val token = requireNotNull(permit.capture(key, 1_000L))
        val fields = token.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
        // Include synthetic instance fields: an accidental inner class or closure must fail this.
        assertEquals(3, fields.size)
        assertEquals(1, fields.count { it.type == AtomicLong::class.java })
        assertEquals(2, fields.count { it.type == java.lang.Long.TYPE })
        assertTrue(fields.all { Modifier.isFinal(it.modifiers) })
        assertFalse(fields.any { it.type == LabyrinthEventActionKey::class.java || it.type == EntryPixelRect::class.java })
        val revisionField = permit.javaClass.getDeclaredField("revision").apply { isAccessible = true }
        val tokenAtomic = fields.single { it.type == AtomicLong::class.java }.apply { isAccessible = true }.get(token)
        assertSame(revisionField.get(permit), tokenAtomic)
    }
}
