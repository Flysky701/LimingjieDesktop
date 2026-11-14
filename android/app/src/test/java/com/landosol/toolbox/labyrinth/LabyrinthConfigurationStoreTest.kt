package com.landosol.toolbox.labyrinth

import org.junit.Assert.*
import org.junit.Test

class LabyrinthConfigurationStoreTest {
    private val initial = LabyrinthConfigurationValues(
        LabyrinthStrategySettings(playerWeight = 60, personalRoleScores = mapOf("100101" to 33)),
        mapOf(1L to LabyrinthRerollSettings(difficulty = 1), 2L to LabyrinthRerollSettings(difficulty = 2)),
    )
    private val shared = LabyrinthSharedConfiguration("test", 1,
        strategy = LabyrinthStrategySettings(playerWeight = 20), personalScores = mapOf("100201" to 0),
        reroll = LabyrinthRerollSettings(difficulty = 3))

    @Test fun `multiple modules commit once and survive snapshot serialization`() {
        var writes = 0
        var disk = ""
        val store = LabyrinthConfigurationStore(initial) { writes++; disk = LabyrinthConfigurationPersistence.encode(it) }
        val next = store.importConfiguration(shared, shared.modules, 1, false, initial)
        assertEquals(1, writes)
        assertEquals(20, next.strategy.playerWeight)
        assertEquals(0, next.strategy.personalRoleScores["100201"])
        assertEquals(3, next.rerolls.getValue(1).difficulty)
        assertEquals(initial.rerolls[2L], next.rerolls[2L])
        assertEquals(next, LabyrinthConfigurationPersistence.decode(disk))
        assertEquals(next, store.state.value)
        assertEquals(next.strategy, store.strategyState.value)
    }

    @Test fun `write failure leaves every published module unchanged`() {
        val store = LabyrinthConfigurationStore(initial) { error("disk full") }
        assertThrows(IllegalStateException::class.java) { store.importConfiguration(shared, shared.modules, 1, false, initial) }
        assertEquals(initial, store.state.value)
        assertEquals(initial.strategy, store.strategyState.value)
        assertEquals(initial.rerolls[1L], store.load(1))
    }

    @Test fun `stale preview refuses overwrite after local save`() {
        var writes = 0
        val store = LabyrinthConfigurationStore(initial) { writes++ }
        val saved = initial.strategy.copy(playerWeight = 77)
        store.saveStrategy(saved)
        assertThrows(IllegalStateException::class.java) { store.importConfiguration(shared, shared.modules, 1, false, initial) }
        assertEquals(saved, store.state.value.strategy)
        assertEquals(saved, store.strategyState.value)
        assertEquals(1, writes)
    }

    @Test fun `invalid data fails before persistence`() {
        var writes = 0
        val store = LabyrinthConfigurationStore(initial) { writes++ }
        assertThrows(IllegalArgumentException::class.java) { store.saveStrategy(initial.strategy.copy(playerWeight = 101)) }
        assertThrows(IllegalArgumentException::class.java) { store.save(1, LabyrinthRerollSettings(difficulty = 0)) }
        assertThrows(IllegalArgumentException::class.java) { store.importConfiguration(shared, emptySet(), null, false, initial) }
        assertThrows(IllegalArgumentException::class.java) {
            store.importConfiguration(shared.copy(strategy = initial.strategy.copy(playerWeight = 101)), shared.modules, 1, false, initial)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.importConfiguration(shared.copy(reroll = LabyrinthRerollSettings(difficulty = 0)), shared.modules, 1, false, initial)
        }
        assertEquals(0, writes)
        assertEquals(initial, store.state.value)
        assertEquals(initial.strategy, store.strategyState.value)
    }

    @Test fun `undo metadata and restored values both survive snapshot reconstruction`() {
        var disk = ""
        var writes = 0
        val persist: (LabyrinthConfigurationValues) -> Unit = {
            writes++
            disk = LabyrinthConfigurationPersistence.encode(it)
        }
        val store = LabyrinthConfigurationStore(initial, persist)
        val imported = store.importConfiguration(shared, shared.modules, 1, false, initial)
        val expectedUndo = LabyrinthConfigurationUndo(initial.strategy, initial.rerolls, shared.modules, 1L)
        assertEquals(expectedUndo, imported.undoImport)
        assertEquals(1, writes)
        val reloaded = LabyrinthConfigurationStore(LabyrinthConfigurationPersistence.decode(disk), persist)
        assertEquals(expectedUndo, reloaded.state.value.undoImport)
        assertEquals(initial, reloaded.undoImport(reloaded.state.value))
        assertEquals(initial.strategy, reloaded.strategyState.value)
        assertEquals(2, writes)
        val afterUndo = LabyrinthConfigurationStore(LabyrinthConfigurationPersistence.decode(disk), persist)
        assertEquals(initial, afterUndo.state.value)
        assertNull(afterUndo.state.value.undoImport)
    }

    @Test fun `either ordinary save persists its values and invalidates undo`() {
        listOf(LabyrinthConfigModule.STRATEGY, LabyrinthConfigModule.REROLL).forEach { module ->
            var disk = ""
            val store = LabyrinthConfigurationStore(initial) { disk = LabyrinthConfigurationPersistence.encode(it) }
            val imported = store.importConfiguration(shared, shared.modules, 1, false, initial)
            val expected = if (module == LabyrinthConfigModule.STRATEGY) {
                val settings = imported.strategy.copy(playerWeight = 77)
                store.saveStrategy(settings)
                imported.copy(strategy = settings, undoImport = null)
            } else {
                val settings = LabyrinthRerollSettings(difficulty = 4)
                store.save(2, settings)
                imported.copy(rerolls = imported.rerolls + (2L to settings), undoImport = null)
            }
            assertEquals(module.name, expected, store.state.value)
            assertEquals(expected.strategy, store.strategyState.value)
            assertEquals(expected, LabyrinthConfigurationPersistence.decode(disk))
            assertNull(store.state.value.undoImport)
            assertThrows(IllegalStateException::class.java) { store.undoImport(store.state.value) }
        }
    }

    @Test fun `failed undo retains imported state and recovery point`() {
        var fail = false
        val store = LabyrinthConfigurationStore(initial) { if (fail) error("disk full") }
        val imported = store.importConfiguration(shared, shared.modules, 1, false, initial)
        fail = true
        assertThrows(IllegalStateException::class.java) { store.undoImport(imported) }
        assertEquals(imported, store.state.value)
        assertEquals(imported.strategy, store.strategyState.value)
        fail = false
        assertEquals(initial, store.undoImport(imported))
        assertEquals(initial.strategy, store.strategyState.value)
    }

    @Test fun `caller mutation cannot change committed settings`() {
        val scores = mutableMapOf("100101" to 88)
        val store = LabyrinthConfigurationStore(initial) {}
        store.saveStrategy(initial.strategy.copy(personalRoleScores = scores))
        scores.clear()
        assertEquals(mapOf("100101" to 88), store.state.value.strategy.personalRoleScores)
        assertEquals(mapOf("100101" to 88), store.strategyState.value.personalRoleScores)
    }
}
