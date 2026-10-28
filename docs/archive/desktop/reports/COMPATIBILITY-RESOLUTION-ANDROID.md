# 分辨率与 Android 版本兼容调查

日期：2026-10-25。范围：图像与坐标兼容；本轮仅使用仓库源码、公开资料和已有截图做离线验证。

## 结论

底层连接和图像坐标映射没有“必须 Android 15”或“只能处理 1920×1080”的限制；但当前桌面自动视觉执行另有 1920×1080 前置检查，其他尺寸仅用于连接、预览和只读识别。Android 15 的作者原话及其指定原因尚未查到，不能把录屏生命周期文档当作最低版本声明。1920×1080 是主要视觉参考尺寸和上游声明的已测试环境，其他尺寸已有映射支持，但不代表完整流程已验证。执行边界见 [分辨率前置检查](RESOLUTION-GUARD.md)。

本轮修复一个可复现的问题：进入迷宫后的备用点击点独立缩放 X/Y，与标准模板和入口动作使用的居中 FIT 坐标系不一致。改为复用 `CoordinateMapper`，不改参考点、模板、阈值、页面规则、策略、任务顺序或触发条件。Android 版本入口、ADB 连接与 UI 均未修改。

## Android 15 说法的证据范围

查阅了本地 `README-upstream.md`、`发布/`、`android/docs/labyrinth-full-automation.md`，以及本地 `upstream/main` 可达的 README/发布资料历史；该引用为 `b809b196988b019571caf5679ccf8d7ebb7f072d`（2026-10-22）。也只读查看了作者公开仓库首页和 Releases 页面。未找到“必须使用安卓 15”的说明；不涵盖群聊、私发教程等未提供资料，因此不能断言作者从未说过。

- [作者公开 README](https://github.com/wbero/limingjie-assistant#运行环境) 明确工程最低为 Android 8.0/API 26；已测试环境写 MuMu、雷电 9 与 1920×1080。当地副本为 `README-upstream.md` 的“运行环境”。
- `android/app/build.gradle.kts` 为 `minSdk=26`、`targetSdk=35`、`compileSdk=35`。35 对应 Android 15，但 targetSdk 是目标行为版本，不能替代最低版本。[Android 官方 uses-sdk 说明](https://developer.android.com/guide/topics/manifest/uses-sdk-element) 区分了这两个属性。
- `android/docs/labyrinth-full-automation.md` 第 8 节“Android 15 屏幕授权与生命周期”要求整批次共用一次 MediaProjection/VirtualDisplay，避免重复使用授权令牌；这是原生安卓版录屏路线。官方把令牌重复使用引发异常的约束列在 Android 14 及以上，另将 Android 15 QPR1 的录屏提示和锁屏停止列为新行为。这些是兼容约束，不是必须升级的理由。[Android 官方 MediaProjection 文档](https://developer.android.com/media/grow/media-projection)
- 桌面 `ScreenCapturer.capture()` 使用 `adb exec-out screencap` 或 `screencap -p`，不经过 MediaProjection 授权。因此没有依据为桌面添加 Android 15 要求。
- 桌面 `AdbDeviceService.establish()` 只拒绝 SDK < 26。现有 Raw 解码支持 12/16 字节头及格式 1/2/5，PNG 有独立解码与测速回退。支持格式不能直接换算成已通过的 Android 版本矩阵。
- [既有 MuMu 实测](L1-MuMu.md) 记录 Android 12/API 32 的连接、截图、系统设置页点击与滑动，以及横屏 1280×720。它足以反驳桌面底层只能运行于 Android 15，但不证明其他版本或所有游戏页面通过。本轮没有重跑这些实机操作。

## 1080 模板的实际限制与本次修正

`LabyrinthEntryFrameProcessor` 同时保留 1920×1080 标准参考和 2780×1264 宽屏参考。标准锚点使用 `ReferenceFitMapper`，部分宽屏锚点使用 `ReferenceCoverMapper`。`GradientTemplateMatcher` 按模板和目标 ROI 尺寸映射采样点，因此模板并不是只能在原像素尺寸匹配。

标准 FIT 使用 `s=min(W/1920,H/1080)`，偏移为 `((W-1920s)/2,(H-1080s)/2)`。入口动作的 `CoordinateMapper` 使用同样的等比居中逻辑。截图仍为设备原像素，识别所得矩形与点击直接位于这张截图的坐标系；本轮没有裁剪整帧或添加二次点击缩放。

问题在 `LabyrinthFallbackTap.rect()`：原来采用 `x/1920*W` 和 `y/1080*H`。在 2560×1080 下，参考 x=1640 的点落到 x=2186，而 FIT 应为 x=1960，相差 226 像素；扩充既有测试后，“发起挑战”的备用点实际落出了声明的按钮矩形。较高画幅下同理会产生纵向偏移。

修改只将该表的坐标转换改为复用 `CoordinateMapper`，保留 2×2 点击矩形、整数截断和边界限制。1920×1080 下全部七个原始点逐一验证不变。历史上 `ITEM_REWARD`、事件动画等未绑定锚点的参考点和其使用政策保持原样，不顺带修正业务规则。

| 情况 | 本轮处理与限制 |
| --- | --- |
| 同为 16:9、分辨率不同 | 沿用原有等比映射与模板采样；离线覆盖 720p、1080p、1440p |
| 内容在屏幕中居中等比显示，左右或上下留边 | 备用点现在与标准 FIT 锚点一致；离线样本覆盖左右、上下黑边 |
| 异宽高比导致客户端重新排版 | 不能仅凭分辨率判断；既有 FIT/COVER 候选照常使用，本轮未宣称全页面兼容 |
| 不对称系统栏、内容偏移、窗口裁切 | 尚无统一有效内容边界检测，本轮没有猜测边界或强行拉伸；仍需原始截图确定可见区域及偏移 |
| DPI、字体、抗锯齿、渲染后端差异 | 模板梯度/亮度分数和 OCR 输入可能改变；离线缩放不是这类差异的实机证据，不降低匹配阈值 |
| 原图已经缺失边缘内容 | 缩放不能恢复；仍按现有越界/识别失败路径处理，不补造点击区域 |

## 修改文件

1. `android/app/src/main/java/com/landosol/toolbox/labyrinth/LabyrinthFallbackTaps.kt`：唯一生产逻辑修改，仅坐标转换。桌面构建通过现有共享源码机制消费；这是对上游的最小兼容改动记录，未另做桌面副本。
2. `android/app/src/test/java/com/landosol/toolbox/labyrinth/LabyrinthFallbackTapsTest.kt`：扩展宽高比覆盖，增加原参考点不变与入口动作坐标一致性检查。
3. `desktop/shared/src/test/kotlin/com/landosol/toolbox/desktop/shared/ResolutionCompatibilityTest.kt`：复用已有截图及出厂模板，验证缩放、居中留边、ROI 裁剪与备用点一致。
4. 本独立报告。其余对话在设备与 UI 范围的改动未纳入本轮交付；未暂存或提交共享分支。

## 定向验证

先只扩展测试、保留旧实现：5 项中 2 项失败，分别是 FIT 一致性与按钮矩形覆盖，复现上述 226 像素偏移。修正后使用本地 JDK 21、仓库已有 Gradle 缓存离线运行：

```powershell
$env:JAVA_HOME = "$PWD/.tools/jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME = "$PWD/.gradle-user-home"
./desktop/gradlew.bat -p desktop :shared:test --offline --console=plain `
  --tests '*LabyrinthFallbackTapsTest' `
  --tests '*ResolutionCompatibilityTest' `
  --tests '*ReferenceFitMapperTest' `
  --tests '*CoordinateMapperTest'
```

结果：11 项通过，0 失败，0 跳过。构建原有的测试后源文件保护检查自动执行并通过；没有运行全量测试或新增门禁。本地日志为 `.tools/compatibility-baseline-cached.log` 和 `.tools/compatibility-validation.log`。

几何检查覆盖 1920×1080、1280×720、2560×1440、2400×1080、2560×1080、1600×720、1920×1200、1440×1080、1280×800。截图检查使用已入库的 `run-result-next-button-20260920.jpg`（1920×1080）及原有 `run_result_close_button.png`；只在内存中双线性缩放并加居中黑边，不新增或修改图片文件。

| 离线画布 | 按钮模板分数 | 备用点击中心 |
| --- | ---: | --- |
| 1920×1080 | 0.6032 | (960,1000) |
| 1280×720 | 0.5637 | (640,666) |
| 2560×1440 | 0.6121 | (1280,1333) |
| 2560×1080，左右留边 | 0.6032 | (1280,1000) |
| 1440×1080，上下留边 | 0.5977 | (720,885) |
| 1280×800，上下留边 | 0.5637 | (640,706) |

以上均通过既有动作锚点分数线 0.45；整帧 ROI 与裁剪图评分一致，点击同时落在声明 ROI 和原截图目视按钮内部。**这验证的是按钮局部匹配与坐标，不是整页分类或自动通关。** 原有标题页误判不在本轮修复范围，未调整其规则。未构建 APK/桌面安装包，未控制游戏或改动模拟器配置。

## 仍需实机确认的组合

- 当前使用实例：保持现有版本、DPI、分辨率，在用户后续正常测试中观察涉及备用点的确认/结算页面；本轮没有打断正在运行的实例。
- MuMu Android 12 与 Android 15：分别在固定 1920×1080 下核对截图、触控与相同页面；前者有历史底层证据，后者本轮未实测。
- 同版本的 1280×720、2560×1440：需要原始截图确认实际字体/布局；已有缩放样本通过不能替代游戏真实渲染。
- 2560×1080、1280×800 等异比例：先确认是居中显示还是重新布局，以及是否有系统栏；不对称边界不能按本报告的居中黑边结果认定通过。
- 雷电/Android 9 及其他 Android 8+ 组合：本项目仅有部分合成解析样本及上游环境说明，未取得本轮真实桌面接线证据。

目前无需修改另一个对话负责的 ADB 连接文件。若后续截图显示有效内容区域不居中，应以该截图为依据，把识别裁剪与动作坐标共同适配；不能单独裁图或单独补点击偏移。
