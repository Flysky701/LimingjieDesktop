package com.landosol.toolbox.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.landosol.toolbox.protocol.bilibili.CaptchaChallenge
import com.landosol.toolbox.protocol.bilibili.GameServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AccountEditorState(
    val id: Long? = null,
    val alias: String = "",
    val loginId: String = "",
    val password: String = "",
    val gameUid: String = "",
    /** 必须由用户选择：新增账号没有默认服务器，读不懂原值的账号也要重新选。 */
    val server: GameServer? = null,
    /** 编辑时账号表里原样存着的服务器值；新增时为 null。 */
    val savedServerId: String? = null,
) {
    val isEditing: Boolean get() = id != null

    /** 与仓库写入共用同一个判定：换服务器（含从读不懂的旧值改成任何服务器）必须重填密码。 */
    val serverChanged: Boolean
        get() = server != null && accountServerChangeRequiresPassword(savedServerId, server)
}

data class AccountUiState(
    val accounts: List<AccountListItem> = emptyList(),
    val editor: AccountEditorState? = null,
    val deleteCandidate: AccountListItem? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
)

data class AccountCaptchaState(
    val accountId: Long,
    val accountAlias: String,
    val challenge: CaptchaChallenge,
    val error: String? = null,
)

private data class AccountChromeState(
    val editor: AccountEditorState? = null,
    val deleteCandidate: AccountListItem? = null,
    val isWorking: Boolean = false,
    val message: String? = null,
)

class AccountViewModel(
    private val repository: AccountRepository,
) : ViewModel() {
    private val chrome = MutableStateFlow(AccountChromeState())

    val uiState = combine(repository.observeAccounts(), chrome) { accounts, chromeState ->
        AccountUiState(
            accounts = accounts,
            editor = chromeState.editor,
            deleteCandidate = chromeState.deleteCandidate,
            isWorking = chromeState.isWorking,
            message = chromeState.message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AccountUiState(),
    )

    fun openNewAccount() {
        chrome.update { it.copy(editor = AccountEditorState(), message = null) }
    }

    fun openEditAccount(id: Long) {
        runOperation(closeEditorOnSuccess = false) {
            val value = repository.loadEditor(id)
            chrome.update {
                it.copy(
                    editor = AccountEditorState(
                        id = value.id,
                        alias = value.alias,
                        loginId = value.loginId,
                        password = "",
                        gameUid = value.gameUid,
                        server = value.server,
                        savedServerId = value.savedServerId,
                    ),
                )
            }
        }
    }

    fun updateEditor(transform: (AccountEditorState) -> AccountEditorState) {
        chrome.update { current -> current.copy(editor = current.editor?.let(transform), message = null) }
    }

    fun dismissEditor() {
        if (!chrome.value.isWorking) chrome.update { it.copy(editor = null, message = null) }
    }

    fun saveEditor() {
        val editor = chrome.value.editor ?: return
        val server = editor.server ?: run {
            chrome.update { it.copy(message = "请选择服务器") }
            return
        }
        when (
            val validation = AccountInputValidator.validate(
                alias = editor.alias,
                loginId = editor.loginId,
                password = editor.password,
                gameUid = editor.gameUid,
                passwordRequired = !editor.isEditing || editor.serverChanged,
                server = server,
            )
        ) {
            is AccountValidationResult.Invalid -> chrome.update { it.copy(message = validation.message) }
            is AccountValidationResult.Valid -> runOperation(closeEditorOnSuccess = true) {
                if (editor.id == null) {
                    repository.create(validation.value)
                } else {
                    repository.update(editor.id, validation.value)
                }
            }
        }
    }

    fun selectAccount(id: Long) = runOperation { repository.select(id) }

    fun requestDelete(account: AccountListItem) {
        chrome.update { it.copy(deleteCandidate = account, message = null) }
    }

    fun dismissDelete() {
        if (!chrome.value.isWorking) chrome.update { it.copy(deleteCandidate = null) }
    }

    fun confirmDelete() {
        val account = chrome.value.deleteCandidate ?: return
        runOperation {
            repository.delete(account.id)
            chrome.update { it.copy(deleteCandidate = null) }
        }
    }

    fun dismissMessage() {
        chrome.update { it.copy(message = null) }
    }

    private fun runOperation(
        closeEditorOnSuccess: Boolean = false,
        operation: suspend () -> Unit,
    ) {
        if (chrome.value.isWorking) return
        viewModelScope.launch {
            chrome.update { it.copy(isWorking = true, message = null) }
            runCatching { operation() }
                .onSuccess {
                    chrome.update { current ->
                        current.copy(
                            editor = if (closeEditorOnSuccess) null else current.editor,
                            isWorking = false,
                        )
                    }
                }
                .onFailure {
                    chrome.update { current ->
                        current.copy(
                            isWorking = false,
                            message = safeMessage(it),
                        )
                    }
                }
        }
    }

    private fun safeMessage(failure: Throwable): String = when {
        failure is IllegalArgumentException && !failure.message.isNullOrBlank() -> failure.message.orEmpty()
        failure.message?.contains("UNIQUE", ignoreCase = true) == true -> "同一服务器下已存在这个账号名称"
        else -> "操作失败，请稍后重试"
    }

    class Factory(
        private val repository: AccountRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: kotlin.reflect.KClass<T>, extras: androidx.lifecycle.viewmodel.CreationExtras): T {
            require(modelClass.java.isAssignableFrom(AccountViewModel::class.java))
            return AccountViewModel(repository) as T
        }
    }
}
