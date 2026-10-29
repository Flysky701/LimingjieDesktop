"""Derive the desktop CTC output contract without changing weights or probabilities.

Install build-only dependency: python -m pip install onnx==1.19.1
Usage: python tools/ocr/compact_ctc_model.py SOURCE.onnx DESTINATION.onnx
SOURCE is the pinned RapidOCR PP-OCRv5 mobile recognition export in MODELS.md.
Python/onnx are not used by the application or required for ordinary builds.
"""

import hashlib
from pathlib import Path
import sys

import onnx
from onnx import TensorProto, helper


SOURCE_SHA256 = "5825fc7ebf84ae7a412be049820b4d86d77620f204a041697b0494669b1742c5"


def compact(source: Path, destination: Path) -> None:
    data = source.read_bytes()
    if hashlib.sha256(data).hexdigest() != SOURCE_SHA256:
        raise ValueError("Recognition source does not match the pinned model")
    model = onnx.load_model_from_string(data)
    if len(model.graph.output) != 1 or next(o.version for o in model.opset_import if not o.domain) != 14:
        raise ValueError("Unexpected recognition model output/opset")
    output = model.graph.output[0]
    classes = output.type.tensor_type.shape.dim[2].dim_value
    if classes != 18385:
        raise ValueError("Unexpected recognition dictionary size")
    # The existing greedy decoder chooses the first maximum on ties. ReduceMax
    # keeps the original softmax probability, including the existing 0.5 cutoff.
    model.graph.node.extend([
        helper.make_node("ArgMax", [output.name], ["ctc_indices"], axis=2, keepdims=0, select_last_index=0),
        helper.make_node("ReduceMax", [output.name], ["ctc_confidence"], axes=[2], keepdims=0),
    ])
    del model.graph.output[:]
    model.graph.output.extend([
        helper.make_tensor_value_info("ctc_indices", TensorProto.INT64, [1, "steps"]),
        helper.make_tensor_value_info("ctc_confidence", TensorProto.FLOAT, [1, "steps"]),
    ])
    metadata = {item.key: item.value for item in model.metadata_props}
    metadata.update(desktop_ctc_classes=str(classes), desktop_ctc_source_sha256=SOURCE_SHA256)
    helper.set_model_props(model, metadata)
    onnx.checker.check_model(model)
    destination.parent.mkdir(parents=True, exist_ok=True)
    onnx.save_model(model, destination)
    print(hashlib.sha256(destination.read_bytes()).hexdigest())


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("usage: compact_ctc_model.py SOURCE.onnx DESTINATION.onnx")
    compact(Path(sys.argv[1]), Path(sys.argv[2]))
