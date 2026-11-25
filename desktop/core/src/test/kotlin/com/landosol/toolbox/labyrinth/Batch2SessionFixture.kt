package com.landosol.toolbox.labyrinth

import com.landosol.toolbox.automation.*
import com.landosol.toolbox.labyrinth.vision.*
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*

internal class Batch2SessionFixture(private val boss: Boolean = false) {
    val manager = AutomationSessionManager()
    val clock = AtomicLong(0L)
    val calls = AtomicInteger(0)
    val outcome = AtomicReference<AutomationBackendResult>(AutomationBackendResult.Completed)
    val ids = (1..5).map { "r$it" }
    private val profiles = ids.mapIndexed { i,id -> id to LabyrinthRoleProfile(
        characterId=id, displayName="角色${i+1}", roleClass=if(i==0) "掩护者" else "攻击者",
        attribute=LabyrinthCharacterAttribute.LIGHT, damageType=LabyrinthRoleDamageType.PHYSICAL,
        position=i+1, userScore=80.0, userRatingCount=5, physicalDamagePotential=80.0,
        magicDamagePotential=0.0, functions=LabyrinthRoleFunctions(reliableVanguard=if(i==0) 100.0 else 0.0)) }.toMap()
    val session = LabyrinthEntryRecognitionSession(
        sessionManager=manager, captureActive={true}, processorFactory={{error("no bitmap input")}},
        actionExecutor=SessionBoundActionExecutor(manager) { calls.incrementAndGet(); outcome.get() },
        actionsAvailable={true}, clock={clock.get()},
        battleTeamRecommendationPlanner=if(boss) planner() else null,
        battleTeamSelectionPlanner=if(boss) LabyrinthBattleTeamSelectionPlanner(profiles,minimumCharacterConfidence=0.0) else null,
    )
    val id get()=requireNotNull(session.state.value.sessionId)
    private fun planner()=LabyrinthBattleTeamRecommendationPlanner(profiles,
        LabyrinthTeamPlanSearcher(LabyrinthTeamOptimizer(LabyrinthTeamScorer(LabyrinthTeamScoringConfig(
            attributeDamageBonus=mapOf(1 to 0.0,2 to 0.08,3 to 0.16,4 to 0.25,5 to 0.75)))),
            LabyrinthTeamPlanSearchConfig(0.0,0.0,0.0,0.0,0.0,0.0,0.0)))
    suspend fun start() {
        assertTrue(session.startAutomation(accountId=1L) is LabyrinthEntryRecognitionStartResult.Started)
        set("entryPhaseComplete",true)
        if(boss) {
            set("combatContext",LabyrinthCombatContext(LabyrinthCombatKind.BOSS))
            set("activeNodeType", com.landosol.toolbox.labyrinth.node.LabyrinthNodeTypes.BOSS)
            set("bossEditorPreparationStage",LabyrinthBossEditorPreparationStage.COMPLETE)
            set("bossEditorTeamIndex",1)
            state { it.copy(joinedCharacters=ids.mapIndexed { i,id -> LabyrinthJoinedCharacter(
                id,"角色${i+1}",i,0.95,0L,0L) }) }
        }
    }
    suspend fun close() { session.stop("batch2 fixture done") }
    fun field(name:String)=session.javaClass.getDeclaredField(name).apply {isAccessible=true}
    fun get(name:String):Any?=field(name).get(session)
    fun set(name:String,value:Any?) {field(name).set(session,value)}
    @Suppress("UNCHECKED_CAST") fun state(update:(LabyrinthEntryRecognitionSessionState)->LabyrinthEntryRecognitionSessionState) {
        val flow=get("_state") as MutableStateFlow<LabyrinthEntryRecognitionSessionState>; flow.value=update(flow.value)
    }
    private fun adapt(types:Array<Class<*>>,args:Array<out Any?>):Array<Any?> = types.mapIndexed { i,t ->
        val a=args[i];when {t.name=="long" -> (a as? AutomationSessionId)?.value ?: (a as Number).toLong()
            t.name=="int" -> (a as Number).toInt(); else -> a} }.toTypedArray()
    fun invoke(name:String,vararg args:Any?):Any? {
        val m=session.javaClass.declaredMethods.first {(it.name == name || it.name.startsWith("$name-")) && it.parameterCount==args.size}
        m.isAccessible=true;return m.invoke(session,*adapt(m.parameterTypes,args))
    }
    suspend fun feed(result:LabyrinthEntryFrameResult,at:Long,processingAt:Long=at) {
        clock.set(processingAt)
        suspendCoroutine<Unit> { continuation ->
            val m=session.javaClass.declaredMethods.first {it.name.startsWith("processFrameResult") && it.parameterCount==6}
            m.isAccessible=true
            val args=arrayOf<Any?>(id,result,1920,1080,at,continuation)
            try {if(m.invoke(session,*adapt(m.parameterTypes,args)) !== COROUTINE_SUSPENDED) continuation.resume(Unit)}
            catch(e:InvocationTargetException) {continuation.resumeWithException(e.targetException)}
        }
    }
    suspend fun awaitActions() {
        val flag=get("actionInFlight") as AtomicBoolean
        withTimeout(2000L) {while(flag.get()) delay(1L)}
    }
    fun round()=get("roleRewardRound") as LabyrinthRoleRewardRound
    fun pending()=get("pendingBattleTeamAction")
    fun team(index:Int?,members:Boolean=true,page:LabyrinthEntryPageState=LabyrinthEntryPageState.BATTLE_TEAM_SELECTION,
             stable:Boolean=true):LabyrinthEntryFrameResult = LabyrinthEntryFrameResult(
        observation=LabyrinthEntryPageObservation(page,1.0,emptyMap(),LabyrinthAnchorScores(emptyMap())),
        matchedFeatures=emptyList(),elapsedMillis=0,
        anchorMatches=mapOf(EntryAnchorId.BATTLE_TEAM_START_BUTTON to EntryAnchorMatch(0.95,EntryPixelRect(1565,850,270,110))),
        battleTeamSelection=LabyrinthBattleTeamObservation(
            currentFilter=LabyrinthBattleElementFilter.ALL,filters=emptyList(),visibleCharacters=emptyList(),
            selectedCharacters=if(members) ids.mapIndexed {i,id -> LabyrinthBattleCharacterMatch(
                slotId="member_$i",characterId=id,displayName="角色${i+1}",iconVariant=null,
                confidence=0.95,screenRect=EntryPixelRect(200+i*310,940,260,120),selected=true)} else emptyList(),
            scrollbar=LabyrinthBattleScrollbarObservation(EntryPixelRect(1825,377,28,349),null,false,false,0.0),
            recognitionState=if(stable) LabyrinthBattleTeamRecognitionState.STABLE else LabyrinthBattleTeamRecognitionState.WAITING_FOR_STABILITY,
            viewportRevision=0L,bossTeamIndex=index,
            bossTeamTabs=listOf(EntryPixelRect(60,120,150,90),EntryPixelRect(240,120,150,90),EntryPixelRect(420,120,150,90))),
        frameWidth=1920,frameHeight=1080)
    fun roleFrame(ids:List<String?>):LabyrinthEntryFrameResult = LabyrinthEntryFrameResult(
        observation=LabyrinthEntryPageObservation(LabyrinthEntryPageState.INITIAL_CHARACTER_SELECTION,1.0,emptyMap(),
            LabyrinthAnchorScores(mapOf(EntryAnchorId.ROLE_REWARD_TITLE to 1.0,EntryAnchorId.ROLE_REWARD_INSTRUCTION to 1.0,
                EntryAnchorId.ROLE_REWARD_SELECT_BUTTON to 1.0))),
        matchedFeatures=emptyList(),elapsedMillis=0,
        characterMatches=ids.mapIndexed {i,id -> LabyrinthCharacterMatch(
            slotId=listOf("role_reward_left","role_reward_center","role_reward_right")[i],
            characterId=id,displayName=id?.let {"角色$it"},confidence=if(id==null) 0.0 else 0.95,
            screenRect=EntryPixelRect(260+i*555,650,290,155),trusted=id!=null,nameAssisted=id!=null) },
        frameWidth=1920,frameHeight=1080)
    fun choose(result:LabyrinthEntryFrameResult,selected:String,at:Long) {
        clock.set(at);state {it.copy(lastResult=result)}
        assertEquals(true,invoke("observeRoleRewardRound",id,result,at))
        val slot=result.characterMatches.first {it.characterId==selected}.slotId
        val rect=requireNotNull(LabyrinthRoleRewardChoicePlanner.selectionButtonRect(slot,1920,1080))
        state {it.copy(roleRewardChoiceDecision=LabyrinthRoleRewardChoiceDecision.Select(selected,"角色$selected",rect,emptyList()))}
        invoke("dispatchPostEntryTap",id,LabyrinthPostEntryActionKind.SELECT_ROLE_REWARD,"选择$selected",rect,at,
            labyrinthRoleRewardSelectionSignature(result),selected,null,null,null,false,emptyList<String>())
    }
}
