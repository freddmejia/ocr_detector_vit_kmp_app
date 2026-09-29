"""Rewrites a .tflite so every weight buffer is stored inside the flatbuffer instead of after it.

Recent converters (ai-edge-torch / LiteRT) write the weights after the flatbuffer and point to them with
`Buffer.offset` / `Buffer.size`. LiteRT (Android) reads that layout, but TensorFlow Lite C 2.17 (iOS) does not: it
treats those constants as empty tensors and fails with "Input tensor N lacks data". The rewritten file holds the same
bytes for every weight and the same graph, so both runtimes compute the same thing.

Usage (needs `pip install ai-edge-litert numpy flatbuffers`; only its schema module is used):
    python scripts/inline_tflite_buffers.py IN.tflite OUT.tflite [--verify]
"""

import argparse
import sys

import flatbuffers
import numpy as np
from ai_edge_litert import schema_py_generated as schema

FILE_IDENTIFIER = b"TFL3"


class AlignedBuilder(flatbuffers.Builder):
    """Aligns byte vectors to 16 bytes, as the schema's `Buffer.data: [ubyte] (force_align: 16)` requires. The
    generated Python `Pack` ignores `force_align`, which would leave weights at arbitrary addresses."""

    def StartVector(self, elemSize, numElems, alignment):
        if elemSize == 1:
            alignment = max(alignment, 16)
        return super().StartVector(elemSize, numElems, alignment)


def inline_buffers(data: bytes) -> bytes:
    model = schema.ModelT.InitFromPackedBuf(bytearray(data), 0)
    moved = 0
    for buffer in model.buffers or []:
        if buffer.offset > 1:
            start, end = buffer.offset, buffer.offset + buffer.size
            if end > len(data):
                raise ValueError(f"Buffer [{start}, {end}) is outside the {len(data)}-byte file")
            buffer.data = np.frombuffer(data, dtype=np.uint8, count=buffer.size, offset=start)
            buffer.offset = 0
            buffer.size = 0
            moved += 1
    builder = AlignedBuilder(len(data) + (1 << 20))
    builder.Finish(model.Pack(builder), file_identifier=FILE_IDENTIFIER)
    print(f"Inlined {moved} of {len(model.buffers or [])} buffers", file=sys.stderr)
    return bytes(builder.Output())


def buffer_bytes(model: "schema.ModelT", data: bytes, index: int) -> bytes:
    buffer = model.buffers[index]
    if buffer.offset > 1:
        return data[buffer.offset:buffer.offset + buffer.size]
    return b"" if buffer.data is None else bytes(np.asarray(buffer.data, dtype=np.uint8))


def verify(original: bytes, rewritten: bytes) -> None:
    """Requires the same graph (operators, tensors, signatures) and the same bytes for every buffer."""
    a = schema.ModelT.InitFromPackedBuf(bytearray(original), 0)
    b = schema.ModelT.InitFromPackedBuf(bytearray(rewritten), 0)
    if any(buffer.offset > 1 for buffer in b.buffers):
        raise AssertionError("The rewritten model still has offset buffers")
    if len(a.buffers) != len(b.buffers):
        raise AssertionError("Buffer count differs")
    root = schema.Model.GetRootAs(rewritten, 0)
    for i in range(root.BuffersLength()):
        buffer = root.Buffers(i)
        if buffer.DataLength() and buffer.Offset() == 0:
            start = buffer._tab.Vector(buffer._tab.Offset(4))
            if start % 16:
                raise AssertionError(f"Buffer {i} data starts at {start}, not 16-byte aligned")
    for i in range(len(a.buffers)):
        if buffer_bytes(a, original, i) != buffer_bytes(b, rewritten, i):
            raise AssertionError(f"Buffer {i} differs")
    # Graph without the buffers: pack both with empty buffers and compare the bytes.
    for model in (a, b):
        model.buffers = [schema.BufferT() for _ in model.buffers]
    packed = []
    for model in (a, b):
        builder = flatbuffers.Builder(1 << 20)
        builder.Finish(model.Pack(builder), file_identifier=FILE_IDENTIFIER)
        packed.append(bytes(builder.Output()))
    if packed[0] != packed[1]:
        raise AssertionError("Operators, tensors or signatures differ")
    print(f"Verified: same graph and same bytes in all {len(b.buffers)} buffers", file=sys.stderr)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("input")
    parser.add_argument("output")
    parser.add_argument("--verify", action="store_true", help="check that the graph and every weight are unchanged")
    args = parser.parse_args()
    with open(args.input, "rb") as f:
        data = f.read()
    rewritten = inline_buffers(data)
    if args.verify:
        verify(data, rewritten)
    with open(args.output, "wb") as f:
        f.write(rewritten)


if __name__ == "__main__":
    main()
