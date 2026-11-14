package com.landosol.toolbox.labyrinth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Strategy UI adapter over the shared, atomically persisted configuration snapshot. */
class AndroidLabyrinthStrategySettingsStore(
    private val store: LabyrinthConfigurationStore,
    private val gate: com.landosol.toolbox.automation.TaskConfigurationGate,
) {
    val state = store.strategyState

    suspend fun save(settings: LabyrinthStrategySettings) {
        val admission = checkNotNull(gate.tryBeginTask()) { com.landosol.toolbox.automation.TaskConfigurationGate.TRANSFER_MESSAGE }
        try { withContext(Dispatchers.IO) { store.saveStrategy(settings) } } finally { admission.close() }
    }
}
