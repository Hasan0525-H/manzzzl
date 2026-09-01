#!/usr/bin/env python3
"""Measure an exported Manzl student with explicit dataset provenance.

The evaluator measures all three ONNX heads used by the Android runtime:
semantic logits, corner logits and wall orientation. It does not decide release readiness.
Release thresholds are pre-registered separately from real validation evidence.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib

import cv2
import numpy as np

SEMANTIC_CLASSES = [
    "background",
    "wall_face",
    "door",
    "window",
    "stair",
    "column",
    "room_boundary",
    "courtyard",
    "shaft",
]

DEFAULT_DOMAIN = "generated-validation-not-real-plan-benchmark"
ALLOWED_DOMAINS = {
    DEFAULT_DOMAIN,
    "private-real-validation",
    "private-real-held-out-test",
}
# Matches StudentWallGeometryDecoder.MIN_CORNER_SNAP_PROBABILITY.
RUNTIME_CORNER_THRESHOLD = 0.56


def segmentation_metrics(
    predicted: np.ndarray,
    target: np.ndarray,
    valid: np.ndarray,
    classes: int = len(SEMANTIC_CLASSES),
) -> dict[str, np.ndarray | float]:
    if predicted.shape != target.shape or target.shape != valid.shape:
        raise ValueError("predicted, target and valid masks must share shape")
    intersections = np.zeros(classes, dtype=np.int64)
    unions = np.zeros(classes, dtype=np.int64)
    tp = np.zeros(classes, dtype=np.int64)
    fp = np.zeros(classes, dtype=np.int64)
    fn = np.zeros(classes, dtype=np.int64)

    for class_id in range(classes):
        pred_class = (predicted == class_id) & valid
        target_class = (target == class_id) & valid
        intersections[class_id] = np.count_nonzero(pred_class & target_class)
        unions[class_id] = np.count_nonzero(pred_class | target_class)
        tp[class_id] = intersections[class_id]
        fp[class_id] = np.count_nonzero(pred_class & ~target_class)
        fn[class_id] = np.count_nonzero(~pred_class & target_class & valid)

    iou = np.divide(intersections, np.maximum(unions, 1), dtype=np.float64)
    present = unions > 0
    mean_iou = float(iou[present].mean()) if np.any(present) else 0.0
    return {
        "intersection": intersections,
        "union": unions,
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "iou": iou,
        "mean_iou": mean_iou,
    }


def corner_metrics(
    probabilities: np.ndarray,
    target: np.ndarray,
    valid: np.ndarray,
    threshold: float = RUNTIME_CORNER_THRESHOLD,
) -> dict[str, float | int]:
    if probabilities.shape != target.shape or target.shape != valid.shape:
        raise ValueError("corner probability, target and valid masks must share shape")
    if not 0.0 < threshold < 1.0:
        raise ValueError("corner threshold must be within (0, 1)")
    finite = np.isfinite(probabilities) & np.isfinite(target)
    mask = valid & finite
    predicted = probabilities >= threshold
    expected = target >= 0.5
    tp = int(np.count_nonzero(predicted & expected & mask))
    fp = int(np.count_nonzero(predicted & ~expected & mask))
    fn = int(np.count_nonzero(~predicted & expected & mask))
    count = int(np.count_nonzero(mask))
    abs_error_sum = float(np.abs(probabilities[mask] - target[mask]).sum()) if count else 0.0
    return {
        "tp": tp,
        "fp": fp,
        "fn": fn,
        "evaluatedPixels": count,
        "supportPixels": int(np.count_nonzero(expected & mask)),
        "absoluteErrorSum": abs_error_sum,
    }


def orientation_metrics(
    predicted: np.ndarray,
    target: np.ndarray,
    valid: np.ndarray,
) -> dict[str, float | int]:
    if predicted.shape != target.shape or predicted.ndim != 3 or predicted.shape[0] != 2:
        raise ValueError("orientation tensors must both be [2,H,W]")
    if valid.shape != predicted.shape[1:]:
        raise ValueError("orientation valid mask must match spatial shape")
    predicted = np.asarray(predicted, dtype=np.float64)
    target = np.asarray(target, dtype=np.float64)
    pred_norm = np.linalg.norm(predicted, axis=0)
    target_norm = np.linalg.norm(target, axis=0)
    mask = valid & np.isfinite(pred_norm) & np.isfinite(target_norm) & (pred_norm > 1e-6) & (target_norm > 1e-6)
    count = int(np.count_nonzero(mask))
    if not count:
        return {"supportPixels": 0, "absCosineSum": 0.0, "angularErrorDegreesSum": 0.0}
    dot = (predicted[0] * target[0] + predicted[1] * target[1]) / np.maximum(pred_norm * target_norm, 1e-12)
    abs_cosine = np.clip(np.abs(dot[mask]), 0.0, 1.0)
    angles = np.degrees(np.arccos(abs_cosine))
    return {
        "supportPixels": count,
        "absCosineSum": float(abs_cosine.sum()),
        "angularErrorDegreesSum": float(angles.sum()),
    }


def _resize_scalar(value: np.ndarray, size: int, interpolation: int) -> np.ndarray:
    return cv2.resize(value, (size, size), interpolation=interpolation)


def prepare_sample(path: pathlib.Path, size: int) -> dict[str, np.ndarray]:
    with np.load(path, allow_pickle=False) as sample:
        image = np.asarray(sample["image"])
        semantic = np.asarray(sample["semantic"], dtype=np.int64)
        supervision = np.asarray(sample["supervision_mask"], dtype=np.float32) if "supervision_mask" in sample else np.ones_like(semantic, dtype=np.float32)
        corners = np.asarray(sample["corners"], dtype=np.float32) if "corners" in sample else np.zeros_like(semantic, dtype=np.float32)
        corner_mask = np.asarray(sample["corner_mask"], dtype=np.float32) if "corner_mask" in sample else supervision.copy()
        orientation = np.asarray(sample["orientation"], dtype=np.float32) if "orientation" in sample else np.zeros((2, *semantic.shape), dtype=np.float32)
        wall_mask = np.asarray(sample["wall_mask"], dtype=np.float32) if "wall_mask" in sample else (semantic == 1).astype(np.float32)
        orientation_mask = np.asarray(sample["orientation_mask"], dtype=np.float32) if "orientation_mask" in sample else wall_mask * supervision

    if image.ndim != 3 or image.shape[-1] != 3 or semantic.ndim != 2:
        raise ValueError(f"invalid sample shape: {path}")
    expected = semantic.shape
    for name, value in {
        "supervision_mask": supervision,
        "corners": corners,
        "corner_mask": corner_mask,
        "wall_mask": wall_mask,
        "orientation_mask": orientation_mask,
    }.items():
        if value.shape != expected:
            raise ValueError(f"{name} must match semantic shape in {path}: {value.shape} != {expected}")
    if orientation.shape != (2, *expected):
        raise ValueError(f"orientation must be [2,H,W] in {path}, got {orientation.shape}")

    image = image.astype(np.float32)
    if image.max() > 1.5:
        image /= 255.0
    image = _resize_scalar(image, size, cv2.INTER_LINEAR)
    semantic = _resize_scalar(semantic.astype(np.float32), size, cv2.INTER_NEAREST).astype(np.int64)
    supervision = _resize_scalar(supervision, size, cv2.INTER_NEAREST) > 0.5
    corners = np.clip(_resize_scalar(corners, size, cv2.INTER_LINEAR), 0.0, 1.0)
    corner_mask = _resize_scalar(corner_mask, size, cv2.INTER_NEAREST) > 0.5
    wall_mask = _resize_scalar(wall_mask, size, cv2.INTER_NEAREST) > 0.5
    orientation_mask = _resize_scalar(orientation_mask, size, cv2.INTER_NEAREST) > 0.5
    orientation = np.stack([
        _resize_scalar(orientation[0], size, cv2.INTER_LINEAR),
        _resize_scalar(orientation[1], size, cv2.INTER_LINEAR),
    ]).astype(np.float32)
    orientation_norm = np.linalg.norm(orientation, axis=0, keepdims=True)
    orientation = np.divide(orientation, np.maximum(orientation_norm, 1e-6), out=np.zeros_like(orientation), where=orientation_norm > 1e-6)
    image = ((image - 0.5) / 0.5).transpose(2, 0, 1)[None, ...].astype(np.float32)
    return {
        "image": image,
        "semantic": semantic,
        "supervision": supervision,
        "corners": corners,
        "corner_mask": corner_mask,
        "orientation": orientation,
        "orientation_mask": orientation_mask & wall_mask,
    }


def evaluate(
    model: pathlib.Path,
    data: pathlib.Path,
    size: int,
    max_samples: int | None = None,
    domain: str = DEFAULT_DOMAIN,
) -> dict:
    import onnxruntime as ort

    if domain not in ALLOWED_DOMAINS:
        raise ValueError(f"unsupported evaluation domain: {domain}")
    files = sorted(data.rglob("*.npz"))
    if max_samples is not None:
        files = files[:max_samples]
    if not files:
        raise RuntimeError(f"no NPZ validation samples under {data}")

    session = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"], sess_options=ort.SessionOptions())
    input_names = [item.name for item in session.get_inputs()]
    output_names = [item.name for item in session.get_outputs()]
    if input_names != ["image"]:
        raise RuntimeError(f"unexpected ONNX inputs: {input_names}")
    if output_names != ["semantic_logits", "corner_logits", "wall_orientation"]:
        raise RuntimeError(f"unexpected ONNX outputs: {output_names}")

    semantic_totals = {key: np.zeros(len(SEMANTIC_CLASSES), dtype=np.int64) for key in ("intersection", "union", "tp", "fp", "fn")}
    corner_totals = {"tp": 0, "fp": 0, "fn": 0, "evaluatedPixels": 0, "supportPixels": 0, "absoluteErrorSum": 0.0}
    orientation_totals = {"supportPixels": 0, "absCosineSum": 0.0, "angularErrorDegreesSum": 0.0}

    for index, path in enumerate(files, start=1):
        sample = prepare_sample(path, size)
        semantic_logits, corner_logits, orientation = session.run(None, {"image": sample["image"]})
        if semantic_logits.shape != (1, len(SEMANTIC_CLASSES), size, size):
            raise RuntimeError(f"unexpected semantic shape: {semantic_logits.shape}")
        if corner_logits.shape != (1, 1, size, size):
            raise RuntimeError(f"unexpected corner shape: {corner_logits.shape}")
        if orientation.shape != (1, 2, size, size):
            raise RuntimeError(f"unexpected orientation shape: {orientation.shape}")
        if not (np.isfinite(semantic_logits).all() and np.isfinite(corner_logits).all() and np.isfinite(orientation).all()):
            raise RuntimeError("ONNX inference produced non-finite values")

        predicted = semantic_logits.argmax(axis=1)[0]
        semantic = segmentation_metrics(predicted, sample["semantic"], sample["supervision"])
        for key in semantic_totals:
            semantic_totals[key] += semantic[key]

        probabilities = 1.0 / (1.0 + np.exp(-np.clip(corner_logits[0, 0], -30.0, 30.0)))
        corners = corner_metrics(probabilities, sample["corners"], sample["corner_mask"])
        for key in corner_totals:
            corner_totals[key] += corners[key]

        oriented = orientation_metrics(orientation[0], sample["orientation"], sample["orientation_mask"])
        for key in orientation_totals:
            orientation_totals[key] += oriented[key]

        if index % 16 == 0:
            print(f"evaluated {index}/{len(files)}")

    union = semantic_totals["union"]
    iou = semantic_totals["intersection"] / np.maximum(union, 1)
    present = union > 0
    per_class = {}
    for class_id, name in enumerate(SEMANTIC_CLASSES):
        precision = semantic_totals["tp"][class_id] / max(1, semantic_totals["tp"][class_id] + semantic_totals["fp"][class_id])
        recall = semantic_totals["tp"][class_id] / max(1, semantic_totals["tp"][class_id] + semantic_totals["fn"][class_id])
        per_class[name] = {
            "present": bool(present[class_id]),
            "iou": float(iou[class_id]) if present[class_id] else None,
            "precision": float(precision),
            "recall": float(recall),
            "supportPixels": int(semantic_totals["tp"][class_id] + semantic_totals["fn"][class_id]),
        }

    corner_precision = corner_totals["tp"] / max(1, corner_totals["tp"] + corner_totals["fp"])
    corner_recall = corner_totals["tp"] / max(1, corner_totals["tp"] + corner_totals["fn"])
    corner_f1 = 2.0 * corner_precision * corner_recall / max(corner_precision + corner_recall, 1e-12)
    orientation_support = int(orientation_totals["supportPixels"])

    return {
        "schema": 2,
        "domain": domain,
        "samples": len(files),
        "inputSize": size,
        "semantic": {
            "meanIoU": float(iou[present].mean()) if np.any(present) else 0.0,
            "perClass": per_class,
        },
        "corners": {
            "runtimeThreshold": RUNTIME_CORNER_THRESHOLD,
            "thresholdMatchesAndroidCornerSnap": True,
            "precision": float(corner_precision),
            "recall": float(corner_recall),
            "f1": float(corner_f1),
            "meanAbsoluteError": float(corner_totals["absoluteErrorSum"] / max(1, corner_totals["evaluatedPixels"])),
            "supportPixels": int(corner_totals["supportPixels"]),
            "evaluatedPixels": int(corner_totals["evaluatedPixels"]),
        },
        "orientation": {
            "signInvariant": True,
            "meanAbsCosine": float(orientation_totals["absCosineSum"] / max(1, orientation_support)),
            "meanAngularErrorDegrees": float(orientation_totals["angularErrorDegreesSum"] / max(1, orientation_support)),
            "supportPixels": orientation_support,
        },
        "releaseReady": False,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=pathlib.Path, required=True)
    parser.add_argument("--data", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--max-samples", type=int)
    parser.add_argument("--domain", choices=sorted(ALLOWED_DOMAINS), default=DEFAULT_DOMAIN)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = evaluate(args.model, args.data, args.size, args.max_samples, args.domain)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
