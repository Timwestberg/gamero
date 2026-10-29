#!/usr/bin/env python3
"""Store Kokoro's float32 weights as float16 for the browser download.

The graph is unchanged except that every float32 weight with at least 1024
elements is stored as float16 and cast back to float32 by a Cast node at the
top of the graph. Compute stays float32, so this is not an fp16 model: only
the stored weights lose precision (about 5e-4 relative). onnxruntime folds the
casts when the session is created.

A full float16 conversion (onnxconverter-common) breaks this graph: shape
arithmetic in the LSTM path collapses to zero length, and int8 dynamic
quantization returns NaN on WebGPU. This keeps the model's numerics and
halves the file: 325,560,556 bytes in, 163,422,642 bytes out.

The output hash is pinned in web/hearing/src/models.js (KOKORO_WEB). It was
produced with onnx 1.19.1 and numpy 2.0.2; a different onnx may serialize
differently, and the fetch script will then refuse the file.

usage: fp16-weights.py --input model.onnx --output kokoro-v1_0-fp16w.onnx
"""

from __future__ import annotations

import sys

MIN_ELEMENTS = 1024


def convert(src: str, dst: str) -> None:
    try:
        import numpy as np
        import onnx
        from onnx import TensorProto, helper, numpy_helper
    except ImportError as error:
        raise SystemExit(
            f"{error}. Install the pinned tools, for example:\n"
            "  python3 -m venv .venv-voice && .venv-voice/bin/pip install onnx==1.19.1 numpy==2.0.2\n"
            "then run with KOKORO_PYTHON=.venv-voice/bin/python"
        )

    model = onnx.load(src)
    graph = model.graph
    casts = []
    for tensor in list(graph.initializer):
        if tensor.data_type != TensorProto.FLOAT or int(np.prod(tensor.dims)) < MIN_ELEMENTS:
            continue
        half = numpy_helper.from_array(numpy_helper.to_array(tensor).astype(np.float16), tensor.name + "__fp16")
        graph.initializer.remove(tensor)
        graph.initializer.append(half)
        casts.append(helper.make_node("Cast", [half.name], [tensor.name], to=TensorProto.FLOAT, name=tensor.name + "__cast"))
    nodes = []
    for node in graph.node:
        value = next((a for a in node.attribute if a.name == "value"), None) if node.op_type == "Constant" else None
        if value is not None and value.t.data_type == TensorProto.FLOAT and int(np.prod(value.t.dims)) >= MIN_ELEMENTS:
            name = node.output[0] + "__fp16"
            graph.initializer.append(numpy_helper.from_array(numpy_helper.to_array(value.t).astype(np.float16), name))
            casts.append(helper.make_node("Cast", [name], [node.output[0]], to=TensorProto.FLOAT, name=name + "__cast"))
            continue
        nodes.append(node)
    del graph.node[:]
    graph.node.extend(casts + nodes)
    onnx.save(model, dst)
    print(f"stored {len(casts)} weights as float16 -> {dst}")


def main(argv: list[str]) -> None:
    src = dst = None
    index = 0
    while index < len(argv):
        if argv[index] == "--input":
            index += 1
            src = argv[index]
        elif argv[index] == "--output":
            index += 1
            dst = argv[index]
        else:
            raise SystemExit(f"unknown argument {argv[index]}")
        index += 1
    if src is None or dst is None:
        raise SystemExit("usage: fp16-weights.py --input model.onnx --output kokoro-v1_0-fp16w.onnx")
    convert(src, dst)


if __name__ == "__main__":
    main(sys.argv[1:])
