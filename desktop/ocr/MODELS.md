# OCR 模型与运行方式

桌面 OCR 引擎为 `com.landosol.toolbox.desktop.ocr.OnnxOcrEngine`，运行时从 classpath `/ocr/` 加载模型。`ocr` 模块仅依赖 ONNX Runtime 与 Coroutines，不依赖 `shared`。

## 模型文件与校验

`verifyModels` 在资源处理和 `check` 时校验以下 SHA-256：

| 文件 | 来源 | SHA-256 |
| --- | --- | --- |
| `ch_PP-OCRv5_det_mobile.onnx` | [RapidAI/RapidOCR v3.10.0](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx) | `4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae` |
| `ch_PP-OCRv5_rec_mobile_ctc.onnx` | 由官方 `ch_PP-OCRv5_rec_mobile.onnx` 派生（保留原权重） | `3f650d0b5b20e6e059c15b590fbf0bcc8459adc088592bc0435f74eae4015cf5` |
| `ppocrv5_dict.txt` | [RapidAI v5 字典](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/paddle/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile/ppocrv5_dict.txt) | `d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b` |

- **识别模型紧凑化**：源文件为 [RapidAI/RapidOCR `ch_PP-OCRv5_rec_mobile.onnx`](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/rec/ch_PP-OCRv5_rec_mobile.onnx)（SHA-256 `5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5`）。`tools/ocr/compact_ctc_model.py` 在校核源文件后，于输出层追加 `ArgMax` 和 `ReduceMax`，仅输出每个时间步的字符索引与置信度，不修改模型权重或解码逻辑。需重新生成时安装 `onnx==1.19.1` 并执行：
  ```powershell
  python tools/ocr/compact_ctc_model.py SOURCE.onnx desktop/ocr/src/main/resources/ocr/ch_PP-OCRv5_rec_mobile_ctc.onnx
  ```
- **字典加载**：加载时前置 CTC blank、末尾追加空格，并校验字符总数与模型元数据一致。
- **开源许可**：模型由 PaddleOCR 提供，ONNX 导出由 RapidAI 维护，均采用 Apache-2.0 许可（见 `licenses/PaddleOCR-APACHE-2.0.txt` 与 `licenses/RapidOCR-APACHE-2.0.txt`）。

## 前后处理与回退规则

- **常规流程**：BGR/NCHW 输入、DB 连通域轴对齐检测框（阈值 `0.3/0.6`）、CTC 贪心解码（置信度阈值 `0.5`）。短文本按 `rec_image_shape=(3,48,320)` 等比缩放并右侧补零，长文本宽度上限 `1280`。输出按空间顺序拼行并 `trim`。
- **紧凑 ROI 回退**：当常规检测+识别结果为空，且输入宽高均在 `32..256`、长宽比 `0.5..2`、像素非纯色时：
  1. 直接对整张 ROI 执行一次识别；
  2. 若仍无有效文本，在四周补短边 `1/4` 宽度的黑边后额外执行一次 `det+rec`。
- **系列面板计数**：系列详情标题确认后，按行读取图标右下角数字，要求原始数字与去除图标背景后的字形读出相同结果。

## 线程、内存与评估

- `warmUp()` 在单工作线程加载并预热检测与识别模型；请求队列容量为 64，待处理像素上限为 64 MiB，超限或关闭时回调 `null`。
- `shared` 适配器在提交前复制 Bitmap，并在回调时切回 Swing Main 线程。
- 原生推理在每次调用后收缩 CPU arena 未用区域，工作线程不自旋；每个设备 worker JVM 独立持有模型实例。
- 真实样本评估结果与前处理诊断见 [evaluation/README.md](evaluation/README.md)。
