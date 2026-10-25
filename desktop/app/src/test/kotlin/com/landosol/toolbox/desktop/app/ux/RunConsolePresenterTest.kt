package com.landosol.toolbox.desktop.app.ux

import com.landosol.toolbox.desktop.app.ui.*
import com.landosol.toolbox.desktop.app.*
import com.landosol.toolbox.labyrinth.batch.*
import org.junit.Assert.*
import org.junit.Test

class RunConsolePresenterTest {
    @Test fun pendingDraftCanCancelOrApproveNavigation(){val guard=NavigationGuard();var called=0;var pending:(()->Unit)?=null;guard.intercept={pending=it};guard.request{called++};assertEquals(0,called);pending=null;assertEquals(0,called);guard.request{called++};pending!!();assertEquals(1,called)}
    @Test fun idleAndBusyAndMissingFieldsHaveExplicitGates(){
        val idle=RunConsoleState();assertEquals("尚未选择账号",startMissing(DesktopTask.BATCH,idle,true,true))
        assertEquals("模拟器未连接",startMissing(DesktopTask.RECOGNIZE,idle,true,false))
        assertEquals("当前任务正在运行",startMissing(DesktopTask.RECOGNIZE,idle.copy(busy=true),true,false))
        assertEquals("正在停止任务",startMissing(DesktopTask.RECOGNIZE,idle.copy(busy=true,stopping=true),true,false))
    }
    @Test fun allBatchHaltReasonsRetainRawDetailsAndProvideAction(){
        LabyrinthBatchHaltReason.entries.forEach{reason->val info=explainStop(reason,"原始原因 ${reason.name}");assertEquals("原始原因 ${reason.name}",info.original);assertTrue(info.advice.isNotBlank());assertTrue(info.action.isNotBlank())}
        assertTrue(explainStop(LabyrinthBatchHaltReason.FATAL_RUN,"已识别战斗失败页，但缺少本次战斗上下文；未自动点击结束或重新挑战").advice.contains("标题画面"))
    }
    @Test fun ratingsMatchUpstreamExplicitApplyRestoreAndCancelSemantics(){
        assertEquals("57",ScoreEdits.initial(null,56.7));assertEquals("0",ScoreEdits.initial(0,56.7));assertEquals("",ScoreEdits.initial(null,null))
        val calls=mutableListOf<Pair<String,Int?>>()
        val change:(String,Int?)->Unit={id,n->calls+=id to n}
        assertTrue(ScoreEdits.apply("1013","0",change));assertTrue(ScoreEdits.apply("1013","100",change))
        assertFalse(ScoreEdits.apply("1013","101",change));assertFalse(ScoreEdits.apply("1013","",change));assertFalse(ScoreEdits.apply("1013","-1",change))
        ScoreEdits.restore("1013",100,change);ScoreEdits.restore("1063",null,change)
        assertEquals(listOf("1013" to 0,"1013" to 100,"1013" to null),calls)
    }
    @Test fun logKindsAreExplicitAndRepeatsCountAfterRedaction(){
        val log=UserLog();log.add("重复行");log.add("重复行");assertEquals(2,log.lines.value.single().repeats)
        log.add("关键动作",kind=UserLogKind.KEY);log.add("注意",kind=UserLogKind.WARNING);log.add("错误",true)
        assertEquals(listOf(UserLogKind.NORMAL,UserLogKind.KEY,UserLogKind.WARNING,UserLogKind.ERROR),log.lines.value.map{it.kind})
    }
}
