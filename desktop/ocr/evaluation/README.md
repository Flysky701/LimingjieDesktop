# OCR 真实样本评估

桌面 OCR 使用固定 PP-OCRv5 模型。当前真实 ROI 清单的业务字段通过 **15/15**，由于样本总数尚未达到 80 条目标，状态记为 `INCOMPLETE_COVERAGE`（总准确率门槛为 95%，单类门槛为 85%）。

## 执行与输出

使用 JDK 21 从仓库根目录执行：

```powershell
desktop\gradlew.bat -p desktop :ocr:evaluate
```

评估结果输出至 `desktop/ocr/build/reports/ocr-eval.json` 与 `ocr-eval-diff.tsv`。评估任务位于独立 source set，将 OCR 输出送入 `shared` 中的上游匹配器验证；`check -Pfull` 包含该任务。

## 当前样本覆盖

完整覆盖目标为 80 条（每类至少 8 条）：

| 类别 | 通过 / 真实 ROI 数 | 距每类 8 条缺口 |
| --- | ---: | ---: |
| 商店物品 | 3 / 3 | 5 |
| 遗物详情 | 2 / 2 | 6 |
| 遗物堆叠 | 7 / 7 | 1 |
| 角色奖励名 | 0 / 0 | 8 |
| 事件选项 | 2 / 2 | 6 |
| EX 遭遇 | 1 / 1 | 7 |
| 合计 | 15 / 15 | 总目标 80 尚缺 65 |

`manifest.json` 引用仓库内的上游真实截图与 resolver ROI 坐标；素材筛选说明见 [SAMPLE_AUDIT.md](SAMPLE_AUDIT.md)。

## 历次归档对比

以下快照记录了模型选型与前处理改进过程（前五组基于初始 14 条清单，最后一组及当前版本基于 15 条清单）：

| 归档报告 | 模型与处理方式 | 通过数 | 说明 |
| --- | --- | ---: | --- |
| [baseline-v4.json](baseline-v4.json) | 固定 v4 | 7/14 | 小数字等漏检 |
| [baseline-v5.json](baseline-v5.json) | 原始固定 v5 | 9/14 | 未做短文本右侧补零 |
| [final-v5.json](final-v5.json) | v5 + 320 宽补零 + 短 ROI 整图识别 | 12/14 | 系列长列及低清数字 5 未通过 |
| [final-v5det-v4rec.json](final-v5det-v4rec.json) | v5 det + v4 rec 对照 | 12/14 | 无改善，保留纯 v5 |
| [final-v5-border.json](final-v5-border.json) | 紧凑 ROI 追加四周黑边 `det+rec` | 13/14 | 修复低清数字 5 |
| [final-v5-expanded.json](final-v5-expanded.json) | 扩展至 15 条样本（未含系列角标核验） | 14/15 | 当时整列直接识别漏读系列计数 |
| 当前版本（`ocr-eval.json`） | 新增系列面板按行角标双重核验 | **15/15** | 现有样本全部通过，待补充样本覆盖 |

## 前处理诊断与随包自检

- **前处理诊断**：见 [preprocessing-diagnosis.md](preprocessing-diagnosis.md) 与 [preprocessing-diagnosis.tsv](preprocessing-diagnosis.tsv)。可通过 `desktop\gradlew.bat -p desktop :ocr:diagnosePreprocessing` 重新生成诊断图像至 `desktop/ocr/build/reports/preprocessing-diagnosis/`。
- **随包自检样图**：`src/main/resources/ocr/self-test.png` 裁自 `shop/imprints-20260914.jpg` 的 `(125,230,390,55)` 区域，期望文本为 `攻击型职能的随机印记`（见 [self-test-source.json](self-test-source.json)）。
