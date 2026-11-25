package com.landosol.toolbox.account

import com.landosol.toolbox.protocol.bilibili.GameServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountInputValidatorTest {
    @Test
    fun `normalizes valid account input`() {
        val result = AccountInputValidator.validate(
            alias = "  主账号  ",
            loginId = "  test@example.com ",
            password = "secret",
            gameUid = " 123456 ",
            passwordRequired = true,
            server = GameServer.CN_BILIBILI,
        )

        assertTrue(result is AccountValidationResult.Valid)
        result as AccountValidationResult.Valid
        assertEquals("主账号", result.value.alias)
        assertEquals("test@example.com", result.value.loginId)
        assertEquals("123456", result.value.gameUid)
    }

    @Test
    fun `requires password for a new account`() {
        val result = AccountInputValidator.validate(
            alias = "账号",
            loginId = "tester",
            password = "",
            gameUid = "",
            passwordRequired = true,
            server = GameServer.CN_BILIBILI,
        )

        assertEquals(AccountValidationResult.Invalid("请输入密码"), result)
    }

    @Test
    fun `sql control text remains ordinary bound data`() {
        val result = AccountInputValidator.validate(
            alias = "'; DROP TABLE accounts; --",
            loginId = "tester",
            password = "secret",
            gameUid = "",
            passwordRequired = true,
            server = GameServer.CN_BILIBILI,
        )

        assertTrue(result is AccountValidationResult.Valid)
    }

    @Test
    fun `channel access key is trimmed but a Bilibili password is kept verbatim`() {
        val channel = AccountInputValidator.validate(
            alias = "渠道号",
            loginId = " 12345678 ",
            password = " access-key\n",
            gameUid = "",
            passwordRequired = true,
            server = GameServer.CN_XIAOMI,
        ) as AccountValidationResult.Valid
        val bilibili = AccountInputValidator.validate(
            alias = "B 服号",
            loginId = "tester",
            password = " pass word ",
            gameUid = "",
            passwordRequired = true,
            server = GameServer.CN_BILIBILI,
        ) as AccountValidationResult.Valid

        assertEquals("12345678", channel.value.loginId)
        assertEquals("access-key", channel.value.password)
        assertEquals(GameServer.CN_XIAOMI, channel.value.server)
        assertEquals(" pass word ", bilibili.value.password)
        assertEquals(GameServer.CN_BILIBILI, bilibili.value.server)
    }

    @Test
    fun `changing the server requires a new password, including away from an unreadable stored value`() {
        assertTrue(accountServerChangeRequiresPassword("cn-bilibili", GameServer.CN_XIAOMI))
        assertTrue(accountServerChangeRequiresPassword("cn-from-a-newer-build", GameServer.CN_BILIBILI))
        assertEquals(false, accountServerChangeRequiresPassword("cn-xiaomi", GameServer.CN_XIAOMI))
        assertEquals(false, accountServerChangeRequiresPassword(null, GameServer.CN_XIAOMI))
    }
}
