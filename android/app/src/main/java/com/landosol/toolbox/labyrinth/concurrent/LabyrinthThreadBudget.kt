package com.landosol.toolbox.labyrinth.concurrent

/** Startup snapshot only: no settings, memory policy, extra pools, or live resizing. */
class LabyrinthThreadBudget private constructor(
    val availableProcessors: Int,
    val cpuWorkerBudget: Int,
) {
    val captureThreadBudget: Int get() = 1
    val controlThreadBudget: Int get() = 1
    val businessThreadBudget: Int get() = captureThreadBudget + controlThreadBudget + cpuWorkerBudget

    companion object {
        /** Baseline is 1+1+2; only six or more Android-visible processors select 1+1+4. */
        fun forProcessors(availableProcessors: Int): LabyrinthThreadBudget = LabyrinthThreadBudget(
            availableProcessors = availableProcessors,
            cpuWorkerBudget = if (availableProcessors >= 6) 4 else 2,
        )

        /** Reading the snapshot never creates any executor or Android service. */
        val process: LabyrinthThreadBudget by lazy {
            forProcessors(Runtime.getRuntime().availableProcessors())
        }
    }
}
