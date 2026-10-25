package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.control.*
import com.landosol.toolbox.desktop.platform.BrowserLauncher
import kotlinx.serialization.json.*

internal val LocalControlName=staticCompositionLocalOf{"当前标签"}

@Composable internal fun RemoteCaptchaDialog(session:ControlSessionUi) {
    val ticket by session.captcha.collectAsState()
    val value=ticket ?: return
    val id=value["ticketId"]?.jsonPrimitive?.contentOrNull ?: return
    val url=value["url"]?.jsonPrimitive?.contentOrNull
    val actions=rememberActions(session)
    fun cancel(){actions.run{session.request(ControlOps.CAPTCHA_CANCEL,buildJsonObject{put("ticketId",id)})}}
    AlertDialog(onDismissRequest={if(!actions.working)cancel()},title={Text("人工验证 · ${LocalControlName.current}")},text={
        Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("账号：${value["accountAlias"]?.jsonPrimitive?.contentOrNull ?: "当前账号"}")
            Text("请在浏览器中手动完成验证。此入口只属于当前标签，其他标签的任务不受影响。")
            value["error"]?.jsonPrimitive?.contentOrNull?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            Message(actions.message)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick={url?.let(BrowserLauncher::open)},enabled=url!=null){Text("在浏览器打开")}
                TextButton(onClick={url?.let(::copyText)},enabled=url!=null){Text("复制链接")}
            }
        }
    },confirmButton={TextButton(onClick=::cancel,enabled=!actions.working){Text("取消验证")}})
}

@Composable internal fun ReportControlDirty(part:String,dirty:Boolean) {
    val callback by rememberUpdatedState(LocalControlDirty.current)
    LaunchedEffect(part,dirty){callback(part,dirty)}
    DisposableEffect(part){onDispose{callback(part,false)}}
}
