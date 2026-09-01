#!/usr/bin/env python3
"""Relative-path-safe adapter for pinned Raster2Graph-512 predictions.

The evidence rasterization contract remains in ``adapt_raster2seq_predictions_impl``. This facade only
preserves recursive corpus identity: ``jsons/family/plan.json`` reads ``family/plan.png`` and writes
``family/plan.npz``. That makes Raster2Seq paths align exactly with MitUNet, CubiCasa and the private
source-group manifest, including when different families reuse the same basename.
"""
from __future__ import annotations

import argparse
import json
import pathlib

import cv2
import numpy as np

import adapt_raster2seq_predictions_impl as _impl
from adapt_raster2seq_predictions_impl import *  # noqa: F401,F403


def _safe_relative(path: pathlib.Path) -> pathlib.Path:
    path = pathlib.Path(path)
    if path.is_absolute() or ".." in path.parts or not path.parts:
        raise ValueError(f"prediction path must be safe and relative: {path}")
    return path


def load_image(save_root: pathlib.Path, relative_stem: pathlib.Path | str) -> np.ndarray:
    stem = _safe_relative(pathlib.Path(relative_stem))
    candidates = [
        save_root / stem.with_suffix(".png"),
        save_root / stem.with_suffix(".jpg"),
        save_root / stem.with_suffix(".jpeg"),
    ]
    for path in candidates:
        if not path.exists():
            continue
        bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if bgr is not None:
            return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    raise FileNotFoundError(
        f"No transformed Raster2Seq source image found for {stem.as_posix()} under {save_root}"
    )


def adapt_one(
    json_path: pathlib.Path,
    save_root: pathlib.Path,
    output_root: pathlib.Path,
    confidence: float,
    line_width: int,
) -> pathlib.Path:
    json_root = (save_root / "jsons").resolve()
    relative_json = json_path.resolve().relative_to(json_root)
    relative_stem = _safe_relative(relative_json.with_suffix(""))
    image = load_image(save_root, relative_stem)
    height, width = image.shape[:2]
    semantic = np.zeros((height, width), dtype=np.uint8)
    valid_mask = np.zeros((height, width), dtype=np.uint8)
    corners = np.zeros((height, width), dtype=np.float32)
    orientation = np.zeros((2, height, width), dtype=np.float32)

    records = json.loads(json_path.read_text(encoding="utf-8"))
    if not isinstance(records, list):
        raise ValueError(f"Expected a JSON list in {json_path}")

    accepted = 0
    for record in records:
        if not isinstance(record, dict):
            continue
        try:
            category = int(record.get("category_id", -1))
        except (TypeError, ValueError):
            continue
        if category not in _impl.R2G_ROOM_CATEGORY_IDS:
            continue
        points = _impl.polygon_points(record.get("segmentation", []))
        if len(points) < 3:
            continue
        _impl.draw_room_boundary(
            semantic,
            valid_mask,
            corners,
            orientation,
            points,
            line_width=line_width,
        )
        accepted += 1

    if records and accepted == 0:
        raise RuntimeError(
            f"Raster2Seq emitted records but none matched the pinned Raster2Graph-512 room contract: {json_path}"
        )

    orientation_mask = (valid_mask > 0).astype(np.float32)
    orientation *= orientation_mask[None, ...]
    teacher_confidence = np.full((height, width), confidence, dtype=np.float32)

    destination = output_root / relative_stem.with_suffix(".npz")
    destination.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        destination,
        image=image,
        semantic=semantic.astype(np.int64),
        semantic_classes=np.asarray(_impl.EVIDENCE_CLASSES, dtype="U32"),
        confidence=teacher_confidence,
        valid_mask=valid_mask,
        corners=corners,
        corner_confidence=teacher_confidence,
        orientation=orientation,
        orientation_mask=orientation_mask,
        teacher_format=np.asarray(["raster2seq-r2g512-json-v3-room-boundary-only"], dtype="U64"),
    )
    return destination


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--prediction-root",
        type=pathlib.Path,
        required=True,
        help="Raster2Seq save directory containing jsons/** and matching transformed images",
    )
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--confidence", type=float, default=0.90)
    parser.add_argument("--line-width", type=int, default=3)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not 0.0 < args.confidence <= 1.0:
        raise ValueError("--confidence must be in (0,1]")
    if args.line_width < 1:
        raise ValueError("--line-width must be >= 1")

    json_root = args.prediction_root / "jsons"
    if not json_root.is_dir():
        raise FileNotFoundError(f"Missing Raster2Seq jsons directory: {json_root}")
    json_files = sorted(json_root.rglob("*.json"))
    if not json_files:
        raise RuntimeError(f"No Raster2Seq JSON predictions under {json_root}")

    written = [
        adapt_one(
            json_path=json_path,
            save_root=args.prediction_root,
            output_root=args.output,
            confidence=args.confidence,
            line_width=args.line_width,
        )
        for json_path in json_files
    ]
    print(f"adapted Raster2Seq R2G-512 predictions: {len(written)}")
    print("relative paths preserved: yes")
    print("semantic classes: room_boundary only; openings are abstentions")
    print("output:", args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
