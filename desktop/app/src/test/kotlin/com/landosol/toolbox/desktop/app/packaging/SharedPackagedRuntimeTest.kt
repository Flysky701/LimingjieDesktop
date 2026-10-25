package com.landosol.toolbox.desktop.app.packaging

import com.landosol.toolbox.account.AccountEditorState
import com.landosol.toolbox.account.AccountUiState
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopGoal
import com.landosol.toolbox.desktop.app.adapters.storage.DesktopPreferences
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.app.ui.RunConsoleState
import com.landosol.toolbox.desktop.platform.InstanceLock
import com.landosol.toolbox.desktop.platform.WorkerProcessContainment
import com.landosol.toolbox.labyrinth.LabyrinthRerollSettings
import com.landosol.toolbox.labyrinth.LabyrinthStrategySettings
import com.landosol.toolbox.protocol.bilibili.GameServer
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real packaged processes and one temporary authority. Never discovers devices or logs in. */
class SharedPackagedRuntimeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oneRuntimeUsesSharedDataThenTwoRuntimesKeepBindingsAndLifetimesSeparate() = runBlocking {
        val executable = System.getProperty("limingjie.test.packagedWorker")
        assumeTrue("Run explicitly with -PpackagedWorkerExecutable=<exe>", executable != null)
        val root = temporary.root.toPath()
        val host = SharedDataHost.start(root.resolve("shared"), startupUpdates = false)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val launcher = WorkerLauncher(scope, WorkerProcessContainment::own, listOf(checkNotNull(executable), "--offline-startup"))
        val links = mutableListOf<WorkerLink>()
        try {
            suspend fun account(alias: String): Long {
                host.control.request(ControlOps.ACCOUNT_NEW)
                host.control.request(ControlOps.ACCOUNT_SAVE, buildJsonObject {
                    put("editor", ControlModelCodec.encode(AccountEditorState(alias = alias,
                        loginId = "offline-$alias", password = "synthetic-unused-password", server = GameServer.CN_BILIBILI)))
                })
                return withTimeout(10_000) {
                    host.control.snapshots.first { raw ->
                        raw["accounts"]?.let { ControlModelCodec.decode<AccountUiState>(it).accounts.any { it.alias == alias } } == true
                    }.getValue("accounts").let { ControlModelCodec.decode<AccountUiState>(it).accounts.single { it.alias == alias }.id }
                }
            }
            suspend fun start(name: String): WorkerLink {
                val link = launcher.start(UUID.randomUUID().toString(), name, root.resolve(name), sharedData = host.endpoint)
                links += link
                withTimeout(60_000) {
                    link.connection.first { it != WorkerConnectionStatus.STARTING }
                    assertEquals(link.error.value, WorkerConnectionStatus.CONNECTED, link.connection.value)
                    link.snapshots.first { it["console"] != null }
                }
                return link
            }
            suspend fun bind(link: WorkerLink, id: Long) {
                link.request(ControlOps.ACCOUNT_SELECT, buildJsonObject { put("id", id) })
                withTimeout(10_000) { link.snapshots.first {
                    it["console"]?.let { state -> ControlModelCodec.decode<RunConsoleState>(state).task.selectedAccount?.id } == id
                } }
            }
            val alice = account("packaged-A")
            val bob = account("packaged-B")
            val strategy = LabyrinthStrategySettings(playerWeight = 37)
            val d5Goals = listOf(DesktopGoal(1, 4))
            host.control.request(ControlOps.PREFS_PATCH, buildJsonObject { put("goals", ControlModelCodec.encode(d5Goals)) })
            host.control.request(ControlOps.STRATEGY_SAVE, buildJsonObject { put("value", ControlModelCodec.encode(strategy)) })

            // First establish the single-runtime path through the real packaged entry point.
            val a = start("runtime-A")
            bind(a, alice)
            withTimeout(10_000) { a.snapshots.first {
                it["strategy"]?.let { value -> ControlModelCodec.decode<LabyrinthStrategySettings>(value) } == strategy
            } }
            assertEquals(d5Goals, ControlModelCodec.decode<DesktopPreferences>(a.snapshots.value.getValue("preferences")).goals)
            val settings = LabyrinthRerollSettings(difficulty = 2, maxAttempts = "19")
            a.request(ControlOps.SAVE_REROLL, buildJsonObject { put("value", ControlModelCodec.encode(settings)) })
            val client = SharedDataClient(host.endpoint)
            assertEquals(settings, client.taskData(alice).reroll)
            assertEquals(strategy, ControlModelCodec.decode<LabyrinthStrategySettings>(a.snapshots.value.getValue("strategy")))
            assertFalse(a.snapshots.value.toString().contains("synthetic-unused-password"))

            // Add a second runtime only after the first has bound and saved successfully.
            val b = start("runtime-B")
            bind(b, bob)
            assertEquals(bob, ControlModelCodec.decode<RunConsoleState>(b.snapshots.value.getValue("console")).task.selectedAccount?.id)
            assertEquals(alice, ControlModelCodec.decode<RunConsoleState>(a.snapshots.value.getValue("console")).task.selectedAccount?.id)
            val globalAccounts = ControlModelCodec.decode<AccountUiState>(host.control.snapshots.value.getValue("accounts"))
            assertEquals(2, globalAccounts.accounts.size)
            assertEquals(alice, globalAccounts.accounts.single { it.isSelected }.id)
            a.setSelected(false); b.setSelected(true)
            a.shutdown()
            InstanceLock.acquire(root.resolve("runtime-A")).close()
            b.request(ControlOps.SAVE_REROLL, buildJsonObject { put("value", ControlModelCodec.encode(settings.copy(difficulty = 3))) })
            assertEquals(3, client.taskData(bob).reroll.difficulty)
            assertEquals(2, client.taskData(alice).reroll.difficulty)
            assertEquals(WorkerConnectionStatus.CONNECTED, b.connection.value)
            for (directory in listOf(root.resolve("runtime-A"), root.resolve("runtime-B"))) {
                for (name in listOf("account-credentials", "sdk-sessions", "labyrinth-strategy")) {
                    assertFalse("Runtime must not persist $name", Files.exists(directory.resolve("shared_prefs/$name.json")))
                }
            }
        } finally {
            withContext(NonCancellable) {
                try { links.forEach { it.shutdown() } } finally { scope.cancel(); host.shutdown() }
            }
        }
        InstanceLock.acquire(root.resolve("shared")).close()
        InstanceLock.acquire(root.resolve("runtime-B")).close()
    }
}
