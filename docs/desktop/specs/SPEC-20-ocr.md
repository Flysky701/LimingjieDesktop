# SPEC-20 OCR `:ocr`（WS-C）

**职责**：在 PC 上替代 ML Kit 中文识别，对上游保持完全相同的调用语义。
**原则**：上游的匹配器、阈值、缓存和调度（`RelicOcrScheduler`、各 resolver 的 `remainingNewOcrRuns` 等）一律不动。差异只能在本模块的后处理里、凭评估集证据消化。

## 1. 上游契约（必须逐条满足）

上游调用形态是 `submitTextRead: (Bitmap, (String?) -> Unit) -> Unit`，默认值是 `AndroidChineseRoleNameOcr::readAsync`，被 7 个 resolver 引用。语义如下：

| 要点 | 上游行为 | 桌面要求 |
|---|---|---|
| 返回 | 异步；成功回调 `result.text.trim()`，失败回调 `null` | 相同。**不抛异常**：引擎不可用也回调 `null` |
| 位图所有权 | 调用方在回调里（或提交抛异常时）`recycle` 裁剪图 | 提交时**同步复制像素**，之后不再访问该 Bitmap |
| 回调线程 | ML Kit 默认在主线程回调 | 回调派发到 `Dispatchers.Main`（Swing EDT），最终以 THREADING.md 为准 |
| 实例 | 进程内单例，模型初始化复用 | 单例引擎，懒加载 |

`:shared` 中的同名替身 `object AndroidChineseRoleNameOcr { fun readAsync(bitmap: Bitmap, callback: (String?) -> Unit) }` 只做三件事：复制像素 → 交给 `DesktopBindings.ocr` → 在 Main 上回调。`:ocr` 不依赖 `:shared`，接口用纯 JVM 类型：

```kotlin
interface OcrEngine {
    /** pixels 为 ARGB 非预乘，长度 = width * height。回调线程由调用方决定。 */
    fun submit(width: Int, height: Int, pixels: IntArray, onResult: (String?) -> Unit)
    val status: StateFlow<OcrEngineStatus>   // NotLoaded / Loading / Ready / Failed(reason)
}
```

## 2. 运行时

- ONNX Runtime Java（`com.microsoft.onnxruntime:onnxruntime`，CPU 版）。`intraOpNumThreads = min(4, max(1, cores / 2))`，`interOpNumThreads = 1`。
- 模型从类路径**按字节加载**（`createSession(ByteArray)`），不经过文件路径，避免中文或空格路径问题（R-11）。
- 懒加载 + 预热：应用启动后在后台线程加载，并对一张空白图推理一次。加载期间提交的请求排队等待。
- 初始化失败：`status = Failed(原因)`；之后每个请求都直接回调 `null`；环境检查把 OCR 标为红色阻断项（黎明界任务不能开始；账号库、设置、诊断等其他页面不受影响）；原因写入日志。不重试。
- Windows 上 ONNX Runtime 的原生库依赖 VC++ 运行库。加载前，如果 `<java.home>/bin/` 中有 `msvcp140.dll`、`vcruntime140_1.dll`，先用 `System.load` 显式加载（登记到 DECISIONS）。加载仍失败时，错误提示中附上 VC++ 运行库的官方下载说明。
- 单线程工作队列，容量 64。队列满时新请求立即回调 `null`，并记一条警告日志（FB-13）。正常情况下上游自身的调度会限制并发数，不应触发。

## 3. 模型

- 默认：PP-OCRv4 mobile 中文检测（det）+ 识别（rec）的 ONNX 版本，字典 `ppocr_keys_v1.txt`，末尾追加空格字符（与 PaddleOCR 的 `use_space_char=True` 一致）。不用方向分类器（游戏文字都是水平的）。
- 模型文件放在 `desktop/ocr/src/main/resources/ocr/`（约 15 MB，直接入库），`MODELS.md` 记录来源 URL、许可（Apache-2.0）、版本、SHA-256。构建时校验 SHA-256。
- 评估集不达标时，允许换成 PP-OCRv5 mobile 再评估**一次**，取更好的一组，登记到 DECISIONS。不再做其他模型尝试。

## 4. 前后处理（纯 Kotlin，按 PaddleOCR 默认参数）

**检测**：
- 长边限制 960，宽高都取 32 的倍数；输入通道顺序 **BGR**；`(x / 255 - mean) / std`，`mean = [0.485, 0.456, 0.406]`，`std = [0.229, 0.224, 0.225]`（按 PaddleOCR 的写法，直接作用在 BGR 通道上）。
- DB 后处理：二值化阈值 0.3；连通域取轴对齐外接框；框内平均概率 < 0.6 的丢弃；按 `unclip_ratio = 1.5` 扩边（偏移量 = 面积 × 1.5 / 周长）；映射回原图坐标并裁剪到图像边界；宽或高 < 3 px 的丢弃。
- 裁剪图很小时（短边 < 32 px），先等比放大到短边 32 px 再检测，框坐标再换算回去。

**识别**：
- 每个框裁出后缩放到高 48，宽按比例取整（上限 1280，超出时等比缩小到宽 1280），输入 BGR，`(x / 255 - 0.5) / 0.5`。
- CTC 贪心解码：逐时间步取最大值，去掉 blank（下标 0）和连续重复。框的置信度为保留字符概率的平均值，< 0.5 的框丢弃（PaddleOCR 的 `drop_score`）。

**拼接**（模拟 ML Kit 的 `result.text`）：
- 框按中心 y 分行：与当前行的垂直重叠 ≥ 框高的 50% 视为同一行。行按 y 排序，行内按 x 排序。
- 同一行的框之间用一个空格连接，行与行之间用 `\n` 连接，最后 `trim()`。
- 实施前先阅读上游各匹配器的文本规范化方式（例如 `normalizePromptText`、名称模糊匹配器）。如果上游会去掉空白，以上拼接规则直接沿用；如果不会，用评估集比较「空格连接」和「直接连接」，选准确率更高的并登记。
- **除此之外不做任何文本归一化**（繁简转换、全半角、相似字替换等）。确有必要时，必须附评估集证据，并登记到 DECISIONS。

## 5. 评估集与指标

- 目录：`desktop/ocr/eval/`，清单 `manifest.json`，每条包含：`image`（相对仓库根的路径）、`roi`（像素矩形；**取自上游 resolver 的矩形常量或测试中的矩形**，注明出处 `文件:行`）、`kind`（商店物品 / 遗物详情 / 遗物堆叠 / 角色奖励名 / 事件选项 / EX 遭遇等）、`expected`（期望解析出的实体 ID 或名称）、`source`（期望值的出处）。
- 来源：上游 `android/app/src/test/resources/**` 的截图；上游测试中记录的真实 ML Kit 输出与期望实体的配对；实机 L2 通过诊断面板的逐帧存档功能采集的截图（用户协助时）。目标 ≥ 80 条，每个 `kind` ≥ 8 条。不足时在 PROGRESS 中记录缺口，并写进给用户的 L2 清单。
- **指标**：把 OCR 输出送进**上游原有的匹配器**，统计实体解析准确率。准确率 ≥ 95% 为通过；每个 `kind` 都不得低于 85%。同时记录原始字符错误率，仅供分析。
- 性能：单个 ROI 的端到端耗时（复制像素到回调）p50 / p95，记录在 PROGRESS。参考目标 p95 ≤ 150 ms（4 核 PC）。CI 上 p95 > 300 ms 时需要排查原因，但不作为失败条件。
- Gradle 任务 `:ocr:evaluate` 输出 `build/reports/ocr-eval.json` 和逐条差异表；CI 运行它并以准确率阈值作为门槛。

## 6. 测试

- 预处理：尺寸取整、通道顺序、归一化数值（与手算的小图对照）。
- DB 后处理：合成概率图 → 预期的框。
- CTC 解码：重复、blank、置信度过滤。
- 拼接：多行、同行多框、乱序输入。
- 契约：提交后立即 `recycle` 原图，结果不受影响；初始化失败时回调 `null` 且不抛异常；回调在 Main 上执行；队列满的行为。
- 中文 + 空格路径下（`D:\测试 目录\`）加载成功，由 SPEC-60 的 self-test 覆盖。
