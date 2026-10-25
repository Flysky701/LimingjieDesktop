package com.landosol.toolbox.desktop.platform

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InstanceInfrastructureTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun catalogKeepsLegacyAndConcurrentCreatesDoNotLoseProfilesOrCopySecrets() {
        val root = folder.root.toPath()
        val legacy = Files.createDirectories(root.resolve("legacy"))
        Files.writeString(legacy.resolve("credentials.fixture"), "synthetic-only")
        val catalog = InstanceCatalog(root.resolve("catalog"), legacy)
        val first = catalog.list().single()
        assertEquals(legacy, first.dataDirectory)
        assertEquals(InstanceCatalog.DEFAULT_PROFILE_ID, first.id)
        val pool = Executors.newFixedThreadPool(4)
        val created = try { (1..8).map { n -> pool.submit<InstanceProfile> { InstanceCatalog(root.resolve("catalog"),legacy).create("实例 $n") } }.map { it.get() } }
            finally { pool.shutdownNow() }
        assertEquals(9,catalog.list().size)
        assertEquals(8,created.map { it.id }.distinct().size)
        created.forEach { assertFalse(Files.exists(it.dataDirectory.resolve("credentials.fixture"))) }
        val renamed = catalog.rename(first.id,"原账号")
        assertEquals(first.id,renamed.id); assertEquals(legacy,renamed.dataDirectory)
        assertEquals("原账号",InstanceCatalog(root.resolve("catalog"),legacy).list().first().name)
        assertEquals("synthetic-only",Files.readString(legacy.resolve("credentials.fixture")))
        assertThrows(IllegalArgumentException::class.java) { InstanceCatalog(legacy.resolve("instances"),legacy) }
    }
    @Test fun sharedLeasesRejectAliasAndUnknownIdentityButAllowDifferentBootsAndServers() {
        val root=folder.root.toPath()
        val a=UserResourceLeases(root); val b=UserResourceLeases(root)
        val boot="aaaaaaaa-1111-2222-3333-444444444444"
        a.acquireDevice(boot).use {
            assertThrows(IllegalStateException::class.java) { b.acquireDevice(boot.uppercase()) }
            b.acquireDevice("bbbbbbbb-1111-2222-3333-444444444444").close()
        }
        b.acquireDevice(boot).close()
        a.acquireAccount("cn-bilibili","123").use {
            assertThrows(IllegalStateException::class.java) { b.acquireAccount("cn-bilibili","123") }
            b.acquireAccount("cn-xiaomi","123").close()
        }
        for (identity in listOf("", "unknown", "00000000-0000-0000-0000-000000000000"))
            assertThrows(IllegalArgumentException::class.java) { a.acquireDevice(identity) }
        assertThrows(IllegalArgumentException::class.java) { a.acquireAccount("cn-bilibili","") }
        assertEquals(root.resolve("LimingjieAssistant-runtime"),UserRuntimeDirectory.resolve(mapOf("LOCALAPPDATA" to root.toString()),"Windows", "ignored"))
    }
    @Test fun windowsJobKillsOnlyOwnedWorkerAndReleasesAfterUiProcessDeath() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root=folder.root.toPath()
        val owner=ProcessBuilder(InfrastructureFixture.command("owner",root.toString())).start()
        var worker: ProcessHandle?=null; var independentChild:ProcessHandle?=null
        try {
            waitFor(root.resolve("ready"))
            worker=ProcessHandle.of(Files.readString(root.resolve("worker.pid")).toLong()).get()
            independentChild=ProcessHandle.of(Files.readString(root.resolve("child.pid")).toLong()).get()
            assertTrue(worker.isAlive); assertTrue(independentChild.isAlive)
            owner.destroyForcibly().waitFor(5,TimeUnit.SECONDS)
            worker.onExit().get(5,TimeUnit.SECONDS)
            assertFalse(worker.isAlive)
            assertTrue("shared-service-like child must break away",independentChild.isAlive)
        } finally { owner.destroyForcibly(); worker?.destroyForcibly(); independentChild?.destroyForcibly() }
    }
    @Test fun crossProcessLeasesReleaseOnCrash() {
        val root=folder.root.toPath()
        val child=ProcessBuilder(InfrastructureFixture.command("lease",root.toString())).start()
        try {
            waitFor(root.resolve("ready"))
            assertThrows(IllegalStateException::class.java) { UserResourceLeases(root).acquireAccount("fixture","123") }
            child.destroyForcibly().waitFor(5,TimeUnit.SECONDS)
            val deadline=System.nanoTime()+2_000_000_000
            while (true) {
                val lease=try { UserResourceLeases(root).acquireAccount("fixture","123") }
                catch (busy:IllegalStateException) {
                    if(System.nanoTime()>=deadline) throw busy
                    Thread.sleep(10); continue
                }
                lease.close(); break
            }
        } finally { child.destroyForcibly() }
    }
    @Test fun launcherCarriesProfileAndStopsOnlyItsChild() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root=folder.root.toPath()
        val profile=InstanceCatalog(root.resolve("catalog"),root.resolve("legacy")).create("worker")
        val independent=ProcessBuilder(InfrastructureFixture.command("sleep",root.toString())).start()
        val worker=WorkerProcessLauncher.start(InfrastructureFixture.command("sleep",root.toString()),profile)
        try {
            assertEquals(profile,worker.profile)
            assertTrue(worker.isAlive)
            worker.stop(0)
            assertFalse(worker.isAlive)
            assertTrue(independent.isAlive)
            worker.close()
        } finally { worker.close();independent.destroyForcibly().waitFor(5,TimeUnit.SECONDS) }
    }
    private fun waitFor(file:Path) {
        val deadline=System.nanoTime()+10_000_000_000
        while(!Files.exists(file) && System.nanoTime()<deadline) Thread.sleep(20)
        assertTrue("child fixture did not become ready: $file",Files.exists(file))
    }
}

object InfrastructureFixture {
    fun command(vararg args:String):List<String> = listOf(
        Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-cp",
        listOf(InfrastructureFixture::class.java,ResourceLease::class.java,kotlin.Unit::class.java,com.sun.jna.Native::class.java,com.sun.jna.platform.win32.Kernel32::class.java)
            .map { Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator),
        InfrastructureFixture::class.java.name)+args
    @JvmStatic fun main(args:Array<String>) {
        val root=Path.of(args[1])
        when(args[0]) {
            "lease" -> UserResourceLeases(root).acquireAccount("fixture","123").use {
                Files.writeString(root.resolve("ready"),"ready"); Thread.sleep(60_000)
            }
            "owner" -> {
                val worker=ProcessBuilder(command("worker",root.toString())).start()
                WorkerProcessContainment.own(worker.pid()).use {
                    Files.writeString(root.resolve("worker.pid"),worker.pid().toString())
                    // The worker waits for containment before spawning a service-like child.
                    Files.writeString(root.resolve("contained"),"ready")
                    val deadline=System.nanoTime()+8_000_000_000
                    while(!Files.exists(root.resolve("child.pid")) && System.nanoTime()<deadline) Thread.sleep(20)
                    Files.writeString(root.resolve("ready"),"ready")
                    Thread.sleep(60_000)
                }
            }
            "worker" -> {
                while(!Files.exists(root.resolve("contained"))) Thread.sleep(20)
                val child=ProcessBuilder(command("sleep",root.toString())).start()
                Files.writeString(root.resolve("child.pid"),child.pid().toString())
                Thread.sleep(60_000)
            }
            else -> Thread.sleep(60_000)
        }
    }
}
