# WS-C OCR 实施报告

- 分支：`desktop/ws-c`；代码、资源和本报告随最终 OCR 提交交付。
- 结论：真实 ONNX 引擎、前后处理、生命周期和模型资源已完成；17 项定向测试通过。**G2-C 失败**：修复并扩大真实清单后评估为 14/15（93.33%），低于总体 95% / 各类 85%；样本覆盖也不足。

## 交付

| 项 | 状态 | 文件 |
|---|---|---|
| 单例使用的异步 OCR 引擎 | 已实现 | `desktop/ocr/src/main/kotlin/com/landosol/toolbox/desktop/ocr/OnnxOcrEngine.kt` |
| BGR/NCHW、尺寸、DB框、CTC、拼行 | 已实现 | 同目录 `OcrPipeline.kt` |
| 固定 v5 det/rec/字典，SHA 守卫与许可 | 已完成 | `desktop/ocr/MODELS.md`、`licenses/`、`build.gradle.kts` |
| 真实截图自检样例 | 实际逐字识别通过 | `src/main/resources/ocr/self-test.png` / `.txt` |
| 独立评估与上游原匹配器 | 已接通；门槛失败 | `desktop/ocr/evaluation.gradle.kts`、`src/evaluation/`、`evaluation/manifest.json` |

公开入口：`OnnxOcrEngine()`；按冻结契约提供 `warmUp()`、`submit(width,height,pixels,onResult)`、`status`、`close()`。按 classpath 字节加载模型，CPU 推理，工作队列 64；引擎初始化失败不重试；失败/满队列/关闭回调 null，每个已接收请求恰好完成一次。shared 适配器负责复制 Bitmap 与 Swing Main 回调，生产 OCR 不引用 shared。

## 验证

- 最终模型上的 `desktop/gradlew -p desktop :ocr:test --console=plain`：退出码 0，17 项通过（OcrPipelineTest 8 + OnnxOcrEngineTest 9）。覆盖实际原生模型加载/预热、空白与紧凑纯色图、真实1920地图计数、低清计数及五个真实无文字ROI、加载中排队、容量64、失败不重试、原生库失败提示、回调异常隔离、关闭后清理与回调一次性。
- `:ocr:evaluate` 使用真实截图和现行 resolver ROI，把实际文本送入原 matcher；不修改真值、上游阈值或按具体文字补齐。最终准确率未达标，Java 任务退出码 1。`check` 已依赖该任务。
- v4 首轮 7/14；按 SPEC 进行一次 v5 比较为 9/14；v5 补齐官方 320 宽补边与 FB-14 后 12/14；最后已下载模型间 v5-det/v4-rec 组合仍 12/14且系列计数更少，无改善，最终保留纯 v5，不再扩展模型尝试。
- 固定 v5 继续前处理诊断，紧凑ROI的原检测及整图识别都失败后追加一次黑边 det+rec，使低清数字5恢复，13/14；没有新增模型、改真值或删除失败样本。五个真实无文字ROI对照未发现新增误识别。
- 原14条清单 v5 性能：p50 23.32 ms / p95 157.57 ms，触发末级回退的低清数字5耗时31.87 ms；机器/负载依赖，不能当所有用户机器的保证。自检文字为真实商店截图的“攻击型职能的随机印记”，实际逐字通过。
- 原始数据：`desktop/ocr/evaluation/baseline-v4.json`、`baseline-v5.json`、`final-v5.json`、`final-v5det-v4rec.json`、`final-v5-border.json`。诊断方法、风险与原始得分见同目录 `preprocessing-diagnosis.md` / `.tsv`；显式 `:ocr:diagnosePreprocessing` 成功，不计入17项回归或14条评估。

## 残留与覆盖缺口

当前失败一项：`series-visible` 长列无法完整提取三个系列计数。原图真实数字位置的det最大概率远低于0.3，没有候选框被错误过滤；独立真实像素裁剪可读2/1/1，说明主要缺口在模型检测定位。灰度、对比度、插值、色通道、反色、二值、分块、四周/左右补边均未通用解决，部分变换还误识别截断正文或图标；未将这些不充分方案启用。未降置信度或上游匹配阈值。最终15条分布为商店3、详情2、堆叠7、事件2、EX1、角色奖励0；80条目标缺65。上游55张图包含结果页、日志/诊断截图等，不能凭文件名伪标用途或重复缩放充数。

L2-04 需采集更多真实不同实体与现行 ROI，优先补角色奖励、系列详情和低分辨率计数；届时重新跑 `evaluate`，保持相同门槛。模型可加载、自检通过不能代替准确率或实机完整流程验证。

## 登记与范围

- D-46：v5选择、官方识别张量补边事实修正、未通过结论。
- FB-13：队列满立即 null，日志一分钟最多一次。
- FB-14：检测/识别为空且尺寸32..256、长宽比0.5..2、非纯色的紧凑ROI，最多整图识别一次；仍失败才追加一次四周短边1/4黑边det+rec，不递归。保留0.5置信阈值；FINE日志记录尺寸与边距。
- EDGE E-01…E-11 已映射；E-03/E-08 引用主分支已通过的 `DesktopAdaptersTest#ocrBridgeCopiesPixelsAndReturnsOnSwingMain`，不重复实现 shared 测试。
- 新增生产代码约400行；无新增第三方库。仅 evaluation sourceSet 经批准依赖 shared。
- try/catch 仅限模型/原生I/O、线程中断、消费方回调及资源关闭边界；没有付费请求、真实账号、模拟器或游戏操作。
- 除 OCR 模块与本报告外，仅按主代理明确分工更新 `DECISIONS` 的 D-46、`FALLBACKS` 的 WS-C、`EDGE_CASES` 的 E组；未改上游业务、其他模块或根依赖配置。


最终扩大清单（2026-10-25）：保留原14条真值，新增一张不同真实地图的计数ROI。`final-v5-expanded.json/tsv` 为 **14/15（93.33%）**，p50 **23.27ms**、p95 **151.04ms**；商店3/3、详情1/2、堆叠7/7、事件2/2、EX1/1，角色奖励0。仍为 `FAILED_ACCURACY`，样本目标尚缺65，未放松门槛。新增来源及未纳入素材的原因见 `desktop/ocr/evaluation/SAMPLE_AUDIT.md`；旧14例结果是历史基线，不是最终清单结果。
