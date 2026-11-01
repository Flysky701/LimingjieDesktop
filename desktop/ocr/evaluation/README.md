# OCR 真实样本评估

桌面 OCR 使用固定 PP-OCRv5。当前真实 ROI 清单的业务字段通过 **15/15**，状态为 `INCOMPLETE_COVERAGE`。总准确率目标为 95%，有样本类别的目标为 85%。此前单纯转写整列的评估通过了 14/15。[历史 JSON](final-v5-expanded.json) · [历史逐项输出](final-v5-expanded.tsv)

## 执行与输出

从仓库根目录执行，使用 JDK 21：

```powershell
desktop\gradlew.bat -p desktop :ocr:evaluate
```

Linux/macOS 对应命令为 `bash desktop/gradlew -p desktop :ocr:evaluate`。新结果写入 `desktop/ocr/build/reports/ocr-eval.json` 与 `ocr-eval-diff.tsv`，本目录保留已归档快照。

评估使用独立 source set 并依赖 `shared`。文本送入上游匹配器，internal 帮助函数通过反射调用；生产 OCR 模块保持独立。

`check` 依赖 `evaluate`，按上述准确率门槛判定。完整覆盖目标为 80 条、每类至少 8 条，报告通过 `coverageComplete` 表示进度。字符错误率含空白和标点；p50/p95 从复制 ROI 像素计时到回调返回。

## 当前覆盖

| 类别 | 正确 / 真实 ROI 数 | 距每类 8 条的缺口 |
| --- | ---: | ---: |
| 商店物品 | 3 / 3 | 5 |
| 遗物详情 | 2 / 2 | 6 |
| 遗物堆叠 | 7 / 7 | 1 |
| 角色奖励名 | 0 / 0（无样本） | 8 |
| 事件选项 | 2 / 2 | 6 |
| EX 遭遇 | 1 / 1 | 7 |
| 合计 | 15 / 15 | 总目标 80 尚缺 65 |

系列面板的标题通过后，桌面端按行读取图标右下角的数量。原始数字与去除图标背景后的字形必须读出同一个值。返回的文字只包含确认的系列及数量，报告仍将它与清单的完整参考文字计算字符错误率。系列计数只有一个真实面板来源。

`manifest.json` 引用仓库内真实上游截图，ROI 来自 resolver 常量或布局映射，期望值依据截图、测试和日志登记。新增样本按不同真实游戏帧记录，并附 ROI 与实体真值；素材筛选规则见 [SAMPLE_AUDIT.md](SAMPLE_AUDIT.md)。

## 历次结果

以下结果均归档于 2026-10-25，前五组使用原 14 条清单，最后一组扩展为 15 条。

| 报告 | 模型与处理 | 正确数 | 结论 |
| --- | --- | ---: | --- |
| [baseline-v4.json](baseline-v4.json) | 固定 v4 | 7/14 | 小数字等漏读 |
| [baseline-v5.json](baseline-v5.json) | 原始固定 v5 | 9/14 | 低于门槛 |
| [final-v5.json](final-v5.json) | v5、320 宽零填充与短 ROI 整图识别 | 12/14 | 系列列表及一个小数字失败 |
| [final-v5det-v4rec.json](final-v5det-v4rec.json) | v5 det + v4 rec 对照 | 12/14 | 未改善，保留纯 v5 |
| [final-v5-border.json](final-v5-border.json) | 紧凑 ROI 无结果时追加一次黑边 det+rec | 13/14 | 小数字修复，系列列表仍失败 |
| [final-v5-expanded.json](final-v5-expanded.json) | 同一 v5 管线，新增不同真实地图计数 ROI | 14/15 | 当时的整列读法未达到准确率与覆盖目标 |

## 前处理诊断与随包自检

诊断说明见 [preprocessing-diagnosis.md](preprocessing-diagnosis.md)，原始数据见 [preprocessing-diagnosis.tsv](preprocessing-diagnosis.tsv)。执行 `desktop\gradlew.bat -p desktop :ocr:diagnosePreprocessing` 可复现变换、检测框和原始解码得分，图像输出至 `desktop/ocr/build/reports/preprocessing-diagnosis/`。这是独立诊断任务，评估样本仍以 manifest 为准。

随包 [self-test.png](../src/main/resources/ocr/self-test.png) 用于检查模型、原生库和资源加载。它裁自 `shop/imprints-20260914.jpg` 的 `(125,230,390,55)` 区域，期望文本为 `攻击型职能的随机印记`，来源见 [self-test-source.json](self-test-source.json)。
