package com.landosol.toolbox.desktop.app.adapters.storage

import com.landosol.toolbox.desktop.shared.AtomicFiles
import com.landosol.toolbox.desktop.shared.DesktopLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*

data class DesktopRunRecord(val id: String, val startedAt: Long, val alias: String, val type: String, val goal: String,
    val endedAt: Long? = null, val result: String = "运行中", val reason: String = "", val cleared: Int = 0, val singleRun: Boolean = false) {
    val durationMillis get() = endedAt?.let { (it-startedAt).coerceAtLeast(0) }
}

/** Event journal. Each UTF-8 line is written under one exclusive writer lock and forced to disk.
 * Compaction only applies the documented retention policy; replacement is atomic. No credentials. */
class RunHistoryStore(directory: Path, private val notice: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis,
    private val limit: Int = 5000) {
    private val file = directory.resolve("runs.jsonl")
    private var needsCompaction = false
    private val mutable = MutableStateFlow(load())
    val records = mutable.asStateFlow()
    private fun encode(r: DesktopRunRecord) = JSONObject().put("id",r.id).put("startedAt",r.startedAt).put("alias",r.alias)
        .put("type",r.type).put("goal",r.goal).put("endedAt",r.endedAt ?: JSONObject.NULL).put("result",r.result)
        .put("reason",DesktopLog.redact(r.reason)).put("cleared",r.cleared).put("singleRun",r.singleRun).toString()
    private fun decode(line: String): DesktopRunRecord = JSONObject(line).let { j ->
        DesktopRunRecord(j.getString("id"),j.getLong("startedAt"),j.getString("alias"),j.getString("type"),j.getString("goal"),
            if(j.isNull("endedAt")) null else j.getLong("endedAt"),j.getString("result"),j.optString("reason"),j.optInt("cleared"),j.optBoolean("singleRun"))
    }
    private fun retained(rows: Collection<DesktopRunRecord>): List<DesktopRunRecord> = rows.filter { it.startedAt >= now()-180L*86_400_000 }
        .sortedBy { it.startedAt }.takeLast(limit)
    private fun load(): List<DesktopRunRecord> {
        if(!Files.exists(file)) return emptyList()
        val rows = linkedMapOf<String,DesktopRunRecord>(); var damaged = 0
        Files.newBufferedReader(file).useLines { lines -> lines.forEach { line ->
            if(line.isNotBlank()) try { decode(line).let { rows[it.id]=it } } catch(_:Exception) { damaged++ }
        } }
        if(damaged>0) notice("运行记录有 $damaged 行损坏，已跳过；其余记录可用。")
        val kept=retained(rows.values)
        needsCompaction=kept.size<rows.size
        return kept
    }
    @Synchronized fun put(record: DesktopRunRecord) {
        val safe = record.copy(alias=DesktopLog.redact(record.alias),goal=DesktopLog.redact(record.goal),reason=DesktopLog.redact(record.reason))
        Files.createDirectories(file.parent)
        // Repair a crash-truncated final line before appending, leaving it as an independently skipped line.
        FileChannel.open(file, CREATE, READ, WRITE).use { channel ->
            val size=channel.size(); var prefix=""
            if(size>0) { val last=ByteBuffer.allocate(1);channel.position(size-1);channel.read(last);if(last.array()[0]!=10.toByte()) prefix="\n" }
            channel.position(channel.size())
            val buffer=ByteBuffer.wrap((prefix+encode(safe)+"\n").toByteArray(Charsets.UTF_8))
            while(buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        val all=mutable.value.filterNot { it.id==safe.id }+safe
        val kept=retained(all)
        mutable.value=kept
        if(kept.size<all.size || Files.size(file)>8L*1024*1024) compact()
    }
    @Synchronized fun recoverInterrupted() { if(needsCompaction){compact();needsCompaction=false};mutable.value.filter { it.endedAt==null }.forEach { put(it.copy(endedAt=now(),result="未正常结束",reason="上次进程退出前未记录终态，请检查游戏当前状态。")) } }
    @Synchronized fun compact() { AtomicFiles.write(file,mutable.value.joinToString("\n",postfix=if(mutable.value.isEmpty()) "" else "\n",transform=::encode).toByteArray(Charsets.UTF_8)) }
    @Synchronized fun finish(id:String,result:String,reason:String,cleared:Int=0) { mutable.value.firstOrNull { it.id==id && it.endedAt==null }?.let { put(it.copy(endedAt=now(),result=result,reason=reason,cleared=cleared)) } }
}
