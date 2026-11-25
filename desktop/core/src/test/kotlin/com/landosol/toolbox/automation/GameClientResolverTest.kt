package com.landosol.toolbox.automation

import com.landosol.toolbox.account.SelectedAccountServer
import com.landosol.toolbox.protocol.bilibili.GameServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameClientResolverTest {
    private val bilibili = GameServer.CN_BILIBILI.packageName
    private val xiaomi = GameServer.CN_XIAOMI.packageName

    @Test
    fun `selected account decides the client even when several are installed`() {
        assertEquals(
            GameClientResolution.Available(GameServer.CN_XIAOMI),
            GameClientResolver.resolve(SelectedAccountServer.Known(GameServer.CN_XIAOMI), setOf(bilibili, xiaomi)),
        )
    }

    @Test
    fun `missing client of the selected account is reported, never swapped for another channel`() {
        assertEquals(
            GameClientResolution.NotInstalled(GameServer.CN_HUAWEI),
            GameClientResolver.resolve(SelectedAccountServer.Known(GameServer.CN_HUAWEI), setOf(xiaomi)),
        )
    }

    @Test
    fun `without a selected account a single install is used`() {
        assertEquals(
            GameClientResolution.Available(GameServer.CN_XIAOMI),
            GameClientResolver.resolve(SelectedAccountServer.NoAccount, setOf(xiaomi)),
        )
        assertEquals(
            GameClientResolution.Available(GameServer.CN_BILIBILI),
            GameClientResolver.resolve(SelectedAccountServer.NoAccount, setOf(bilibili)),
        )
    }

    @Test
    fun `without a selected account nothing installed is unavailable`() {
        assertEquals(GameClientResolution.Unavailable, GameClientResolver.resolve(SelectedAccountServer.NoAccount, emptySet()))
    }

    @Test
    fun `without a selected account several installs are ambiguous`() {
        val resolution = GameClientResolver.resolve(SelectedAccountServer.NoAccount, setOf(bilibili, xiaomi))
        assertTrue(resolution is GameClientResolution.Ambiguous)
        assertEquals(2, (resolution as GameClientResolution.Ambiguous).servers.size)
    }

    @Test
    fun `storage ids and package names are unique`() {
        assertEquals(GameServer.entries.size, GameServer.entries.map { it.storageId }.toSet().size)
        assertEquals(GameServer.entries.size, GameServer.entries.map { it.packageName }.toSet().size)
    }

    @Test
    fun `only the Bilibili server uses the Bilibili SDK login`() {
        assertFalse(GameServer.CN_BILIBILI.isChannelServer)
        assertTrue(GameServer.entries.filter { it != GameServer.CN_BILIBILI }.all { it.isChannelServer })
    }

    @Test
    fun `unknown storage id is never read as Bilibili`() {
        assertEquals(GameServer.CN_BILIBILI, GameServer.fromStorageId("cn-bilibili"))
        assertEquals(GameServer.CN_XIAOMI, GameServer.fromStorageId("cn-xiaomi"))
        assertNull(GameServer.fromStorageId("cn-unknown"))
        assertNull(GameServer.fromStorageId(null))
    }

    @Test
    fun `selected account with an unreadable server is not guessed from the installed clients`() {
        assertEquals(
            GameClientResolution.UnknownServer("cn-unknown"),
            GameClientResolver.resolve(SelectedAccountServer.Unknown("cn-unknown"), setOf(xiaomi)),
        )
    }
}
