# MuMu 实机定向验证

日期：2026-10-25。用户已授权计划内测试。对象是本机 MuMu 的 Android 12 实例 0（API 32），原状态为关闭；ADB 36.0.0。测试使用独立 `.tools/l1-probe` 和 `.tools/l2-probe` 数据目录，不读取旧项目账号，不输入凭据，不执行付费业务。

## 已完成的 L1

- 自动发现 `127.0.0.1:16384`、真实 DeviceService 连接 Ready、MaaTouch 常驻启动均通过。首次实机揭示 `pkill -f` 会匹配自己的 shell 命令并 exit 143，已在 f056b03 改为不会自匹配的正则，并通过真实连接/动作验证。
- 首次成功连接 Raw 5 帧 p50/p95 为 237/250 ms，PNG 为 504/591 ms。后续连接遇到 PNG p95 626 ms 时实际出现“截图耗时较长，可尝试调整模拟器渲染模式或关闭高帧率”警告。
- 系统设置中的 MaaTouch Tap 打开“声音”子页，Back 返回；400 ms 上滑后列表显示“显示/壁纸/无障碍”，400 ms 加 250 ms hold 的下滑返回列表顶端。截图已目视核对，滑动前后节点树分别为 47/49/47 节点。声音子页 dump 失败，不能声称每个点击前后都有节点树证明。
- 实际横屏截图 1280×720，wm 报告 720×1280，设备接受转置尺寸，实际坐标正确；显示上游只在 1920×1080 验证过的提示。没有修改模拟器分辨率或旋转设置。
- 前台实时查询 20 次，p50 100.87 ms、p95 128.43 ms；Home 后缓存 210 ms 内切换，随后针对设置的触控被拒绝。设置的 resolve/start/force-stop 流程成功。
- 每次 probe 正常退出均检查 `ps -A -o PID,ARGS`：MaaTouch 进程为 0。运行时已确认该命令能看见真实 MaaTouch 进程，零结果不是进程名匹配错误。

## 节点树与 fixture

初次设置根页 dump 成功（47 节点），随后声音页出现 idle/null-root 错误；后续静止根页也出现 `ERROR: null root node returned by UiTestAutomationBridge`，直接使用模拟器自带 adb 复查结果一致。生产设备层按现有语义返回空列表并告警，没有读旧 XML 冒充成功。追加设置页节点确认模式因该问题失败，明确保留失败记录。

`desktop/device/src/test/resources/fixtures/android12-live/` 保存真实设置页 XML、前台两行、wakefulness 和 wm size；来源和采集时刻见该目录 SOURCES.md。新增解析测试 `ScreenAndParserTest#actualMumu12SettingsDumpPreservesTheInspectedNodes` 定向运行通过。原 `android9/`、`android12/` 合成边界样本继续明确标注合成；未拥有 Android 9/雷电实机证据。

## 连续截图、兼容模式与 L2

最终数据见本文“最终实测结果”。此次 L1 不等同于完整 G6：还没有真实 Android 9、旋转设置切换、锁屏、12 次耗尽重连以及所有渠道登录场景证据。

## 复现与文件

显式手工入口：`:device:probeDevice -PprobeAdb=<adb.exe> -PprobeSerial=<serial> -PprobeOutput=<隔离目录> -PprobeMode=inspect|actions|settings|compatibility|soak`。普通 test/check 从不运行该探针，也不接触设备。`actions` 选择系统设置的显示/声音行；`compatibility` 使用本次实采 1280×720 设置根页的存储行 bounds，先保留截图供核对。

原始日志和设置页截图保留在本机 `.tools/l1-probe/`；游戏截图仅留 `.tools/l2-probe/`，不提交仓库。完整项目单元测试不因这次实机验证重复执行；新增实采 parser 用例、动作参数边界各做了对应方法的一次定向验证。

## 最终实测结果（追加）

- 连续截图通过：18:52–19:02，截图循环实际运行 600 秒，1989 帧，无丢失连接；起帧间隔 p50=301.2124 ms、p95=302.4555 ms。该次连接 Raw p50/p95=233/250 ms，PNG=220/313 ms，按 p50 选择 PNG 并固定全程；没有 Raw 失败淘汰或运行中切换。不要拿较早一次不同页面/冷启动的 PNG 504 ms 与本轮选择混淆。
- GC 后 heap 从第100帧的 6,699,152 字节，到第1900帧的 6,788,328 字节；中途有回落。增长约89 KB，其中探针本身保存帧间隔列表。没有观察到持续大幅增长；这不替代1小时或8小时 soak。退出后 MaaTouch=0。命令 exit 0，日志 `docs/desktop/evidence/l1/soak.log`。
- 兼容模式通过最短路径：Tap 进入存储页、Back、hold=0 的 Swipe 列表滚动均经截图核对，hold=250 的请求被规定文案拒绝。窗口树最初仍报 null-root，但滚动后恢复；其后连续20次 uiautomator dump：成功20、idle错误0、null-root错误0。此前声音页/根页的失败仍保留，因此不能把整个过程表述为始终正常。命令 exit 0，日志 `compatibility.log`。
- L2 仅通过连接及短时只读装配：真实 Graph + 最终 c09a3ea 的 PP-OCRv5，B服包 `com.bilibili.priconne`，版本11.7.2/352，apksig读取证书MD5为 `4502a02a00395dec05a4134ad593224d`，前台正确。启动游戏后停在“点击屏幕开始游戏”的标题页；不点击、不输入凭据、未访问账号或凭据存储。
- L2 20秒 dryRun 处理46帧，预览存在；Graph关闭289 ms，退出后MaaTouch无残留。分类集合 `UNKNOWN / TITLE_WAITING_TAP / BATTLE_FAILED`；目视首尾仍是标题动画，说明1280×720标题动画存在战斗失败误报，不能把这次短测算作L2各场景识别通过。未为绕过此结果修改上游识别/策略。游戏截图有ID，只留本机 `.tools/l2-probe/`，不提交。
- 不具备黎明界内各画面或登录/验证码协助条件，所以未继续游戏内流程，也没有把停在标题页1小时当作游戏soak。
- 恢复：未更改分辨率、旋转、系统设置值；结束时 wm 仍为720×1280。Settings已force-stop，Graph及触控服务已关闭；MuMu实例0通过官方Manager shutdown恢复关闭。设备列表为空后停止了本次查询自动启动的ADB守护进程（最初该进程未运行），本次没有启动其他实例。

证据：`docs/desktop/evidence/l1/`保留普通设置截图及原始日志；`inspect/actions/soak/compatibility`命令成功，`settings`的补充节点确认命令因null-root失败并据实记录。真实Graph只读摘要另见 `readonly-report.txt`，没有完整identity或真实账号截图。

