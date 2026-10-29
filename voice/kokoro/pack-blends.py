#!/usr/bin/env python3
"""Write the chat blends into a derived Kokoro voices.bin.

Kokoro v1.0 stores one float32 style tensor per speaker, shaped
[speakers, frames, dim]. sherpa-onnx reads n_speakers from the ONNX
metadata (54), so a 55th speaker cannot be appended without rebuilding
that metadata. Slots 50-53 are zm_yunxi, zm_yunxia, zm_yunyang, and
em_santa. English chat does not select them. This script copies the
upstream table and overwrites those slots with the weighted blends in
blends.json. The upstream file is left unchanged.

The blend is the weighted sum of the source tensors, divided by the sum
of the weights, accumulated in float64 and stored as float32. Running it
twice on the same upstream bytes yields the same derived hash.
"""

from __future__ import annotations

import hashlib
import json
import struct
import sys
from pathlib import Path


class Layout:
    def __init__(self, speakers: int, frames: int, dim: int) -> None:
        self.speakers = speakers
        self.frames = frames
        self.dim = dim

    @property
    def span(self) -> int:
        return self.frames * self.dim

    @property
    def speaker_bytes(self) -> int:
        return self.span * 4

    @property
    def total_bytes(self) -> int:
        return self.speakers * self.speaker_bytes


def load_recipe(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def layout_of(recipe: dict) -> Layout:
    raw = recipe["layout"]
    return Layout(int(raw["speakers"]), int(raw["frames"]), int(raw["dim"]))


def blend_speaker(table: bytes, layout: Layout, sources: list[tuple[int, float]]) -> bytes:
    total = sum(weight for _, weight in sources)
    if total <= 0:
        raise SystemExit("blend weights must sum to more than zero")
    acc = [0.0] * layout.span
    for sid, weight in sources:
        scale = weight / total
        start = sid * layout.speaker_bytes
        values = struct.unpack_from("<" + str(layout.span) + "f", table, start)
        for index, value in enumerate(values):
            acc[index] += value * scale
    return struct.pack("<" + str(layout.span) + "f", *acc)


def apply_blends(table: bytes, recipe: dict) -> bytes:
    layout = layout_of(recipe)
    if len(table) != layout.total_bytes:
        raise SystemExit(
            f"voices.bin is {len(table)} bytes, expected {layout.total_bytes} "
            f"for {layout.speakers}x{layout.frames}x{layout.dim} float32"
        )
    speakers = {name: int(sid) for name, sid in recipe["speakers"].items()}
    out = bytearray(table)
    used = set()
    for voice in recipe["voices"]:
        sid = int(voice["sid"])
        if sid in used:
            raise SystemExit(f"duplicate blend sid {sid}")
        if sid < 0 or sid >= layout.speakers:
            raise SystemExit(f"blend sid {sid} is outside 0..{layout.speakers - 1}")
        used.add(sid)
        sources = []
        for item in voice["weights"]:
            name = item["speaker"]
            if name not in speakers:
                raise SystemExit(f"unknown speaker {name} in {voice['id']}")
            sources.append((speakers[name], float(item["weight"])))
        blended = blend_speaker(table, layout, sources)
        start = sid * layout.speaker_bytes
        out[start : start + layout.speaker_bytes] = blended
    return bytes(out)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def self_check() -> None:
    layout = Layout(4, 2, 2)
    raw = bytearray()
    for sid in range(layout.speakers):
        for index in range(layout.span):
            raw += struct.pack("<f", float(sid * 10 + index))
    recipe = {
        "layout": {"speakers": 4, "frames": 2, "dim": 2},
        "speakers": {"s0": 0, "s1": 1},
        "voices": [
            {
                "id": "blend",
                "sid": 3,
                "weights": [
                    {"speaker": "s0", "weight": "0.70"},
                    {"speaker": "s1", "weight": "0.30"},
                ],
            }
        ],
    }
    out = apply_blends(bytes(raw), recipe)
    got = struct.unpack_from("<4f", out, 3 * layout.speaker_bytes)
    expect = tuple(0.7 * index + 0.3 * (10 + index) for index in range(4))
    for index, value in enumerate(got):
        if abs(value - expect[index]) > 1e-5:
            raise SystemExit(f"blend self-check mismatch at {index}: {value} != {expect[index]}")
    if out[: layout.speaker_bytes] != raw[: layout.speaker_bytes]:
        raise SystemExit("blend self-check overwrote a source speaker")
    again = apply_blends(bytes(raw), recipe)
    if again != out:
        raise SystemExit("blend self-check is not reproducible")
    print("pack-blends self-check: ok")


def main(argv: list[str]) -> None:
    if "--self-check" in argv:
        self_check()
        return
    recipe_path = Path(__file__).with_name("blends.json")
    upstream = None
    output = None
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg == "--recipe":
            index += 1
            recipe_path = Path(argv[index])
        elif arg == "--upstream":
            index += 1
            upstream = Path(argv[index])
        elif arg == "--output":
            index += 1
            output = Path(argv[index])
        else:
            raise SystemExit(f"unknown argument {arg}")
        index += 1
    if upstream is None or output is None:
        raise SystemExit("usage: pack-blends.py --upstream voices.bin --output voices-blends.bin")
    recipe = load_recipe(recipe_path)
    table = upstream.read_bytes()
    pinned = recipe.get("upstreamVoicesSha256") or ""
    got = sha256(table)
    if pinned and got != pinned:
        raise SystemExit(f"upstream voices.bin hash {got} != {pinned}")
    derived = apply_blends(table, recipe)
    digest = sha256(derived)
    expect = recipe.get("derivedVoicesSha256") or ""
    if expect and digest != expect:
        raise SystemExit(f"derived voices.bin hash {digest} != {expect}")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(derived)
    print(f"voices-blends.bin {digest}")


if __name__ == "__main__":
    main(sys.argv[1:])
