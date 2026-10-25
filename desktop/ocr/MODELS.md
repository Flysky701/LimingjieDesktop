# OCR 模型与运行方式

引擎为 `com.landosol.toolbox.desktop.ocr.OnnxOcrEngine()`，运行时从 classpath `/ocr/` 按字节加载模型。生产模块只依赖 ONNX Runtime 与 coroutines，不依赖 shared。

固定资源来源如下（2026-10-25 下载并逐一核验）；`verifyModels` 在资源打包和 check 时校验 SHA-256。

| 文件 | 来源 | SHA-256 |
|---|---|---|
| ch_PP-OCRv5_det_mobile.onnx | [RapidAI/RapidOCR v3.10.0](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx) | 4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae |
| ch_PP-OCRv5_rec_mobile.onnx | [RapidAI/RapidOCR v3.10.0](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile.onnx) | 5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5 |
| ppocrv5_dict.txt | [RapidAI 对应 v5 字典](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/paddle/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile/ppocrv5_dict.txt) | d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b |

模型校验值来自 [RapidOCR 官方模型清单](https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/default_models.yaml)。字典校验值为上列固定版本下载内容的实算值；加载时前置 CTC blank、末尾追加空格，不改变原始资源文件，并校验字符总数与模型输出匹配。

模型由 PaddleOCR 提供，ONNX 转换产物由 RapidAI 维护；两项目均提供 Apache-2.0 许可。许可证全文保存在 `licenses/PaddleOCR-APACHE-2.0.txt`、`licenses/RapidOCR-APACHE-2.0.txt`，分别取自 PaddleOCR v2.7.0 和 RapidOCR v3.9.0。随包第三方声明同时保留两者归属。

前后处理按 SPEC-20 实现 BGR/NCHW 输入、DB 连通域轴对齐框、CTC 贪心解码。短文字按 PaddleOCR 官方 rec_image_shape=(3,48,320) 右侧补零，超长文字保留 1280 上限。输出仅按空间顺序拼行并 trim，不改写上游名称匹配、阈值或 OCR 调度。不附带方向分类器。

按 SPEC-20 仅比较一次 v5：v4 初版 7/14，v5 初版 9/14，v5 加完整官方补边和有界短 ROI 整图识别后 12/14；继续诊断并添加有负样本对照的末级黑边检测后为 13/14。当前采用 v5；**仍未通过总体 95%、每类 85% 的准确率门槛**，扩大到15条后仍缺少65条真实样本及角色奖励类覆盖。报告见 `evaluation/baseline-v4.json`、`baseline-v5.json`、`final-v5.json`、`final-v5-border.json`。两组旧 v4 模型不随最终发行包携带。

短 ROI 回退仅用于：检测/识别结果为空，宽高均在 32..256、长宽比 0.5..2、像素并非纯色。先直接识别整张 ROI 一次；仍无有效文字时，四周补短边 1/4 的黑边，最多再做一次 det+rec，不递归、不再整图尝试。原像素、0.3/0.6 检测阈值和 0.5 识别阈值不变，已有有效结果立即返回。低分辨率数字 5 已修复；系列列表部分计数仍失败并保留在评估中。参见 D-46/FB-14 和 `evaluation/preprocessing-diagnosis.md`；不按具体字词补全结果。

历史对比使用 RapidAI/RapidOCR 固定 v3.9.2 的 `onnx/PP-OCRv4/det/ch_PP-OCRv4_det_mobile.onnx`（SHA-256 `d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9`）和 `onnx/PP-OCRv4/rec/ch_PP-OCRv4_rec_mobile.onnx`（`48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b`），下载 URL 前缀为 `https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/`。v4字典取自 PaddleOCR v2.7.0 的 `ppocr/utils/ppocr_keys_v1.txt`，SHA-256 `28b2362ad4ab2dc38769aa72feb535e3a9ddb3fd2a7585a05920e6393b1dc7f7`。这些历史文件已从最终运行资源删除。

`warmUp()` 在单个工作线程加载与预热两模型；队列容量 64；失败、关闭或队列满都回调 null。引擎不指定 UI 回调线程，shared 适配器负责切回 Swing Main。提交的像素归请求所有，调用者在提交后不得修改；shared 在提交前同步复制 Bitmap。


最终扩大清单（2026-10-25）：保留原14条真值，新增一张不同真实地图的计数ROI。`final-v5-expanded.json/tsv` 为 **14/15（93.33%）**，p50 **23.27ms**、p95 **151.04ms**；商店3/3、详情1/2、堆叠7/7、事件2/2、EX1/1，角色奖励0。仍为 `FAILED_ACCURACY`，样本目标尚缺65，未放松门槛。新增来源及未纳入素材的原因见 `desktop/ocr/evaluation/SAMPLE_AUDIT.md`；旧14例结果是历史基线，不是最终清单结果。
