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
- L2 20秒 dryRun 处理46帧，预览存在；Graph关闭289 ms，退出后MaaTouch无残留。分类集合 `UNKNOWN / TITLE_WAITING_TAP / BATTLE_FAILED`；目视首尾仍是标题动画，说明标题动画存在战斗失败误报（本轮1280×720；后续1920×1080亦重现，见下文），不能把这次短测算作L2各场景识别通过。未为绕过此结果修改上游识别/策略。游戏截图有ID，只留本机 `.tools/l2-probe/`，不提交。
- 不具备黎明界内各画面或登录/验证码协助条件，所以未继续游戏内流程，也没有把停在标题页1小时当作游戏soak。
- 恢复：未更改分辨率、旋转、系统设置值；结束时 wm 仍为720×1280。Settings已force-stop，Graph及触控服务已关闭；MuMu实例0通过官方Manager shutdown恢复关闭。设备列表为空后停止了本次查询自动启动的ADB守护进程（最初该进程未运行），本次没有启动其他实例。

证据：`docs/desktop/evidence/l1/`保留普通设置截图及原始日志；`inspect/actions/soak/compatibility`命令成功，`settings`的补充节点确认命令因null-root失败并据实记录。真实Graph只读摘要另见 `readonly-report.txt`，没有完整identity或真实账号截图。


## 1920×1080 标题误判复核（2026-10-25）

这是针对新发现误判的一次直接复测，没有重复 L1，也未修改上游分类或游戏流程。使用同一 MuMu Android 12 实例0、同一最终 PP-OCRv5、同一真实 Graph 只读探针。开始前实例0关闭，原 `wm size` 为 `Physical size: 720x1280`、无 override，`wm density` 为 `Physical density: 240`。仅临时执行 `wm size 1080x1920`，实际游戏截图经核对为1920×1080；密度不变。

结果：命令 exit 0，20秒 dryRun 处理54帧，预览存在，分类集合仍为 `UNKNOWN / TITLE_WAITING_TAP / BATTLE_FAILED`；首尾均为“点击屏幕开始游戏”的标题动画，没有发出点击。Graph关闭327 ms。本次 Raw p50/p95=322/329 ms，PNG=780/855 ms。**战斗失败误报在1920×1080下同样重现，不能归因于仅使用了非支持分辨率，也不能据此宣称标题识别通过。** 没有扩大到登录或游戏内操作，没有更改匹配阈值或上游规则。

恢复过程完整记录在 `evidence/l1/display-restore-1080.txt`：第一次 finally 中设备突然 offline，原设置复核失败；没有把失败当成功。随后仅为恢复重新启动实例0，确认覆盖仍为1080×1920，执行 `wm size reset`。再次读取确认 Physical720×1280、density240、无override，与原值完全一致；实例0随后完全关闭。

第二次收尾发现本轮之外新启动了实例3（Android15）。没有启动、修改或关闭该实例；为避免影响并发使用，保留共享ADB server。这与第一轮在全部实例关闭后停止本轮server的收尾情况不同，不能写成此次也已停止全局server。

脱敏文字摘要：`evidence/l1/readonly-1080-report.txt`。原始游戏截图含ID，仅留本机 `.tools/l2-probe-1080/`，没有提交；完整设备identity同样没有提交。

## 标题误判精确抓帧与上游定位（2026-10-25）

上述首尾截图并不是误判发生时的帧：四张首尾 PNG 离线回放均未触发战斗失败 detector。为确认实际原因，仅追加一次20秒只读采集，直接把真实 `DeviceService.capture()` 的每张 `PixelImage` 交给 `LabyrinthBattleFailureDetector`；只有命中时才保存同一帧、运行真实 processor 并记录原分类与颜色覆盖率。实例0开始前关闭，尺寸保持原720×1280、density240、无override，游戏实际截图1280×720；只启动游戏，不点击标题、不登录，不调用触控。采集63帧，精确命中5帧，触控命令计数0。结束后尺寸/密度未变，实例0恢复关闭，未操作外部实例3，保留共享ADB server。

5张命中图逐张目视均为标题动画。保存后的PNG在真实桌面 processor 离线回放全部重现原始采集结果，包括原分类、失败布局及置信分数；全部被纯色 detector 判断为 `TWO_BUTTON` 并覆盖为 `BATTLE_FAILED`。两张的原分类为 `UNKNOWN`（标题提示正在闪烁），三张原本已经是 `TITLE_WAITING_TAP`。

| 实际标题帧 | 原分类 | Logo分数 | 提示分数 | 失败分数 | 最终分类 |
|---|---|---:|---:|---:|---|
| 1 | UNKNOWN | 0.536270 | 0 | 0.565272 | BATTLE_FAILED |
| 2 | TITLE_WAITING_TAP | 0.549469 | 0.479144 | 0.665158 | BATTLE_FAILED |
| 3 | UNKNOWN | 0.547468 | 0.369366 | 0.660714 | BATTLE_FAILED |
| 4 | TITLE_WAITING_TAP | 0.547986 | 0.469493 | 0.601312 | BATTLE_FAILED |
| 5 | TITLE_WAITING_TAP | 0.548064 | 0.467372 | 0.601716 | BATTLE_FAILED |

已排除本次怀疑的桌面图像差异：四张首尾图共5,990,400个像素，以及五张实际命中图共4,608,000个像素，ImageIO与Skia解码的完整ARGB逐像素一致，包含alpha；首尾图全部不透明。102张识别模板的两种解码结果也一致，桌面打包资源与 `android/app/src/main/assets` 原文件逐字节一致。直接从Android原目录用ImageIO读取模板，再运行共享纯Kotlin processor，在五张标题图及下表四个失败fixture上的原分类、失败结果和最终分类均与桌面装配完全一致。

`LabyrinthBattleFailureRecognition.kt`、`LabyrinthEntryClassifier.kt`、`LabyrinthEntryFrameProcessor.kt` 的Android原源码与桌面构建生成源码全文一致。定位到 `LabyrinthEntryFrameProcessor` 第551行无条件运行纯色失败检测，第559行开始无条件以其结果覆盖原分类。标题动画的蓝色背景与浅色文字恰好满足该检测的颜色覆盖门槛。**本次证据已确定为共享上游已有的误判/优先级缺口，非ImageIO改Skia造成的PNG像素、alpha、资源装配或仅低分辨率问题。** 这里验证了原源码及原资源的同图行为，没有声称另行运行了Android APK中的识别流程。

针对“已识别标题，或Logo达到分类器既有阈值时，不让纯色失败结果覆盖”的思路，仅做离线条件评估，没有实施：

| 上游既有真实失败fixture | 布局 | Logo分数 | 原失败分数 | 拟议条件会否拦截 |
|---|---|---:|---:|---|
| battle/battle_failure_20260910.jpg | THREE_BUTTON | 0.044614 | 0.708013 | 否 |
| battle/ex_battle_failure_20260917.png | THREE_BUTTON | 0.033227 | 0.704905 | 否 |
| labyrinth/battle-failure-two-button-20261004.jpg | TWO_BUTTON | 0 | 0.684127 | 否 |
| ex/ex_monster_detail_live_20260910.jpg | THREE_BUTTON | 0.044614 | 0.708013 | 否 |

最后一项与第一项字节相同，四个文件仅代表三张独立失败图，不能扩大为四个独立场景。仅保护原 `TITLE_WAITING_TAP` 可挡住本次3/5误判；再以Logo≥0.45否决失败检测，可挡住5/5，另外两帧保留UNKNOWN，不强行判标题。0.45是分类器默认的**页面分数**门槛；原标题条件是 `min(logo,prompt)` 再加分类间距，Logo单锚并非现有标题充分条件。因此“单锚否决失败”属于新的识别仲裁政策，这些样本不足以证明对其他画面普遍可靠。当前保留上游业务原样，修正需要扩大既定“只搬移/注入、不重写识别”的范围，不能作为桌面兼容修复悄然加入。

现有 `LabyrinthEntryRecognitionSession` 第5194行起在收到 `BATTLE_FAILED` 且 `combatContext == null` 时结束本次识别，并明确提示未自动点击结束或重新挑战，随后立即返回。该条件下不会盲点结束/重试；本次只读探针实际也没有任何触控。此说明限定于没有战斗上下文的现有分支，不推断保留旧上下文等未测路径的行为，也不将停止保护视为误判已修复。

无ID文字证据均位于 `docs/desktop/evidence/l1/`：`title-trigger-capture.txt` 保留原始命中帧号、时刻、五个ROI覆盖率与锚分数；`title-trigger-replay.txt` 为逐帧离线复现；`title-decoder-parity.txt` 为首尾图及模板解码对照；`title-original-assets-guard-assessment.txt` 为原源码/原资源一致性及拟议条件评估；`title-trigger-lifecycle.txt` 为恢复记录。含ID的实际触发图仅保留本机 `.tools/title-offline-probe/private-trigger-*.png`，不提交。临时探针源文件及手工任务也只在该目录；生产代码、分类阈值与自动测试入口均未修改。
