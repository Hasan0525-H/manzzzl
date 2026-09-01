#!/usr/bin/env python3
"""Convert the pinned Raster2Graph-512 Raster2Seq predictions into Manzl teacher NPZ samples.

The selected Manzl Raster2Seq teacher is **Raster2Graph-512**, not the CubiCasa checkpoint. Its
published semantic label space contains room/space categories only; notably category 9 is
``washing_room`` and category 10 is ``PS``. They MUST NOT be interpreted as Window/Door. Earlier
CubiCasa-style adapters commonly use 9/10 for Window/Door, so this file intentionally locks the
pinned R2G contract and exports only ``room_boundary`` evidence.

Raster2Seq remains a global topology teacher. Every valid predicted R2G room polygon contributes a
thin closed boundary, corner heat evidence and tangent orientation. Room interiors, wall faces,
openings, stairs, columns, courtyards, shafts and background are all true abstentions. CubiCasa and
other independent experts provide opening/wall semantics.

The output is consumed by ``build_teacher_consensus.py`` and the stricter
``build_real_teacher_consensus.py``.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib

import cv2
import numpy as np

EVIDENCE_CLASSES = ["room_boundary"]
CLASS = {name: index for index, name in enumerate(EVIDENCE_CLASSES)}

# Official Raster2Graph label mapping at the pinned Raster2Seq source revision:
# 0 unknown, 1 living_room, 2 kitchen, 3 bedroom, 4 bathroom, 5 restroom,
# 6 balcony, 7 closet, 8 corridor, 9 washing_room, 10 PS, 11 outside.
R2G_ROOM_CATEGORY_IDS = frozenset(range(12))

# Legacy CubiCasa ids are retained only so older contract fixtures/importers do not crash while they
# are migrated. They are NOT opening mappings for the selected Raster2Graph-512 teacher. In this
# adapter categories 9 and 10 remain ordinary R2G room/space polygons and therefore produce only
# room_boundary evidence.
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
    valid_mask: np.ndarray,
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
    cv2.polylines(
        valid_mask,
        [integer],
        isClosed=True,
        color=1,
        thickness=line_width,
        lineType=cv2.LINE_8,
    )
    for point in integer:
        cv2.circle(corners, tuple(point), max(1, line_width), 1.0, thickness=-1, lineType=cv2.LINE_8)

    # Axial tangent orientation on the exact same boundary band. v and -v are equivalent; consensus
    # uses doubled-angle averaging when another independent orientation source is available.
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
        # Fail closed for any label outside the published R2G room label space. In particular there
        # is no Window/Door mapping in this checkpoint's label contract.
        if category not in R2G_ROOM_CATEGORY_IDS:
            continue
        points = polygon_points(record.get("segmentation", []))
        if len(points) < 3:
            continue
        draw_room_boundary(
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

    destination = output_root / f"{stem}.npz"
    destination.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        destination,
        image=image,
        semantic=semantic.astype(np.int64),
        semantic_classes=np.asarray(EVIDENCE_CLASSES, dtype="U32"),
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
    print("semantic classes: room_boundary only; openings are abstentions")
    print("output:", args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
