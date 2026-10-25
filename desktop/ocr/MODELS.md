# OCR 模型与运行方式

引擎为 `com.landosol.toolbox.desktop.ocr.OnnxOcrEngine()`，运行时从 classpath `/ocr/` 按字节加载模型。生产模块只依赖 ONNX Runtime 与 coroutines，不依赖 shared。

固定资源来源如下（2026-10-25 下载并逐一核验）；`verifyModels` 在资源打包和 check 时校验 SHA-256。

| 文件 | 来源 | SHA-256 |
|---|---|---|
| ch_PP-OCRv4_det_mobile.onnx | [RapidAI/RapidOCR v3.9.2](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv4/det/ch_PP-OCRv4_det_mobile.onnx) | d2a7720d45a54257208b1e13e36a8479894cb74155a5efe29462512d42f49da9 |
| ch_PP-OCRv4_rec_mobile.onnx | [RapidAI/RapidOCR v3.9.2](https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.9.2/onnx/PP-OCRv4/rec/ch_PP-OCRv4_rec_mobile.onnx) | 48fc40f24f6d2a207a2b1091d3437eb3cc3eb6b676dc3ef9c37384005483683b |
| ppocr_keys_v1.txt | [PaddleOCR v2.7.0](https://raw.githubusercontent.com/PaddlePaddle/PaddleOCR/v2.7.0/ppocr/utils/ppocr_keys_v1.txt) | 28b2362ad4ab2dc38769aa72feb535e3a9ddb3fd2a7585a05920e6393b1dc7f7 |

模型校验值来自 [RapidOCR 官方模型清单](https://github.com/RapidAI/RapidOCR/blob/main/python/rapidocr/default_models.yaml)。字典校验值为上列固定版本下载内容的实算值；文件 6,623 个字符，加载时前置 CTC blank、末尾追加空格，不改变原始资源文件。

模型由 PaddleOCR 提供，ONNX 转换产物由 RapidAI 维护；两项目均提供 Apache-2.0 许可。许可证全文保存在 `licenses/PaddleOCR-APACHE-2.0.txt`、`licenses/RapidOCR-APACHE-2.0.txt`，分别取自 PaddleOCR v2.7.0 和 RapidOCR v3.9.0。随包第三方声明同时保留两者归属。

前后处理按 SPEC-20 实现 BGR/NCHW 输入、DB 连通域轴对齐框、CTC 贪心解码。输出仅按空间顺序拼行并 trim，不改写上游名称匹配、阈值或 OCR 调度。不附带方向分类器。资源可加载与推理可执行不等于上游实体识别准确率已达标；真实截图评估单独报告。

`warmUp()` 在单个工作线程加载与预热两模型；队列容量 64；失败、关闭或队列满都回调 null。引擎不指定 UI 回调线程，shared 适配器负责切回 Swing Main。提交的像素归请求所有，调用者在提交后不得修改；shared 在提交前同步复制 Bitmap。
