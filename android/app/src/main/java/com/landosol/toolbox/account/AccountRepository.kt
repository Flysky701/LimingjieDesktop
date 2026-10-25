package com.landosol.toolbox.account

import androidx.room.withTransaction
import com.landosol.toolbox.data.local.AccountEntity
import com.landosol.toolbox.data.local.AppDatabase
import com.landosol.toolbox.security.AccountCredentials
import com.landosol.toolbox.security.CredentialStore
import com.landosol.toolbox.protocol.bilibili.AccountLoginMaterial
import com.landosol.toolbox.protocol.bilibili.GameServer
import com.landosol.toolbox.protocol.bilibili.SdkSessionStore
import com.landosol.toolbox.protocol.bilibili.GameSessionRegistry
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class AccountListItem(
    val id: Long,
    val alias: String,
    val serverName: String,
    val gameUid: String?,
    val isSelected: Boolean,
)

data class AccountEditorData(
    val id: Long,
    val alias: String,
    val loginId: String,
    val gameUid: String,
    /** 读不懂账号表里的服务器值时为 null，编辑时必须重新选择。 */
    val server: GameServer?,
    /** 账号表里原样存着的服务器值。 */
    val savedServerId: String,
)

/** 选中账号的服务器。和「没有选中账号」区分开，读不懂的值也单独表示。 */
sealed interface SelectedAccountServer {
    data object NoAccount : SelectedAccountServer
    data class Known(val server: GameServer) : SelectedAccountServer
    data class Unknown(val storageId: String) : SelectedAccountServer
}

internal fun serverDisplayName(storageId: String): String =
    GameServer.fromStorageId(storageId)?.displayName ?: "未知服务器（$storageId）"

class AccountRepository(
    private val database: AppDatabase,
    private val credentialStore: CredentialStore,
    private val sessionStore: SdkSessionStore,
    private val gameSessionRegistry: GameSessionRegistry,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Desktop runtime projections send authenticated UID changes to their sole data owner. */
    private val onGameUidUpdated: (suspend (Long, String) -> Unit)? = null,
) {
    fun observeAccounts(): Flow<List<AccountListItem>> = database.accountDao().observeAll().map { accounts ->
        accounts.map { entity ->
            AccountListItem(
                id = entity.id,
                alias = entity.alias,
                serverName = serverDisplayName(entity.serverId),
                gameUid = entity.gameUid,
                isSelected = entity.isSelected,
            )
        }
    }

    /** 选中账号所属的服务器，决定自动化要操作哪个游戏客户端。 */
    fun observeSelectedServer(): Flow<SelectedAccountServer> = database.accountDao().observeAll()
        .map { accounts ->
            val selected = accounts.firstOrNull { it.isSelected }
            when {
                selected == null -> SelectedAccountServer.NoAccount
                else -> GameServer.fromStorageId(selected.serverId)
                    ?.let { SelectedAccountServer.Known(it) }
                    ?: SelectedAccountServer.Unknown(selected.serverId)
            }
        }
        .distinctUntilChanged()

    suspend fun loadEditor(id: Long): AccountEditorData {
        val account = requireNotNull(database.accountDao().getById(id)) { "账号不存在" }
        val credentials = requireNotNull(credentialStore.read(account.credentialKey)) { "账号凭据不可用" }
        return AccountEditorData(
            id = account.id,
            alias = account.alias,
            loginId = credentials.loginId,
            gameUid = account.gameUid.orEmpty(),
            server = GameServer.fromStorageId(account.serverId),
            savedServerId = account.serverId,
        )
    }

    suspend fun loadLoginMaterial(id: Long): AccountLoginMaterial {
        val account = requireNotNull(database.accountDao().getById(id)) { "账号不存在" }
        val credentials = requireNotNull(credentialStore.read(account.credentialKey)) { "账号凭据不可用" }
        return AccountLoginMaterial(
            accountId = account.id,
            credentialKey = account.credentialKey,
            loginId = credentials.loginId,
            password = credentials.password,
            server = requireNotNull(GameServer.fromStorageId(account.serverId)) {
                "${serverDisplayName(account.serverId)}：请在账号编辑里重新选择服务器并填写密码"
            },
        )
    }

    suspend fun create(input: NormalizedAccountInput): Long {
        val credentialKey = UUID.randomUUID().toString()
        credentialStore.save(credentialKey, AccountCredentials(input.loginId, input.password))
        return try {
            database.withTransaction {
                val now = clock()
                database.accountDao().insert(
                    AccountEntity(
                        alias = input.alias,
                        serverId = input.server.storageId,
                        gameUid = input.gameUid,
                        credentialKey = credentialKey,
                        isSelected = database.accountDao().count() == 0,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        } catch (failure: Throwable) {
            credentialStore.delete(credentialKey)
            throw failure
        }
    }

    suspend fun update(id: Long, input: NormalizedAccountInput) {
        val account = requireNotNull(database.accountDao().getById(id)) { "账号不存在" }
        // 凭据含义随服务器而变（B 服密码 / 渠道服 access_key）：换服务器必须同时给出新密码，
        // 且旧服务器的 SDK / 游戏会话一律作废。
        val serverChanged = accountServerChangeRequiresPassword(account.serverId, input.server)
        require(!serverChanged || input.password.isNotEmpty()) { "更换服务器需要重新填写密码" }
        val previous = requireNotNull(credentialStore.read(account.credentialKey)) { "账号凭据不可用" }
        val replacement = AccountCredentials(
            loginId = input.loginId,
            password = input.password.ifEmpty { previous.password },
        )
        val credentialsChanged = serverChanged || replacement != previous
        val previousSession = if (credentialsChanged) sessionStore.read(account.credentialKey) else null
        try {
            if (credentialsChanged) sessionStore.delete(account.credentialKey)
            if (credentialsChanged) gameSessionRegistry.delete(account.id)
            credentialStore.save(account.credentialKey, replacement)
            database.accountDao().update(
                account.copy(
                    alias = input.alias,
                    serverId = input.server.storageId,
                    gameUid = input.gameUid,
                    updatedAt = clock(),
                ),
            )
        } catch (failure: Throwable) {
            credentialStore.save(account.credentialKey, previous)
            if (previousSession != null) sessionStore.save(account.credentialKey, previousSession)
            throw failure
        }
    }

    suspend fun select(id: Long) {
        database.accountDao().selectAccount(id, clock())
    }

    suspend fun updateGameUid(id: Long, gameUid: String) {
        require(gameUid.isNotBlank()) { "游戏 UID 不能为空" }
        val account = requireNotNull(database.accountDao().getById(id)) { "账号不存在" }
        onGameUidUpdated?.invoke(id, gameUid)
        database.accountDao().update(account.copy(gameUid = gameUid, updatedAt = clock()))
    }

    suspend fun delete(id: Long) {
        val account = requireNotNull(database.accountDao().getById(id)) { "账号不存在" }
        val previous = credentialStore.read(account.credentialKey)
        val previousSession = sessionStore.read(account.credentialKey)
        try {
            credentialStore.delete(account.credentialKey)
            sessionStore.delete(account.credentialKey)
            gameSessionRegistry.delete(account.id)
            database.withTransaction {
                check(database.accountDao().deleteById(id) == 1) { "账号删除失败" }
                if (account.isSelected) {
                    database.accountDao().getFirst()?.let { replacement ->
                        database.accountDao().selectAccount(replacement.id, clock())
                    }
                }
            }
        } catch (failure: Throwable) {
            if (previous != null) credentialStore.save(account.credentialKey, previous)
            if (previousSession != null) sessionStore.save(account.credentialKey, previousSession)
            throw failure
        }
    }
}
