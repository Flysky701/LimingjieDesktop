package com.landosol.toolbox.account

import com.landosol.toolbox.protocol.bilibili.GameServer

data class NormalizedAccountInput(
    val alias: String,
    val loginId: String,
    val password: String,
    val gameUid: String?,
    val server: GameServer,
)

/**
 * 账号的凭据含义随服务器而变（B 服密码 / 渠道服 access_key），所以换服务器必须重填密码。
 * [savedStorageId] 是账号表里原样存着的值（可能是读不懂的旧值），新建账号时为 null。
 * 编辑界面和仓库写入都用这一个判定，避免两处各写一遍。
 */
fun accountServerChangeRequiresPassword(savedStorageId: String?, selected: GameServer): Boolean =
    savedStorageId != null && savedStorageId != selected.storageId

sealed interface AccountValidationResult {
    data class Valid(val value: NormalizedAccountInput) : AccountValidationResult
    data class Invalid(val message: String) : AccountValidationResult
}

object AccountInputValidator {
    fun validate(
        alias: String,
        loginId: String,
        password: String,
        gameUid: String,
        passwordRequired: Boolean,
        server: GameServer,
    ): AccountValidationResult {
        val normalizedAlias = alias.trim()
        val normalizedLoginId = loginId.trim()
        val normalizedUid = gameUid.trim().ifEmpty { null }
        // 渠道服的密码是 access_key，不含空白；粘贴时混入的首尾空白会让游戏服拒绝会话。
        // B 服密码保持原样，不替用户改动。
        val normalizedPassword = if (server.isChannelServer) password.trim() else password

        return when {
            normalizedAlias.isEmpty() -> AccountValidationResult.Invalid("请输入账号名称")
            normalizedAlias.length > 40 -> AccountValidationResult.Invalid("账号名称不能超过 40 个字符")
            normalizedAlias.any(Char::isISOControl) -> AccountValidationResult.Invalid("账号名称包含不可用字符")
            normalizedLoginId.isEmpty() -> AccountValidationResult.Invalid("请输入登录账号")
            normalizedLoginId.length > 128 -> AccountValidationResult.Invalid("登录账号不能超过 128 个字符")
            normalizedLoginId.any(Char::isISOControl) -> AccountValidationResult.Invalid("登录账号包含不可用字符")
            passwordRequired && normalizedPassword.isEmpty() -> AccountValidationResult.Invalid("请输入密码")
            normalizedPassword.length > 512 -> AccountValidationResult.Invalid("密码长度超出限制")
            normalizedUid != null && normalizedUid.length > 64 -> AccountValidationResult.Invalid("游戏 UID 不能超过 64 个字符")
            normalizedUid?.any(Char::isISOControl) == true -> AccountValidationResult.Invalid("游戏 UID 包含不可用字符")
            else -> AccountValidationResult.Valid(
                NormalizedAccountInput(
                    alias = normalizedAlias,
                    loginId = normalizedLoginId,
                    password = normalizedPassword,
                    gameUid = normalizedUid,
                    server = server,
                ),
            )
        }
    }
}
