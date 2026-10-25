# ADB 持续鼠标忙碌与重复设备修复

日期：2026-10-26。延续 ADB-CONNECTION-FIX.md；本报告替代其中关于候选端口探测、实例去重及高频调用进程方式的描述。

## 现场根因

- 协调者现场观察到助手同时创建多个短命 adb.exe；用户确认忙碌圈在桌面及其他窗口也出现，并确认正常退出助手后恢复。旧实现每 500 ms 查询前台、每四轮查询连接/启动标识/电源；展开画面还持续通过新进程截图。`CREATE_NO_WINDOW` 只解决控制台窗口，不能据此断言不会触发系统启动反馈。两种 adb.exe 都是 Console subsystem，也不能将问题简单归因于 GUI 程序。
- 本轮只读查询 16480 和 7555：两边 `cat /proc/sys/kernel/random/boot_id` 均成功（退出码 0），返回相同非空 UUID；两边 `getprop ro.serialno` 都为空。旧去重必须 serialno 与 boot_id 同时非空，因此把同一实例的别名当成两个设备。未使用型号作合并依据，报告不保存设备原始标识。
- 原发现代码将 offline 历史 transport 和在线设备一起返回，还猜测一批端口后执行 connect，扩大服务端注册列表。本轮取消这种探测与注册。

## 实际阅读的成熟源码及选择

- [MAA Win32IO.cpp](https://github.com/MaaAssistantArknights/MaaAssistantArknights/blob/dev-v2/src/MaaCore/Controller/Platform/Win32IO.cpp)：使用 STARTUPINFOEX、受限继承句柄列表、STARTF_USESTDHANDLES | STARTF_USESHOWWINDOW 和 SW_HIDE。此文件没有 STARTF_FORCEOFFFEEDBACK，不能把后者归于 MAA。
- [Windows STARTUPINFOW 官方说明](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/ns-processthreadsapi-startupinfow)：STARTF_FORCEOFFFEEDBACK 是显式关闭启动反馈的选项。本轮选择消除高频子进程，不增加一套 JNA 进程/管道封装。
- [MAA AdbLiteIO.cpp](https://github.com/MaaAssistantArknights/MaaAssistantArknights/blob/dev-v2/src/MaaCore/Controller/Platform/AdbLiteIO.cpp) 与 [adb-lite/client.cpp](https://github.com/MaaAssistantArknights/MaaAssistantArknights/blob/dev-v2/src/MaaCore/Controller/adb-lite/client.cpp)：把 devices/connect/shell/exec 映射为 ADB server 请求，通过 TCP 传输而非每次启动 adb.exe。本轮采用这一通信分层，保留现有 AdbExecutor 和业务调用。
- [MaaFramework DeviceList.cpp](https://github.com/MaaXYZ/MaaFramework/blob/main/source/MaaAdbControlUnit/General/DeviceList.cpp)、[AdbDeviceFinder.cpp](https://github.com/MaaXYZ/MaaFramework/blob/main/source/MaaToolkit/AdbDevice/AdbDeviceFinder.cpp)、[UnitBase.cpp](https://github.com/MaaXYZ/MaaFramework/blob/main/source/MaaAdbControlUnit/Base/UnitBase.cpp)：阅读了设备枚举、工具发现入口及进程通信调用；采用在线状态筛选，不直接照搬厂商工具启动和实例策略。
- [AOSP shell_protocol.h](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/shell_protocol.h)：使用 shell-v2 的通道、长度帧和退出状态。与 MAA 的 legacy shell/exec 不同，本实现对命令与截图均使用 raw shell-v2，保留独立 stderr、真实退出码和未经换行处理的二进制 stdout。

## 修改

1. 新增 `AdbServerClient`，生产 `ManagedDeviceService` 创建 `ProcessAdbExecutor(server = AdbServerClient())`。监控、设备发现、显式连接、shell 查询和 Raw/PNG 截图直接与已有 ADB server 通信；没有每帧/每次轮询启动进程。
2. 版本检查、推送/拉取文件及 MaaTouch 常驻通道仍用已有进程实现。仅在本机 ADB 服务确实拒绝连接时沿用既有最多一次 start-server 补救；不 kill-server，不全局 disconnect，不更换当前服务。协议失败不悄悄回退到进程，避免反复执行已发送命令；不支持 shell-v2 时给出更新模拟器 ADB 的明确提示。
3. socket 的总超时、读取截止时间、取消和关闭均会释放连接；解析分片帧，分离输出与错误并处理真实退出码，拒绝截断帧及异常长度。复用既有并发限制，无新增依赖。
4. 发现仅列举在线且已授权 transport，读取有效 boot UUID 合并别名，不再依赖可能为空或克隆的 ro.serialno，不按型号合并。无法读取有效标识时保守保留独立项。已连接端点优先，其余排序确定；描述信息保留别名集合，手动连接已知别名后缓存行也立即跟随选中地址。
5. 不再猜测端口、不调用模拟器管理器、不改变 ADB 全局注册列表。未注册设备由用户填写模拟器提供的地址连接；空列表提示明确说明在线/授权条件、手动地址以及离线历史隐藏，不能解释成“没有模拟器”。

## 定向验证

项目 JDK 21、根 `.gradle-user-home`，分阶段运行，仅重跑后续修改影响项：

| 测试类 | 通过 |
| --- | ---: |
| AdbDeviceServiceTest | 15 |
| AdbServerClientTest | 6 |
| ProcessAdbExecutorTest | 10 |
| DeviceConnectionActionsTest | 6 |
| ManagedDeviceServiceTest | 3 |

合计 40 项通过；应用编译通过。覆盖单实例两个别名且 serialno 为空、offline 历史不进入列表、不注册猜测端口、两个相同型号/序列号但不同 boot 的实例不误合并、身份读取失败不误合并、已连接端点稳定、shell-v2 分片/Unicode/二进制/退出码、截图失败、超时/取消/关闭、截断协议以及不支持协议不回退到进程。未做全量回归。

本机只读验证使用忽略目录 `.tools/adb-connection-probe`，通过 ManagedDeviceService 和与生产相同的 ProcessAdbExecutor(server=AdbServerClient())，仅省略游戏包元数据读取，采用兼容模式避免安装触控服务。进程工厂记录执行器每次实际启动请求：

- 两条在线 transport 合并为一个实例，别名数为 2；连接 Android API 35 实例成功。
- 监控保持运行，完成 12 次连续真实截图：新增 adb.exe 为 0，整个执行器生命周期进程启动次数为 0。截图没有落盘。
- 连续两次发现均只返回一行，地址稳定为当前连接的 `127.0.0.1:7555`。
- 正常断开并退出；没有启动/点击游戏，没有读取账号，没有改变分辨率。
- 现场验证时 server 已只返回两条在线记录，因此 offline 历史过滤由专门 fixture 证明，未通过全局清理或人为制造离线连接来伪造现场结果。
- 首次现场验证后，为保留截图失败的真实退出码，将截图也改为 raw shell-v2；对应测试与最终现场验证重新通过，上述结果来自最终实现。

## 验收边界与整合

本轮证明了列表实例合并、稳定选中端点及高频路径零新增进程。没有把“能连接”当作鼠标问题已验收；新版桌面程序的系统鼠标观察由协调者构建 desktop.3 后在用户原环境完成。没有改鼠标指针本身或掩盖系统反馈。

未修改安装目录、用户账号或模拟器状态；未关闭用户程序。版本号的两个构建文件由协调者另行提交，分辨率相关文件全部保留不暂存。本提交仅包含 ADB 实现、相关测试与修复记录。
