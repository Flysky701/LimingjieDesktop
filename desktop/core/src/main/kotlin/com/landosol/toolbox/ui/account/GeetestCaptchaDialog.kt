package com.landosol.toolbox.ui.account

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.account.AccountCaptchaState
import com.landosol.toolbox.desktop.shared.DesktopBindings

@Composable
fun GeetestCaptchaDialog(state: AccountCaptchaState, isWorking: Boolean, onSolved: (String) -> Unit, onError: (String) -> Unit, onDismiss: () -> Unit) {
    val host = DesktopBindings.services.captchaHost
    val clipboard = LocalClipboardManager.current
    var url by remember(state.challenge.challenge) { mutableStateOf<String?>(null) }
    var browserFailed by remember(state.challenge.challenge) { mutableStateOf(false) }
    val solved by rememberUpdatedState(onSolved)
    val error by rememberUpdatedState(onError)
    DisposableEffect(state.challenge.challenge) {
        val session = host.open(state.challenge, { solved(it) }, { error(it) })
        url = session.url
        browserFailed = !host.browse(session.url)
        onDispose { session.close() }
    }
    AlertDialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        title = { Text("人工安全验证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("账号：${state.accountAlias}")
                Text("验证由 Geetest 官方组件在系统浏览器中加载，请在浏览器中手动完成。登录账号和密码不会传入该页面。")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (browserFailed) Text("无法自动打开浏览器，请复制下面的链接到浏览器中打开")
                if (browserFailed) url?.let { Text(it) }
                if (isWorking) CircularProgressIndicator(Modifier.size(24.dp))
                Row {
                    TextButton(onClick = { url?.let { browserFailed = !host.browse(it) } }) { Text("在浏览器中打开") }
                    TextButton(onClick = { url?.let { clipboard.setText(AnnotatedString(it)) } }) { Text("复制链接") }
                }
            }
        },
        confirmButton = { TextButton(enabled = !isWorking, onClick = onDismiss) { Text("取消验证") } },
    )
}
