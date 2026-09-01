#!/usr/bin/env python3
"""Fail-closed contract check for bundled MobileSAM ONNX artifacts.

The Android refiner is intentionally coupled to a small, explicit SAM prompt contract. A checksum proves
which bytes were downloaded, but it does not prove that future code still feeds those bytes correctly.
This verifier therefore checks the exact pinned digests and the graph-level input/output contract that
MobileSamBoundaryRefiner consumes before an APK build is allowed to proceed.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib

import onnx

ENCODER = "mobile_sam_encoder.onnx"
DECODER = "mobile_sam_decoder.onnx"
EXPECTED_SHA256 = {
    ENCODER: "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749",
    DECODER: "93915fc7c993ab9d59ab8c9ccd3bce37f7509c81ab4150a74abd4d2abbd8570d",
}
DECODER_INPUTS = {
    "image_embeddings",
    "point_coords",
    "point_labels",
    "mask_input",
    "has_mask_input",
    "orig_im_size",
}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def graph_inputs(model: onnx.ModelProto) -> list[onnx.ValueInfoProto]:
    initializers = {item.name for item in model.graph.initializer}
    return [item for item in model.graph.input if item.name not in initializers]


def rank(value: onnx.ValueInfoProto) -> int:
    return len(value.type.tensor_type.shape.dim)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def verify(assets: pathlib.Path) -> dict:
    assets = assets.resolve()
    encoder_path = assets / ENCODER
    decoder_path = assets / DECODER
    for path in (encoder_path, decoder_path):
        require(path.is_file(), f"required runtime model missing: {path}")
        actual = sha256(path)
        require(
            actual == EXPECTED_SHA256[path.name],
            f"runtime model digest mismatch for {path.name}: {actual}",
        )

    encoder = onnx.load(encoder_path, load_external_data=False)
    decoder = onnx.load(decoder_path, load_external_data=False)
    onnx.checker.check_model(encoder)
    onnx.checker.check_model(decoder)

    encoder_inputs = graph_inputs(encoder)
    encoder_outputs = {item.name: item for item in encoder.graph.output}
    require(len(encoder_inputs) == 1, f"MobileSAM encoder must have one image input, got {[x.name for x in encoder_inputs]}")
    require(rank(encoder_inputs[0]) in (3, 4), f"unsupported encoder image rank: {rank(encoder_inputs[0])}")
    require("image_embeddings" in encoder_outputs, f"encoder output image_embeddings missing: {list(encoder_outputs)}")
    require(rank(encoder_outputs["image_embeddings"]) == 4, "encoder image_embeddings must be rank 4")

    decoder_inputs = {item.name: item for item in graph_inputs(decoder)}
    decoder_outputs = {item.name: item for item in decoder.graph.output}
    require(set(decoder_inputs) == DECODER_INPUTS, f"MobileSAM decoder inputs changed: {sorted(decoder_inputs)}")
    require("masks" in decoder_outputs, f"decoder masks output missing: {list(decoder_outputs)}")

    expected_ranks = {
        "image_embeddings": 4,
        "point_coords": 3,
        "point_labels": 2,
        "mask_input": 4,
        "has_mask_input": 1,
        "orig_im_size": 1,
    }
    for name, expected in expected_ranks.items():
        require(rank(decoder_inputs[name]) == expected, f"decoder {name} rank changed: {rank(decoder_inputs[name])} != {expected}")
    require(rank(decoder_outputs["masks"]) >= 3, "decoder masks output must retain spatial dimensions")

    report = {
        "schema": 1,
        "pipeline": "manzl-mobile-sam-apk-contract",
        "encoderSha256": EXPECTED_SHA256[ENCODER],
        "decoderSha256": EXPECTED_SHA256[DECODER],
        "encoderInput": encoder_inputs[0].name,
        "encoderOutputs": sorted(encoder_outputs),
        "decoderInputs": sorted(decoder_inputs),
        "decoderOutputs": sorted(decoder_outputs),
        "contractPassed": True,
    }
    return report


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--assets",
        type=pathlib.Path,
        default=pathlib.Path("manzl-app/src/main/assets/models"),
    )
    report = verify(parser.parse_args().assets)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
