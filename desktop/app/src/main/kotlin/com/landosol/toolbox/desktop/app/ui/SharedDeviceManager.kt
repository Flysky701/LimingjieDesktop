package com.landosol.toolbox.desktop.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.landosol.toolbox.desktop.app.control.ControlSessionUi
import com.landosol.toolbox.desktop.device.AdbLocation

@Composable internal fun SharedDeviceManager(
    global:ControlSessionUi,devices:List<ManagedDeviceUi>,callbacks:SharedRunShellCallbacks,openRun:(String)->Unit,
    runtimes:Map<String,ControlSessionUi> = emptyMap(),
) {
    val accounts by global.accounts.uiState.collectAsState()
    val settings by global.settings.state.collectAsState()
    val actions=rememberActions(global)
    var address by remember{mutableStateOf("")}
    var binding by remember{mutableStateOf<String?>(null)}
    var details by remember{mutableStateOf<String?>(null)}
    var advanced by remember{mutableStateOf(false)}
    var adbPath by remember(settings.adbPath){mutableStateOf(settings.adbPath)}
    var location by remember{mutableStateOf<AdbLocation?>(null)}
    val detailView=details?.let{runtimes[it]}
    if(detailView!=null){
        val state by detailView.ux.console.collectAsState()
        Column(Modifier.fillMaxSize()){
            TextButton(onClick={details=null}){Text("← 返回设备管理")}
            CompositionLocalProvider(LocalTaskBusy provides (state.busy||detailView.busy)){
                Box(Modifier.weight(1f)){DevicesPage(detailView,managed=true)}
            }
        }
        return
    }
    PageBody("设备管理","集中连接模拟器并绑定账号。运行中的设备锁定绑定；切换运行页不会改变连接和任务。"){
        Message(actions.message)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
            Button(onClick={callbacks.discover(true)},shape=MaterialTheme.shapes.small){Text("重新检测")}
            OutlinedTextField(address,{address=it},label={Text("手动 ADB 地址")},placeholder={Text("127.0.0.1:16384")},singleLine=true,modifier=Modifier.weight(1f))
            OutlinedButton(onClick={callbacks.connect(address.trim())},enabled=address.isNotBlank(),shape=MaterialTheme.shapes.small){Text("添加并连接")}
        }
        if(devices.isEmpty())Panel("尚未发现设备"){
            Text("启动模拟器并打开 ADB 调试，然后重新检测；也可以填写它的 ADB 地址。",color=Muted)
            Text("可以先在账号和策略页完成全局配置。",color=Muted)
        }
        devices.forEach{device->key(device.id){
            val account=accounts.accounts.firstOrNull{it.id==device.boundAccountId}
            Panel(device.name){
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Column(Modifier.weight(1f)){
                        Text(device.serial,style=MaterialTheme.typography.bodySmall,color=Muted)
                        Text("绑定账号：${account?.alias ?: "未绑定"}",style=MaterialTheme.typography.bodyMedium)
                    }
                    StatusBadge(device.status)
                }
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                    if(!device.connected)Button(onClick={
                        device.sessionId?.let(callbacks.reconnectSession) ?: callbacks.connect(device.serial)
                    },enabled=!device.busy&&!device.connecting,shape=MaterialTheme.shapes.small){Text(when{device.connecting->"正在连接…";device.sessionId==null->"连接";else->"重新连接"})}
                    device.sessionId?.let{id->
                        Box{
                            OutlinedButton(onClick={binding=device.id},enabled=device.connected&&!device.busy,shape=MaterialTheme.shapes.small){Text(if(account==null)"绑定账号" else "更换绑定")}
                            DropdownMenu(binding==device.id&&LocalControlVisible.current,{binding=null}){
                                accounts.accounts.forEach{candidate->DropdownMenuItem(text={Text("${if(candidate.id==device.boundAccountId)"✓ " else ""}${candidate.alias} · ${candidate.serverName}")},onClick={binding=null;callbacks.bindAccount(id,candidate.id)})}
                                if(device.boundAccountId!=null)DropdownMenuItem(text={Text("解除绑定")},onClick={binding=null;callbacks.bindAccount(id,null)})
                            }
                        }
                        TextButton(onClick={openRun(id)}){Text(if(device.busy)"查看运行" else "打开运行页")}
                        if(runtimes[id]!=null)TextButton(onClick={details=id}){Text("设备详情")}
                        TextButton(onClick={callbacks.disconnect(id)},enabled=device.connected&&!device.busy){Text("断开")}
                    }
                }
                if(device.busy)Text("运行期间不能换绑或断开；其他设备可继续操作。",color=Muted,style=MaterialTheme.typography.bodySmall)
            }
        }}
        Text("同一模拟器的多个 ADB 地址合并显示。绑定只决定助手使用的凭据，不会切换游戏内账号。",color=Muted,style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={advanced=!advanced}){Text(if(advanced)"收起高级设置" else "高级：ADB 工具与路径")}
        if(advanced)Panel("ADB 工具"){
            val canChange=devices.none{it.connected||it.busy}&&!actions.working
            OutlinedTextField(adbPath,{adbPath=it},label={Text("adb.exe 路径（留空自动查找）")},singleLine=true,enabled=canChange,modifier=Modifier.fillMaxWidth())
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={chooseFile(false,"选择 adb.exe","exe")?.let{adbPath=it.absolutePath}},enabled=canChange){Text("选择文件")}
                Button(onClick={actions.run{location=global.configureAdb(adbPath.trim())}},enabled=canChange){Text("保存并检查")}
                OutlinedButton(onClick={actions.run{val found=global.downloadAdb();location=found;adbPath=found.path}},enabled=canChange){Text("下载 Platform Tools")}
            }
            location?.let{Detail("来源",it.source);Detail("版本",it.version)}
            Text("更换 ADB 前请先断开设备。下载使用 Android 官方 Platform Tools。",color=Muted,style=MaterialTheme.typography.bodySmall)
        }
    }
}
