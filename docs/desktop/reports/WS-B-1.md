# WS-B 设备层实现记录

日期：2026-10-25。范围：`desktop/device/**`，基于 M1 冻结契约；没有修改 `DeviceContracts.kt`、上游业务代码或旧项目数据。

## 实现

- `ProcessAdbExecutor`：argv 进程启动、设备端逐参数 POSIX 转义、并发排空二进制 stdout / UTF-8 stderr、普通命令 4 路和独立截图通道、超时/取消/关闭杀进程树。识别约定的全部 ADB 错误，守护进程仅主动启动一次，10 秒内重复启动或版本争抢停止重试。
- `AdbLocator`：运行中的 MuMu/雷电安装路径、雷电注册表、PATH、用户路径、已下载工具目录依次查找；显式调用 `downloadPlatformTools(dataDirectory)` 才下载 Google 官方 Windows 包，验证三项所需文件与 `adb version`。发行包不包含 ADB。
- `AdbDeviceService`：设备发现和稳定标识去重、启动/API/分辨率/休眠检查、连接状态、截图故障和健康监视、触控失败单次重启、最多 12 次每隔 5 秒重连。丢失与主动断开/切换同步调用 `onLost`，重连只恢复设备；连接中断开会取消启动轮询。
- `ScreenCapturer`：12/16 字节 Raw 头和 RGBA/RGBX/BGRA → ARGB，PNG 的 ImageIO ARGB 解码；各 5 帧的 p50/p95 测速、失败模式淘汰、连接期间固定选择结果。节奏、旋转、黑帧和帧总线由 shared/app 适配。
- `MaaTouchConnection`：固定 v1.1.0 资源校验、每次部署、常驻进程、5 秒握手、单写者 Channel、设备端时间控制、精确端点及原地 hold。关闭杀设备端对应 PID 并回收本地进程；兼容 input 模式只能显式指定。
- 动作实时读取前台；500 ms 后台监视，缓存超过 2 秒返回空。未知前台拒绝动作。UI 树禁用 DTD/外部实体，上游 600 节点/深度 40 上限；idle 错误最多重试 3 次后告警。
- 应用启动解析 exit 0 的 Error/Exception，force-stop 后最多 3 秒轮询。设备画像只读取指定游戏包，用 apksig 按设备 SDK 读取当前签名证书 DER MD5；缓存键含设备/包名/版本/更新时间，临时 APK 在 finally 删除。缺少可验证签名使连接明确失败，避免向登录层交付不完整画像。

## 接线入口

```kotlin
AdbLocator.find(configuredPath: String? = null, downloadedDirectory: Path? = null): AdbLocation?
AdbLocator.downloadPlatformTools(dataDirectory: Path): AdbLocation
ProcessAdbExecutor(executable: String)
AdbDeviceService(adb, dataDirectory, gamePackages, onEvent = {}, onLost = {})
```

定位/下载函数与设备 I/O 为 suspend。`AdbDeviceService.close()` 同时关闭其持有的 ADB 执行器。应用须把 `onLost` 接到已有任务停止路径，不在 Ready 观察器中恢复任务。测试可额外传入 `TouchConnectionFactory`，生产默认真实 MaaTouch。

## 验证

使用项目 JDK 21 和 Gradle 缓存，执行 `:device:test`；首次 26 项中一项合成子进程默认输出编码断言失败，修正 fixture 为显式 UTF-8 后定向复跑。随后新增的签名及生命周期场景分别执行对应测试类。最终 **31 项用例均已按相关类通过**，没有跳过项；补充覆盖关闭时取消正在重启的触控服务。

- 实际子进程：64 MiB stdout 与 8 MiB stderr 同时排空、超时杀父子进程、取消杀进程、普通并发上限与截图独立通道、全部错误文案、守护进程重试上限；含中文与空格的 argv 和设备端 shell 引号验证。
- 截图：两种头长、三种像素格式、截断/无效头/未知格式、PNG、测速失败模式淘汰。
- MaaTouch：固定资源 SHA-256、握手、逐字节手势、并发动作完整写入、进程退出。
- 业务边界：实时前台优先、Unknown 拒绝、坐标/时长/兼容模式、休眠、XML/XXE、uiautomator 重试、启动 Error、force-stop、发现去重、连续失败丢失和重新连接保持停止、连接中关闭。
- 签名：测试临时生成专用密钥与合成 APK，通过真正 apksig 验证 v1/v2/v3，检查证书 DER MD5、同版本缓存、更新时间失效、仅拉 base.apk 和事后删除。测试材料不含真实游戏/账号信息。
- `verifyMaaTouch` 为资源打包前置项；Apache-2.0 许可证和来源随 device JAR 的 `META-INF/licenses/` 打包。

## 实测边界

没有连接用户模拟器或游戏，没有执行真实触控、游戏启动/停止、账号登录或付费请求。`fixtures/android9` 与 `fixtures/android12` 均明确标注为合成；MuMuManager/ldconsole 格式、设备 Raw 头、MaaTouch 握手坐标、前台查询 p95、渠道 UI、force-stop 是否保留渠道登录态仍需 L1/L3 实机验证。它们不因离线测试通过而标成实机完成。官方 platform-tools 下载入口已实现，但本次未实际下载/连接 ADB。

追加（2026-10-25）：已完成 MuMu Android 12/API32 定向实机验证，真实发现并修复 MaaTouch 清理命令自匹配问题；新增实采 parser 用例通过，并补了负坐标、0/5001 ms 与超屏透传的既有用例断言。实机范围、数据、节点树间歇失败、L2 标题动画误报和恢复记录以 L1-MuMu.md 为准，不再将该环境全部列为未实测。Android 9/雷电仍未验证。
