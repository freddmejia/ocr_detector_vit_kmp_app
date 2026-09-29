"""Rewrites a dynamic-range quantized .tflite with float32 weights, so its hybrid ops become plain float ops.

TensorFlow Lite C 2.17 (iOS) runs `plate_ocr.tflite`'s hybrid FULLY_CONNECTED (int8 per-channel weights, float
input, `asymmetric_quantize_inputs`) on a dynamically shaped input and returns all zeros, so every plate reads as
empty. LiteRT on Android runs the same file correctly. Dequantizing the weights (`(q - zero_point) * scale`, per
channel) keeps the trained values and avoids the hybrid kernels; the float ops skip the int8 rounding of the
activations, so readings can differ from the hybrid model in rare borderline cases.

Usage (needs `pip install ai-edge-litert numpy flatbuffers`; only its schema module is used):
    python scripts/dequantize_hybrid_weights.py IN.tflite OUT.tflite
"""

import argparse
import sys

import numpy as np
from ai_edge_litert import schema_py_generated as schema

from inline_tflite_buffers import FILE_IDENTIFIER, AlignedBuilder

HYBRID_OPS = {
    schema.BuiltinOperator.CONV_2D,
    schema.BuiltinOperator.DEPTHWISE_CONV_2D,
    schema.BuiltinOperator.FULLY_CONNECTED,
}


def op_code(model: "schema.ModelT", op: "schema.OperatorT") -> int:
    code = model.operatorCodes[op.opcodeIndex]
    return max(code.builtinCode, code.deprecatedBuiltinCode)


def dequantize(model: "schema.ModelT") -> int:
    done = set()
    for graph in model.subgraphs:
        for op in graph.operators:
            if op_code(model, op) not in HYBRID_OPS or len(op.inputs) < 2:
                continue
            data, weights = graph.tensors[op.inputs[0]], graph.tensors[op.inputs[1]]
            if data.type != schema.TensorType.FLOAT32 or weights.type != schema.TensorType.INT8:
                continue
            if op.inputs[1] in done:
                continue
            q = weights.quantization
            shape = list(weights.shape)
            values = np.asarray(model.buffers[weights.buffer].data, dtype=np.uint8).view(np.int8).reshape(shape)
            scale = np.asarray(q.scale, dtype=np.float32)
            zero_point = np.asarray(q.zeroPoint if q.zeroPoint is not None else [0] * len(scale), dtype=np.float32)
            broadcast = [1] * len(shape)
            if len(scale) > 1:
                broadcast[q.quantizedDimension] = len(scale)
            floats = (values.astype(np.float32) - zero_point.reshape(broadcast)) * scale.reshape(broadcast)
            model.buffers[weights.buffer].data = np.frombuffer(floats.astype("<f4").tobytes(), dtype=np.uint8)
            weights.type = schema.TensorType.FLOAT32
            weights.quantization = None
            done.add(op.inputs[1])
    return len(done)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input")
    parser.add_argument("output")
    args = parser.parse_args()
    with open(args.input, "rb") as f:
        data = f.read()
    model = schema.ModelT.InitFromPackedBuf(bytearray(data), 0)
    if any(buffer.offset > 1 for buffer in model.buffers):
        sys.exit("The model stores weights after the flatbuffer: run inline_tflite_buffers.py first")
    count = dequantize(model)
    builder = AlignedBuilder(len(data) * 4 + (1 << 20))
    builder.Finish(model.Pack(builder), file_identifier=FILE_IDENTIFIER)
    with open(args.output, "wb") as f:
        f.write(builder.Output())
    print(f"Dequantized {count} weight tensors", file=sys.stderr)


if __name__ == "__main__":
    main()
