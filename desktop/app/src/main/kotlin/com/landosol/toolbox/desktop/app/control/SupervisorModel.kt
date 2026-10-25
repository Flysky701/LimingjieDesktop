package com.landosol.toolbox.desktop.app.control

import com.landosol.toolbox.desktop.platform.InstanceCatalog
import com.landosol.toolbox.desktop.platform.InstanceProfile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class ControlInstance(val profile: InstanceProfile, val link: WorkerLink)

/** Owns only profiles, worker processes and UI state. It never creates a task graph. */
@OptIn(ExperimentalCoroutinesApi::class)
class SupervisorModel(
    private val catalog: InstanceCatalog,
    private val launcher: WorkerLauncher,
    private val root: Path,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val mutableProfiles = MutableStateFlow(catalog.list())
    val profiles = mutableProfiles.asStateFlow()
    private val mutableInstances = MutableStateFlow<List<ControlInstance>>(emptyList())
    val instances = mutableInstances.asStateFlow()
    private val mutableSelected = MutableStateFlow<String?>(null)
    val selectedId = mutableSelected.asStateFlow()
    private val mutableErrors = MutableStateFlow<String?>(null)
    val error = mutableErrors.asStateFlow()
    private val mutableDirty = MutableStateFlow<Set<String>>(emptySet())
    val dirtyInstances = mutableDirty.asStateFlow()
    private var closing = false
    private val shutdownDone = CompletableDeferred<Unit>()
    val busy: StateFlow<Boolean> = instances.flatMapLatest { items ->
        if (items.isEmpty()) flowOf(false) else combine(items.map { it.link.snapshots }) { states -> states.any { ControlSnapshot(it).busy } }
    }.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun restore() = withContext(Dispatchers.IO) {
        val state = runCatching { Json.parseToJsonElement(Files.readString(root.resolve("shell.json"))).jsonObject }.getOrNull()
        val ids = (state?.get("open") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val available = profiles.value.map { it.id.toString() }.toSet()
        (ids ?: listOf(profiles.value.first().id.toString())).filter { it in available }.forEach { open(it) }
        val previous = state?.wireString("selected")
        if (previous != null && instances.value.any { it.profile.id.toString() == previous }) select(previous)
    }

    suspend fun create(name: String) = withContext(Dispatchers.IO) {
        val profile = mutex.withLock {
            check(!closing) { "总控正在退出" }
            catalog.create(name).also { mutableProfiles.value = catalog.list() }
        }
        open(profile.id.toString())
    }

    suspend fun open(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closing) { "总控正在退出" }
            val profile = profiles.value.firstOrNull { it.id.toString() == id } ?: error("实例不存在")
            val existing = instances.value.firstOrNull { it.profile.id == profile.id }
            if (existing?.link?.connection?.value in setOf(WorkerConnectionStatus.LOST, WorkerConnectionStatus.CLOSED)) {
                existing!!.link.shutdown()
                mutableInstances.value = instances.value.filterNot { it.link.workerRunId == existing.link.workerRunId }
            }
            if (instances.value.none { it.profile.id == profile.id }) {
                mutableInstances.value += ControlInstance(profile, launcher.start(id, profile.name, profile.dataDirectory))
            }
            selectUnlocked(id)
            save()
        }
    }

    suspend fun select(id: String) = mutex.withLock { selectUnlocked(id); withContext(Dispatchers.IO) { save() } }
    private fun selectUnlocked(id: String) {
        if (instances.value.none { it.profile.id.toString() == id }) return
        mutableSelected.value = id
        instances.value.forEach { it.link.setSelected(it.profile.id.toString() == id) }
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val changed = catalog.rename(UUID.fromString(id), name)
        mutableProfiles.value = catalog.list()
        mutableInstances.value = instances.value.map { if (it.profile.id == changed.id) it.copy(profile = changed) else it }
    } }

    suspend fun close(id: String) {
        val item = mutex.withLock { instances.value.firstOrNull { it.profile.id.toString() == id } } ?: return
        item.link.shutdown()
        mutex.withLock {
            mutableInstances.value = instances.value.filterNot { it.link.workerRunId == item.link.workerRunId }
            markDirty(id, false)
            if (selectedId.value == id) {
                mutableSelected.value = instances.value.firstOrNull()?.profile?.id?.toString()
                selectedId.value?.let(::selectUnlocked)
            }
            withContext(Dispatchers.IO) { save() }
        }
    }

    suspend fun stopAll() = supervisorScope {
        instances.value.map { item -> async {
            if (item.link.connection.value == WorkerConnectionStatus.CONNECTED) {
                runCatching { item.link.request(ControlOps.STOP) }.exceptionOrNull()?.let { reportError("${item.profile.name}：${it.message}") }
            }
        } }.awaitAll()
    }

    suspend fun shutdown(): Unit = withContext(NonCancellable) {
        val copy = mutex.withLock {
            if (closing) null else { closing = true; instances.value }
        }
        if (copy == null) { shutdownDone.await(); return@withContext }
        try {
            // Persistence is best effort; it must never bypass worker cleanup.
            runCatching { withContext(Dispatchers.IO) { save() } }
                .onFailure { reportError("保存标签状态失败：${it.message}") }
            val outcomes = supervisorScope {
                copy.map { item -> async { runCatching { item.link.shutdown() } } }.awaitAll()
            }
            mutableInstances.value = copy.filterIndexed { index, _ -> outcomes[index].isFailure }
            outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
            shutdownDone.complete(Unit)
        } catch (failure: Throwable) {
            shutdownDone.completeExceptionally(failure)
            throw failure
        }
    }

    fun markDirty(id: String, dirty: Boolean) { mutableDirty.update { if (dirty) it + id else it - id } }
    fun reportError(message: String) { mutableErrors.value = message }
    fun dismissError() { mutableErrors.value = null }

    private fun save() {
        Files.createDirectories(root)
        val content = buildJsonObject {
            put("version", 1)
            put("open", JsonArray(instances.value.map { JsonPrimitive(it.profile.id.toString()) }))
            put("selected", selectedId.value?.let(::JsonPrimitive) ?: JsonNull)
        }
        val temporary = Files.createTempFile(root, "shell-", ".writing")
        try {
            Files.writeString(temporary, content.toString())
            Files.move(temporary, root.resolve("shell.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
