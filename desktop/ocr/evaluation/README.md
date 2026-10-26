# OCR 真实样本评估

2026-10-26 按当前 `manifest.json` 与入库报告核对。当前桌面使用固定 PP-OCRv5；最近完整清单结果是 **14/15（93.33%）**，详见 [final-v5-expanded.json](final-v5-expanded.json) 与[逐项输出](final-v5-expanded.tsv)。总准确率未达到 95%，遗物详情为 1/2、低于类别 85% 门槛，覆盖也未完成；结论仍为 `FAILED_ACCURACY`，不能用模型加载、自检或单元测试成功替代。

## 执行与输出

从仓库根目录执行，使用 JDK 21：

```powershell
desktop\gradlew.bat -p desktop :ocr:evaluate
```

Linux/macOS 对应命令为 `bash desktop/gradlew -p desktop :ocr:evaluate`。新结果写入 `desktop/ocr/build/reports/ocr-eval.json` 与 `ocr-eval-diff.tsv`；本目录的 JSON/TSV 是历次入库快照，不会随执行自动覆盖。

评估为独立 source set，仅该 source set 依赖 `shared`，生产 OCR 模块不反向依赖共享业务。识别文本直接送入上游现有匹配器，未修改匹配器或阈值；对 internal 帮助函数反射调用现有实现。

`check` 依赖 `evaluate`：总准确率低于 95% 或任何有样本类别低于 85% 时失败。样本不足单独报告 `coverageComplete=false`；即使准确率达到门槛，覆盖不足也不能宣称 SPEC-20 完整评估完成。字符错误率含空白/标点，仅用于诊断；p50/p95 包含复制 ROI 像素至回调返回。

## 当前覆盖与失败项

| 类别 | 正确 / 真实 ROI 数 | 距每类 8 条的缺口 |
| --- | ---: | ---: |
| 商店物品 | 3 / 3 | 5 |
| 遗物详情 | 1 / 2 | 6 |
| 遗物堆叠 | 7 / 7 | 1 |
| 角色奖励名 | 0 / 0（无样本） | 8 |
| 事件选项 | 2 / 2 | 6 |
| EX 遭遇 | 1 / 1 | 7 |
| 合计 | 14 / 15 | 总目标 80 尚缺 65 |

最近报告 p50 **23.27 ms**、p95 **151.04 ms**。残留失败是 `series-visible` 系列列表计数不完整；低分辨率 `map-link-0` 数字漏读已由有界前处理回退修复。完整来源与排除原因见 [SAMPLE_AUDIT.md](SAMPLE_AUDIT.md)。

`manifest.json` 仅引用仓库内真实上游截图，ROI 来自 resolver 常量或布局映射。真值在运行 OCR 前依据截图、上游测试和已有日志确认，不以模型输出作真值。上游测试截图的数量不等于可用 OCR 场景数；助手日志、缩放诊断网页、战斗失败页和角色加入页不能冒充缺失的角色奖励三选一页。下一阶段需采集不同的真实游戏帧及 ROI/实体真值；复制、缩放变体或生成文字不填充覆盖。

## 历次结果

以下均为 2026-10-25 的保留记录，前五组只有原 14 条样本，不能混作当前 15 条清单结果。

| 报告 | 模型与处理 | 正确数 | 结论 |
| --- | --- | ---: | --- |
| [baseline-v4.json](baseline-v4.json) | 固定 v4 | 7/14 | 小数字等漏读 |
| [baseline-v5.json](baseline-v5.json) | 原始固定 v5 | 9/14 | 低于门槛 |
| [final-v5.json](final-v5.json) | v5、320 宽零填充与短 ROI 整图识别 | 12/14 | 系列列表及一个小数字失败 |
| [final-v5det-v4rec.json](final-v5det-v4rec.json) | v5 det + v4 rec 对照 | 12/14 | 未改善，保留纯 v5 |
| [final-v5-border.json](final-v5-border.json) | 紧凑 ROI 无结果时追加一次黑边 det+rec | 13/14 | 小数字修复，系列列表仍失败 |
| [final-v5-expanded.json](final-v5-expanded.json) | 同一 v5 管线，新增不同真实地图计数 ROI | 14/15 | 当前清单；质量与覆盖均未达标 |

没有改动真值、matcher、匹配阈值或按特定文字补齐结果。

## 前处理诊断与随包自检

诊断说明见 [preprocessing-diagnosis.md](preprocessing-diagnosis.md)，原始数据见 [preprocessing-diagnosis.tsv](preprocessing-diagnosis.tsv)。显式执行 `desktop\gradlew.bat -p desktop :ocr:diagnosePreprocessing` 可复现变换、检测框及原始解码得分，图像输出位于 `desktop/ocr/build/reports/preprocessing-diagnosis/`。此任务不接入 `check` 或常规 `test`，诊断变体不增加样本数。

随包自检图片位于 [self-test.png](../src/main/resources/ocr/self-test.png)，是上游 `shop/imprints-20260914.jpg` 的 `(125,230,390,55)` 原像素裁剪。期望文本 `攻击型职能的随机印记` 来自对应 JSON 的第一条 `titleText`，并已在原评估阶段目视核对；来源见 [self-test-source.json](self-test-source.json)。自检只确认随包模型、原生库和资源可用，不证明整体识别质量或真实游戏流程可靠。
