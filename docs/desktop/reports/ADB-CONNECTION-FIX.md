# ADB 连接体验修复

日期：2026-10-25。范围：ADB 选择、发现、连接生命周期与连接界面；不改游戏业务。包含进入本任务前已有的 ADB 修复。

## 根因及证据边界

1. 既有 `ProcessAdbExecutor` 将短时间两次 daemon 启动提示人工转换为版本冲突，即使命令成功也报错。既有 `AdbLocator` 自动候选排在手动路径之前，用户选择可能未生效。本提交保留此前修复：按实际退出码与输出判定，非空手动路径独占选择，失败不静默回退。
2. `ManagedDeviceService.service()` 每次发现/连接都重新枚举进程、查注册表并执行 `adb version`；即使最终复用同一个服务，也重复支付定位成本。自动选择还可能随运行进程变化而切到另一个 ADB。
3. 底层连接原来仅用互斥锁排队，同一设备的第二个连接请求仍会停止会话、重新读取信息和测速。设备页、向导和顶栏各自管理加载状态，不能阻止其他入口触发重复请求。
4. 自动发现每次启动 MuMuManager/ldconsole 并批量向未注册的候选端口执行 `adb connect`。这些厂商程序存在显示界面的可能。本轮移除这一启动路径，但没有采集用户当次弹窗的进程信息，不能断言所有弹窗都来自它们。
5. 页面拥有的协程会随导航取消，因此仅共享进度不足以保证跨页连接。最终实现由应用级协调对象持有操作，页面离开只取消等待。

Windows 启动方式核对：项目使用 JDK 21 的 `ProcessBuilder` 默认管道；[OpenJDK 实现](https://raw.githubusercontent.com/openjdk/jdk21u/master/src/java.base/windows/native/libjava/ProcessImpl_md.c) 已使用 `CREATE_NO_WINDOW`。未额外引入 native 进程启动器、PowerShell 包装或大型依赖。查阅了 [MAA 官方连接排错说明](https://maa.plus/docs/en-us/manual/faq.html)，保留显式 ADB/地址和手动重试的操作方式；本轮实现仍使用现有 Kotlin/Compose 架构，没有移植 MAA 或执行其关闭 ADB 进程建议。

## 最终实现

- `DeviceConnectionActions` 统一设备页、向导和顶栏操作：同一时刻仅接受一个连接/发现/配置操作，重复请求不排队；保存列表、进度和单份错误，自动发现的成功、空结果、失败都按配置缓存。显式重新检测仍可用。
- 用户操作由应用级 IO 协程持有。页面等待者取消不影响在途操作；显式取消和应用关闭才终止，并等待清理。取消发现不打断已经连接的设备；取消连接或自动重连会停止相应轮询。
- 同一 ADB 配置、设备和触控模式的已就绪连接直接复用；明确“重新连接与测速”才强制重建。底层也对同设备/同模式连接做幂等保护。TCP 设备已注册且在线时不重复 `adb connect`。
- `ManagedDeviceService` 复用成功定位结果；配置变化重新定位，已连接时禁止换 ADB。显式“保存并检查”重新验证，验证失败会清除之前的成功缓存。进程枚举/定位放在 IO 线程。
- 发现保留 `adb devices -l`，对运行中 MuMu/雷电的常见本机端口先做 150 ms TCP 探测，仅向监听中的未注册端口发出连接请求。不会再启动厂商管理器。已注册的特殊端口仍会显示；未注册且不在常见列表中的实例需要手动填写地址。
- 发现和连接在设备生命周期锁内互斥，并跟踪操作以便断开/关闭取消。自动重连的状态变更放到取得锁之后，显式断开等待后台监听/重连清理。
- 设备页和顶栏都显示同一个在途操作；连接中禁止重复请求或更改配置，提供独立取消入口。失败以共享的内联提示显示，提供显式重试。切换页面不重建列表，也不再次自动探测。
- `DesktopApp.kt` 仅改连接状态订阅、顶栏设备标签与设备菜单（重连/取消/断开/错误）；未改任务选择、启动流程或完整流程交接代码。未修改 RunConsole.kt、RunConsolePresenter.kt 或 WorkPages.kt。

## 定向验证

使用项目 JDK `D:/Project/LimingjieDesktop/.tools/jdk-21.0.12.1+1`、现有 wrapper 与根 `.gradle-user-home`。以下检查分阶段执行；后续只重跑受修改影响的类，没有全量回归。

| 检查 | 结果 |
| --- | --- |
| ProcessAdbExecutorTest | 10 项通过：启动提示、真实冲突、失败正文、启动补救、进程退出/取消等 |
| AdbLocatorTest | 3 项通过：显式选择、非法选择不回退、自动候选顺序和去重 |
| AdbDeviceServiceTest | 14 项通过：原有设备行为，加重复连接不重测、不重复 TCP connect、仅探测监听端口、发现保留 Ready、取消发现 |
| DeviceConnectionActionsTest | 5 项通过：跨入口互斥、连接复用与显式重连、失败一次提示/手动重试、缓存失效、取消/关闭，以及页面离开后仍连接、显式取消才停止 |
| ManagedDeviceServiceTest | 3 项通过：定位复用、配置更新及强制复查、取消定位、失败复查清除缓存 |
| 应用 Kotlin 编译 | 通过；仅既有 painterResource 弃用警告 |

离线测试合计 35 项通过。测试用模拟设备、临时文件或 Java 模拟进程，不访问实际游戏。构建自身要求的资源校验随定向任务执行，没有追加全量构建、打包或哈希流程。

示例命令（工作目录 `desktop`）：

```powershell
$env:JAVA_HOME = 'D:\Project\LimingjieDesktop\.tools\jdk-21.0.12.1+1'
.\gradlew.bat --gradle-user-home ..\.gradle-user-home :device:test --tests com.landosol.toolbox.desktop.device.ProcessAdbExecutorTest --tests com.landosol.toolbox.desktop.device.AdbLocatorTest --tests com.landosol.toolbox.desktop.device.AdbDeviceServiceTest --console=plain
.\gradlew.bat --gradle-user-home ..\.gradle-user-home :app:test --tests com.landosol.toolbox.desktop.app.adapters.device.DeviceConnectionActionsTest --tests com.landosol.toolbox.desktop.app.adapters.device.ManagedDeviceServiceTest --console=plain
.\gradlew.bat --gradle-user-home ..\.gradle-user-home :app:compileKotlin --console=plain
```

## 本机只读连接验证

本机存在 MuMu 设备，使用已经运行的 ADB 服务对应的项目 Platform Tools，临时验证入口放在忽略目录 `.tools/adb-connection-probe`，不提交。实例使用独立数据目录、空游戏包集合、兼容触控模式，仅建立连接和读取设备状态；不安装/启动触控服务、不启动应用、不点击、不读账号数据、不落盘屏幕图像。

- Android API 32，读取尺寸 720 × 1280；首次连接成功，用时 10,532 ms。
- 再次连接同设备：复用同一个 Ready 实例，用时 0 ms（毫秒精度）。
- 显式发现完成，现有 Ready 实例保持不变。
- 整个过程 ADB 定位 1 次，测速输出 2 条（Raw/Png 各一条，即只测速一次）。
- 正常断开，最终状态 Disconnected，退出成功。
- 未执行全局 kill-server、关闭模拟器或游戏操作。

本轮没有打包/替换正在运行的程序，也没有用 GUI 自动化复现历史弹窗。UI 已编译，跨页面生命周期以定向测试验证；实机验证覆盖只读连接、重复连接复用、发现保留状态和断开，不代替完整游戏流程验收。

## 整合范围

仅提交 ADB 相关实现、测试和本报告。保留且不暂存分辨率相关 Android/shared 改动及其报告。由协调对话整合其他 UI 提交并制作更新程序；旧程序/旧发布包不会因源码提交自动更新。
