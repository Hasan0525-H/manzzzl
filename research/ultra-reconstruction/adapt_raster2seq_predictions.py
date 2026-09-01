#!/usr/bin/env python3
"""Convert official Raster2Seq saved predictions into Manzl teacher-consensus NPZ samples.

Raster2Seq's official ``predict.py --save_pred`` writes one JSON per image under ``jsons/`` and a
matching transformed source image beside that directory. Each JSON item contains ``segmentation``
polygon coordinates and a ``category_id``. For CubiCasa-style checkpoints, categories 9/10 are
Window/Door; the remaining categories are room polygons.

This adapter deliberately keeps Raster2Seq in its strongest role: global polygons/topology. It does
not hallucinate wall thickness from room boundaries. Room polygon edges become ``room_boundary``
supervision, while predicted door/window polygons become semantic evidence. Exact wall faces still
come from MITUNet/CubiCasa/OpenCV/MobileSAM and are adjudicated against the original raster.

The output format is consumed by ``build_teacher_consensus.py``.
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
CLASS = {name: index for index, name in enumerate(SEMANTIC_CLASSES)}

CC5K_WINDOW = 9
CC5K_DOOR = 10


def polygon_points(raw) -> np.ndarray:
    array = np.asarray(raw, dtype=np.float32)
    if array.size < 6:
        return np.empty((0, 2), dtype=np.float32)
    array = array.reshape(-1, 2)
    finite = np.isfinite(array).all(axis=1)
    return array[finite]


def clipped_int_points(points: np.ndarray, width: int, height: int) -> np.ndarray:
    result = np.rint(points).astype(np.int32)
    result[:, 0] = np.clip(result[:, 0], 0, width - 1)
    result[:, 1] = np.clip(result[:, 1], 0, height - 1)
    return result


def draw_room_boundary(
    semantic: np.ndarray,
    corners: np.ndarray,
    orientation: np.ndarray,
    points: np.ndarray,
    line_width: int,
) -> None:
    height, width = semantic.shape
    integer = clipped_int_points(points, width, height)
    if len(integer) < 3:
        return

    cv2.polylines(
        semantic,
        [integer],
        isClosed=True,
        color=int(CLASS["room_boundary"]),
        thickness=line_width,
        lineType=cv2.LINE_8,
    )
    for point in integer:
        cv2.circle(corners, tuple(point), max(1, line_width), 1.0, thickness=-1, lineType=cv2.LINE_8)

    # Axial tangent orientation on the same boundary band. v and -v are intentionally equivalent;
    # the consensus builder uses doubled-angle averaging across teachers.
    for index in range(len(points)):
        a = points[index]
        b = points[(index + 1) % len(points)]
        dx = float(b[0] - a[0])
        dy = float(b[1] - a[1])
        length = math.hypot(dx, dy)
        if length < 1.0:
            continue
        ux, uy = dx / length, dy / length
        segment_mask = np.zeros((height, width), dtype=np.uint8)
        p0 = tuple(clipped_int_points(np.asarray([a]), width, height)[0])
        p1 = tuple(clipped_int_points(np.asarray([b]), width, height)[0])
        cv2.line(segment_mask, p0, p1, 1, thickness=line_width, lineType=cv2.LINE_8)
        active = segment_mask.astype(bool)
        orientation[0, active] = ux
        orientation[1, active] = uy


def fill_instance(semantic: np.ndarray, points: np.ndarray, class_name: str) -> None:
    height, width = semantic.shape
    integer = clipped_int_points(points, width, height)
    if len(integer) < 3:
        return
    cv2.fillPoly(semantic, [integer], color=int(CLASS[class_name]), lineType=cv2.LINE_8)


def load_image(save_root: pathlib.Path, stem: str) -> np.ndarray:
    candidates = [
        save_root / f"{stem}.png",
        save_root / f"{stem}.jpg",
        save_root / f"{stem}.jpeg",
    ]
    for path in candidates:
        if not path.exists():
            continue
        bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if bgr is not None:
            return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    raise FileNotFoundError(f"No transformed Raster2Seq source image found for {stem} under {save_root}")


def adapt_one(
    json_path: pathlib.Path,
    save_root: pathlib.Path,
    output_root: pathlib.Path,
    confidence: float,
    line_width: int,
) -> pathlib.Path:
    stem = json_path.stem
    image = load_image(save_root, stem)
    height, width = image.shape[:2]
    semantic = np.zeros((height, width), dtype=np.uint8)
    corners = np.zeros((height, width), dtype=np.float32)
    orientation = np.zeros((2, height, width), dtype=np.float32)

    records = json.loads(json_path.read_text(encoding="utf-8"))
    if not isinstance(records, list):
        raise ValueError(f"Expected a JSON list in {json_path}")

    # Draw room boundaries first, then doors/windows so semantic instances win where they overlap.
    room_polygons: list[np.ndarray] = []
    openings: list[tuple[np.ndarray, str]] = []
    for record in records:
        if not isinstance(record, dict):
            continue
        points = polygon_points(record.get("segmentation", []))
        if len(points) < 3:
            continue
        category = int(record.get("category_id", -1))
        if category == CC5K_WINDOW:
            openings.append((points, "window"))
        elif category == CC5K_DOOR:
            openings.append((points, "door"))
        else:
            room_polygons.append(points)

    for points in room_polygons:
        draw_room_boundary(semantic, corners, orientation, points, line_width=line_width)
    for points, class_name in openings:
        fill_instance(semantic, points, class_name)

    # Only emitted room-boundary pixels own orientation supervision.
    orientation_mask = (semantic == CLASS["room_boundary"]).astype(np.float32)
    orientation *= orientation_mask[None, ...]
    valid_mask = np.ones((height, width), dtype=np.uint8)
    teacher_confidence = np.full((height, width), confidence, dtype=np.float32)

    destination = output_root / f"{stem}.npz"
    destination.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        destination,
        image=image,
        semantic=semantic.astype(np.int64),
        semantic_classes=np.asarray(SEMANTIC_CLASSES, dtype="U32"),
        confidence=teacher_confidence,
        valid_mask=valid_mask,
        corners=corners,
        corner_confidence=teacher_confidence,
        orientation=orientation,
        orientation_mask=orientation_mask,
        teacher_format=np.asarray(["raster2seq-official-json"], dtype="U48"),
    )
    return destination


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--prediction-root",
        type=pathlib.Path,
        required=True,
        help="Raster2Seq save directory containing jsons/ and matching transformed images",
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
    json_files = sorted(json_root.glob("*.json"))
    if not json_files:
        raise RuntimeError(f"No Raster2Seq JSON predictions under {json_root}")

    written = []
    for json_path in json_files:
        written.append(
            adapt_one(
                json_path=json_path,
                save_root=args.prediction_root,
                output_root=args.output,
                confidence=args.confidence,
                line_width=args.line_width,
            )
        )
    print(f"adapted Raster2Seq predictions: {len(written)}")
    print("output:", args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
