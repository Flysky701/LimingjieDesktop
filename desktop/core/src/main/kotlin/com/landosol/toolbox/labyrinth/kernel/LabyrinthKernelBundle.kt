package com.landosol.toolbox.labyrinth.kernel

import com.landosol.toolbox.labyrinth.LabyrinthRelicChoicePolicy
import com.landosol.toolbox.labyrinth.LabyrinthRoleRewardChoicePlanner
import com.landosol.toolbox.labyrinth.kernel.battle.BattleDomainModule
import com.landosol.toolbox.labyrinth.kernel.event.EventDomainModule
import com.landosol.toolbox.labyrinth.kernel.map.MapDomainModule
import com.landosol.toolbox.labyrinth.kernel.recovery.RecoveryDomainModule
import com.landosol.toolbox.labyrinth.kernel.reward.RewardTailModule
import com.landosol.toolbox.labyrinth.kernel.settlement.SettlementDomainModule
import com.landosol.toolbox.labyrinth.kernel.shop.ShopDomainModule
import com.landosol.toolbox.labyrinth.kernel.shop.ShopPurchaseTarget

class LabyrinthKernelBundle internal constructor(
    private val runtime: KernelRuntime,
    rolePlanner: LabyrinthRoleRewardChoicePlanner?,
    relicPolicy: LabyrinthRelicChoicePolicy,
    purchaseTarget: ShopPurchaseTarget,
) {
    constructor(runtime: KernelRuntime) : this(runtime, null, LabyrinthRelicChoicePolicy(), ShopPurchaseTarget())

    val encounterStack = runtime.encounterStack
    val event = EventDomainModule(runtime)
    val recovery = RecoveryDomainModule(runtime)
    internal val settlement = SettlementDomainModule(runtime)
    val reward = RewardTailModule(runtime, rolePlanner, relicPolicy)
    val shop = ShopDomainModule(runtime, purchaseTarget)
    val battle = BattleDomainModule(runtime, encounterStack)
    val map = MapDomainModule(runtime, encounterStack)

    private val modules = listOf(event, recovery, settlement, reward, shop, battle, map)

    init {
        synchronized(runtime) { modules.forEach(runtime::registerModule) }
    }

    fun moduleStatuses(): List<KernelModuleStatus> = runtime.moduleStatuses()

    /** Relogin retains the event session root and the current trace. */
    internal fun resetForEntryRestart() = synchronized(runtime) {
        modules.filterNot { it === event }.forEach { it.reset(runtime.traceId) }
    }
}
