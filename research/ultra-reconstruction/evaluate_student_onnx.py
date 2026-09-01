#!/usr/bin/env python3
"""Measure the exported Manzl student by semantic class using local ONNX Runtime.

The bootstrap report is deliberately labelled generated-validation. It prevents a high aggregate
number from hiding a collapsed door/window/stair/column class, but it is never presented as proof of
real Saudi/Arabic-plan accuracy.
"""

from __future__ import annotations

import argparse
import json
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

    iou = np.divide(
        intersections,
        np.maximum(unions, 1),
        dtype=np.float64,
    )
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


def prepare_sample(path: pathlib.Path, size: int):
    with np.load(path, allow_pickle=False) as sample:
        image = np.asarray(sample["image"])
        semantic = np.asarray(sample["semantic"], dtype=np.int64)
        supervision = (
            np.asarray(sample["supervision_mask"], dtype=np.float32)
            if "supervision_mask" in sample
            else np.ones_like(semantic, dtype=np.float32)
        )
    if image.ndim != 3 or image.shape[-1] != 3 or semantic.ndim != 2:
        raise ValueError(f"invalid sample shape: {path}")
    image = image.astype(np.float32)
    if image.max() > 1.5:
        image /= 255.0
    image = cv2.resize(image, (size, size), interpolation=cv2.INTER_LINEAR)
    semantic = cv2.resize(semantic.astype(np.float32), (size, size), interpolation=cv2.INTER_NEAREST).astype(np.int64)
    supervision = cv2.resize(supervision, (size, size), interpolation=cv2.INTER_NEAREST) > 0.5
    image = ((image - 0.5) / 0.5).transpose(2, 0, 1)[None, ...].astype(np.float32)
    return image, semantic, supervision


def evaluate(model: pathlib.Path, data: pathlib.Path, size: int, max_samples: int | None = None) -> dict:
    import onnxruntime as ort

    files = sorted(data.rglob("*.npz"))
    if max_samples is not None:
        files = files[:max_samples]
    if not files:
        raise RuntimeError(f"no NPZ validation samples under {data}")

    session = ort.InferenceSession(
        str(model),
        providers=["CPUExecutionProvider"],
        sess_options=ort.SessionOptions(),
    )
    input_names = [item.name for item in session.get_inputs()]
    output_names = [item.name for item in session.get_outputs()]
    if input_names != ["image"]:
        raise RuntimeError(f"unexpected ONNX inputs: {input_names}")
    if output_names != ["semantic_logits", "corner_logits", "wall_orientation"]:
        raise RuntimeError(f"unexpected ONNX outputs: {output_names}")

    totals = {
        "intersection": np.zeros(len(SEMANTIC_CLASSES), dtype=np.int64),
        "union": np.zeros(len(SEMANTIC_CLASSES), dtype=np.int64),
        "tp": np.zeros(len(SEMANTIC_CLASSES), dtype=np.int64),
        "fp": np.zeros(len(SEMANTIC_CLASSES), dtype=np.int64),
        "fn": np.zeros(len(SEMANTIC_CLASSES), dtype=np.int64),
    }
    for index, path in enumerate(files, start=1):
        image, target, valid = prepare_sample(path, size)
        semantic_logits, corner_logits, orientation = session.run(None, {"image": image})
        if semantic_logits.shape != (1, len(SEMANTIC_CLASSES), size, size):
            raise RuntimeError(f"unexpected semantic shape: {semantic_logits.shape}")
        if corner_logits.shape != (1, 1, size, size):
            raise RuntimeError(f"unexpected corner shape: {corner_logits.shape}")
        if orientation.shape != (1, 2, size, size):
            raise RuntimeError(f"unexpected orientation shape: {orientation.shape}")
        if not (np.isfinite(semantic_logits).all() and np.isfinite(corner_logits).all() and np.isfinite(orientation).all()):
            raise RuntimeError("ONNX inference produced non-finite values")

        predicted = semantic_logits.argmax(axis=1)[0]
        metrics = segmentation_metrics(predicted, target, valid)
        for key in totals:
            totals[key] += metrics[key]
        if index % 16 == 0:
            print(f"evaluated {index}/{len(files)}")

    union = totals["union"]
    iou = totals["intersection"] / np.maximum(union, 1)
    present = union > 0
    per_class = {}
    for class_id, name in enumerate(SEMANTIC_CLASSES):
        precision = totals["tp"][class_id] / max(1, totals["tp"][class_id] + totals["fp"][class_id])
        recall = totals["tp"][class_id] / max(1, totals["tp"][class_id] + totals["fn"][class_id])
        per_class[name] = {
            "present": bool(present[class_id]),
            "iou": float(iou[class_id]) if present[class_id] else None,
            "precision": float(precision),
            "recall": float(recall),
            "supportPixels": int(totals["tp"][class_id] + totals["fn"][class_id]),
        }

    return {
        "schema": 1,
        "domain": "generated-validation-not-real-plan-benchmark",
        "samples": len(files),
        "inputSize": size,
        "meanIoU": float(iou[present].mean()) if np.any(present) else 0.0,
        "perClass": per_class,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", type=pathlib.Path, required=True)
    parser.add_argument("--data", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--max-samples", type=int)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    report = evaluate(args.model, args.data, args.size, args.max_samples)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
