package com.landosol.toolbox.desktop.app.ux

import com.landosol.toolbox.desktop.app.adapters.storage.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.nio.file.Files
import java.util.concurrent.Executors

class RunHistoryStoreTest {
    @get:Rule val directory=TemporaryFolder()
    private val day=86_400_000L
    @Test fun appendIsCompleteUnderConcurrentWritersAndPersistsTerminalUpdates() {
        val root=directory.root.toPath();val clock=200*day;val store=RunHistoryStore(root,{}, {clock})
        val pool=Executors.newFixedThreadPool(4)
        try {pool.invokeAll((0..23).map{i->java.util.concurrent.Callable {store.put(DesktopRunRecord("$i",clock,"样例","只读识别","当前画面"))}}).forEach{it.get()}}finally{pool.shutdown()}
        store.finish("0","已停止","用户停止")
        val lines=Files.readAllLines(root.resolve("runs.jsonl"));assertEquals(25,lines.size);lines.forEach{assertNotNull(JSONObject(it).getString("id"))}
        val loaded=RunHistoryStore(root,{}, {clock});assertEquals(24,loaded.records.value.size);assertEquals("已停止",loaded.records.value.first{it.id=="0"}.result)
    }
    @Test fun damagedAndCrashTruncatedLinesDoNotHideLaterEvents() {
        val root=directory.root.toPath();val notices=mutableListOf<String>();val store=RunHistoryStore(root,notices::add)
        store.put(DesktopRunRecord("1",System.currentTimeMillis(),"样例","只读识别","当前画面"))
        Files.writeString(root.resolve("runs.jsonl"),"{broken",java.nio.file.StandardOpenOption.APPEND)
        val loaded=RunHistoryStore(root,notices::add);assertEquals(1,loaded.records.value.size);assertTrue(notices.isNotEmpty())
        loaded.finish("1","完成","无异常");assertEquals("完成",RunHistoryStore(root,{}).records.value.single().result)
    }
    @Test fun unfinishedTasksAreRecoveredOnceAndRetentionKeepsNewest() {
        val root=directory.root.toPath();var clock=200*day;val store=RunHistoryStore(root,{}, {clock},limit=3)
        (0..4).forEach{i->store.put(DesktopRunRecord("$i",clock+i,"样例","任务","目标"))}
        assertEquals(listOf("2","3","4"),store.records.value.map{it.id})
        val loaded=RunHistoryStore(root,{}, {clock},limit=3);loaded.recoverInterrupted();assertTrue(loaded.records.value.all{it.result=="未正常结束"&&it.endedAt!=null})
        val count=Files.readAllLines(root.resolve("runs.jsonl")).size;loaded.recoverInterrupted();assertEquals(count,Files.readAllLines(root.resolve("runs.jsonl")).size)
        clock+=181*day;loaded.put(DesktopRunRecord("new",clock,"样例","任务","目标"));assertEquals(listOf("new"),loaded.records.value.map{it.id})
    }
}
