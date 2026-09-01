#!/usr/bin/env python3
"""Relative-path-safe entry point for the pinned Raster2Seq teacher.

The heavy/pinned inference implementation lives unchanged in ``run_raster2seq_teacher_impl``. This
entry point only fixes corpus identity: recursive inputs preserve their full relative path in transformed
images and JSON predictions, so equal basenames in different private plan families can never overwrite
each other and downstream teacher consensus can join exact source provenance.
"""
from __future__ import annotations

import argparse
import json
import pathlib

import cv2

import run_raster2seq_teacher_impl as _impl
from run_raster2seq_teacher_impl import *  # noqa: F401,F403


def _safe_relative(source: pathlib.Path, root: pathlib.Path) -> pathlib.Path:
    relative = source.resolve().relative_to(root.resolve())
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        raise ValueError(f"unsafe Raster2Seq relative source path: {relative}")
    return relative


def write_prediction(
    output_root: pathlib.Path,
    relative_source: pathlib.Path,
    transformed_rgb,
    records: list[dict],
) -> None:
    relative = pathlib.Path(relative_source)
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        raise ValueError(f"prediction path must be safe and relative: {relative}")

    relative_json = relative.with_suffix(".json")
    relative_image = relative.with_suffix(".png")
    image_id = relative.with_suffix("").as_posix()
    json_path = output_root / "jsons" / relative_json
    image_path = output_root / relative_image
    json_path.parent.mkdir(parents=True, exist_ok=True)
    image_path.parent.mkdir(parents=True, exist_ok=True)

    for record in records:
        record["image_id"] = image_id
    json_path.write_text(json.dumps(records), encoding="utf-8")
    bgr = cv2.cvtColor(transformed_rgb, cv2.COLOR_RGB2BGR)
    if not cv2.imwrite(str(image_path), bgr):
        raise RuntimeError(f"Failed to save transformed Raster2Seq source image for {relative}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=pathlib.Path, required=True)
    parser.add_argument("--repository", type=pathlib.Path, required=True)
    parser.add_argument("--checkpoint-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--cpu-reference-attention", action="store_true")
    parser.add_argument("--recursive", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.input.is_dir():
        raise FileNotFoundError(f"Input directory does not exist: {args.input}")
    _impl.verify_source_revision(args.repository)
    checkpoint = args.checkpoint_dir / "checkpoint.pth"
    config_path = args.checkpoint_dir / "config.json"
    _impl.verify_checkpoint(checkpoint)
    config = _impl.load_and_validate_config(config_path)

    torch_module, model, device, upstream_args, token_type, ResizeAndPad, T = _impl.load_model(
        repository=args.repository,
        checkpoint=checkpoint,
        config=config,
        device_name=args.device,
        cpu_reference_attention=args.cpu_reference_attention,
    )

    images = _impl.discover_images(args.input, recursive=args.recursive)
    total_polygons = 0
    for source in images:
        relative = _safe_relative(source, args.input)
        transformed, tensor = _impl.preprocess_image(
            source,
            upstream_args.image_size,
            ResizeAndPad,
            T,
            torch_module,
        )
        with torch_module.inference_mode():
            outputs = model.forward_inference([tensor.to(device)], use_cache=True)
        records = _impl.decode_r2g_polygons(
            outputs,
            image_size=upstream_args.image_size,
            token_type=token_type,
            per_token_sem_loss=upstream_args.per_token_sem_loss,
        )
        write_prediction(args.output, relative, transformed, records)
        total_polygons += len(records)

    print(json.dumps({
        "teacher": "Raster2Seq/Raster2Graph-512",
        "sourceRevision": _impl.SOURCE_REVISION,
        "modelRevision": _impl.pinned.REVISION,
        "checkpointSha256": _impl.pinned.CHECKPOINT_SHA256,
        "checkpointBytes": _impl.pinned.CHECKPOINT_SIZE,
        "device": str(device),
        "cpuReferenceAttention": bool(args.cpu_reference_attention),
        "samples": len(images),
        "roomPolygons": total_polygons,
        "relativePathsPreserved": True,
        "semanticContract": "room_boundary-only-after-adaptation",
        "passed": True,
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
