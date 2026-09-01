#!/usr/bin/env python3
"""Render CC BY 4.0 ResPlan vector geometry into Manzl dense training samples.

ResPlan deliberately ships geometry only, not third-party listing images. This adapter therefore
creates original Manzl raster renderings from the licensed vector facts/annotations. It contributes
realistic residential topology, wall/door/window/stair geometry and metric proportions without
copying any source listing artwork.

Corner/orientation heads are masked for these samples because ResPlan's released wall layer is a
polygonal face representation rather than an authoritative wall-centerline graph. The two procedural
Manzl curricula keep supervising those heads with exact centerline ground truth.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import pickle
import random
import zipfile
from typing import Iterable

import cv2
import numpy as np
from shapely.geometry import (
    GeometryCollection,
    LineString,
    MultiLineString,
    MultiPoint,
    MultiPolygon,
    Point,
    Polygon,
)

BACKGROUND = 0
WALL = 1
DOOR = 2
WINDOW = 3
STAIR = 4
COLUMN = 5
ROOM_BOUNDARY = 6
COURTYARD = 7
SHAFT = 8
CLASS_COUNT = 9

ROOM_KEYS = ("living", "bedroom", "bathroom", "kitchen", "storage")


def iter_geometry(value) -> Iterable:
    if value is None:
        return
    if isinstance(value, (Polygon, LineString, Point)):
        if not value.is_empty:
            yield value
        return
    if isinstance(value, (MultiPolygon, MultiLineString, MultiPoint, GeometryCollection)):
        for geometry in value.geoms:
            yield from iter_geometry(geometry)
        return
    if isinstance(value, (list, tuple)):
        for item in value:
            yield from iter_geometry(item)


def all_bounds(plan: dict) -> tuple[float, float, float, float] | None:
    preferred = list(iter_geometry(plan.get("inner")))
    geometries = preferred or list(iter_geometry(plan.get("wall")))
    if not geometries:
        return None
    min_x = min(geometry.bounds[0] for geometry in geometries)
    min_y = min(geometry.bounds[1] for geometry in geometries)
    max_x = max(geometry.bounds[2] for geometry in geometries)
    max_y = max(geometry.bounds[3] for geometry in geometries)
    if max_x - min_x <= 1e-6 or max_y - min_y <= 1e-6:
        return None
    return min_x, min_y, max_x, max_y


class Transform:
    def __init__(self, bounds, size: int, padding: int) -> None:
        min_x, min_y, max_x, max_y = bounds
        available = max(1, size - padding * 2)
        self.scale = min(available / (max_x - min_x), available / (max_y - min_y))
        draw_w = (max_x - min_x) * self.scale
        draw_h = (max_y - min_y) * self.scale
        self.offset_x = (size - draw_w) * 0.5 - min_x * self.scale
        # Keep source orientation stable; random 90° augmentation happens in train_student.py.
        self.offset_y = (size - draw_h) * 0.5 - min_y * self.scale
        self.size = size

    def point(self, x: float, y: float) -> tuple[int, int]:
        return (
            int(round(x * self.scale + self.offset_x)),
            int(round(y * self.scale + self.offset_y)),
        )


def polygon_points(polygon: Polygon, transform: Transform) -> np.ndarray:
    return np.asarray([transform.point(x, y) for x, y in polygon.exterior.coords], dtype=np.int32)


def geometry_mask(value, transform: Transform, size: int, line_width: int = 2) -> np.ndarray:
    mask = np.zeros((size, size), dtype=np.uint8)
    for geometry in iter_geometry(value):
        if isinstance(geometry, Polygon):
            exterior = polygon_points(geometry, transform)
            if len(exterior) >= 3:
                cv2.fillPoly(mask, [exterior], 255, cv2.LINE_AA)
            for interior in geometry.interiors:
                hole = np.asarray([transform.point(x, y) for x, y in interior.coords], dtype=np.int32)
                if len(hole) >= 3:
                    cv2.fillPoly(mask, [hole], 0, cv2.LINE_AA)
        elif isinstance(geometry, LineString):
            points = np.asarray([transform.point(x, y) for x, y in geometry.coords], dtype=np.int32)
            if len(points) >= 2:
                cv2.polylines(mask, [points], False, 255, max(1, line_width), cv2.LINE_AA)
        elif isinstance(geometry, Point):
            cv2.circle(mask, transform.point(geometry.x, geometry.y), max(1, line_width), 255, -1, cv2.LINE_AA)
    return mask > 64


def draw_room_text(image: np.ndarray, plan: dict, transform: Transform, rng: random.Random, ink) -> None:
    if rng.random() > 0.78:
        return
    abbreviations = {
        "living": "LIV",
        "bedroom": "BED",
        "bathroom": "BATH",
        "kitchen": "KIT",
        "storage": "ST",
    }
    for key in ROOM_KEYS:
        for geometry in iter_geometry(plan.get(key)):
            if not isinstance(geometry, Polygon) or geometry.area <= 0:
                continue
            point = geometry.representative_point()
            x, y = transform.point(point.x, point.y)
            text = abbreviations[key]
            cv2.putText(
                image,
                text,
                (x - 8, y + 3),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.28,
                ink,
                1,
                cv2.LINE_AA,
            )


def draw_drafting_noise(image: np.ndarray, rng: random.Random, ink) -> None:
    size = image.shape[0]
    if rng.random() < 0.82:
        y = rng.randint(7, max(8, size // 15))
        cv2.line(image, (size // 6, y), (size * 5 // 6, y), ink, 1, cv2.LINE_AA)
        for x in (size // 6, size * 5 // 6):
            cv2.line(image, (x, y - 3), (x, y + 3), ink, 1, cv2.LINE_AA)
        cv2.putText(
            image,
            f"{rng.uniform(4.0, 18.0):.2f}",
            (size // 2 - 14, max(9, y - 2)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.25,
            ink,
            1,
            cv2.LINE_AA,
        )


def render(plan: dict, size: int, seed: int) -> dict[str, np.ndarray] | None:
    bounds = all_bounds(plan)
    if bounds is None:
        return None
    rng = random.Random(seed)
    transform = Transform(bounds, size=size, padding=max(18, size // 18))

    background_value = rng.randint(242, 255)
    image = np.full((size, size, 3), background_value, dtype=np.uint8)
    semantic = np.zeros((size, size), dtype=np.int64)

    if rng.random() < 0.23:
        wall_ink = np.asarray([158, 82, 37], dtype=np.uint8)  # BGR blueprint-like structural ink.
        symbol_ink = np.asarray([125, 70, 35], dtype=np.uint8)
    else:
        value = rng.randint(18, 62)
        wall_ink = np.asarray([value, value, value], dtype=np.uint8)
        symbol_ink = np.asarray([max(0, value - 4)] * 3, dtype=np.uint8)

    wall = geometry_mask(plan.get("wall"), transform, size, line_width=max(2, size // 170))
    if int(wall.sum()) < max(80, size * size // 1200):
        return None
    image[wall] = wall_ink
    semantic[wall] = WALL

    # Openings and stairs are rendered after walls. Their released vector geometry is the authority
    # for the semantic mask; they may overlap the wall face in source geometry and intentionally win.
    for key, class_id in (("door", DOOR), ("front_door", DOOR), ("window", WINDOW), ("stair", STAIR)):
        pixels = geometry_mask(plan.get(key), transform, size, line_width=max(2, size // 190))
        if not pixels.any():
            continue
        semantic[pixels] = class_id
        image[pixels] = symbol_ink

    draw_room_text(image, plan, transform, rng, tuple(int(v) for v in symbol_ink))
    draw_drafting_noise(image, rng, (118, 118, 118))

    # Image-only scanner/screenshot corruption. Geometry supervision remains exact.
    if rng.random() < 0.48:
        sigma = rng.uniform(0.25, 0.85)
        image = cv2.GaussianBlur(image, (3, 3), sigmaX=sigma)
    np_rng = np.random.default_rng(seed ^ 0x5A17)
    if rng.random() < 0.68:
        noise = np_rng.normal(0.0, rng.uniform(0.8, 5.0), image.shape).astype(np.float32)
        image = np.clip(image.astype(np.float32) + noise, 0, 255).astype(np.uint8)

    wall_mask = (semantic == WALL).astype(np.float32)
    supervision = np.ones((size, size), dtype=np.float32)
    semantic_confidence = np.ones((size, size), dtype=np.float32)

    # Wall-face polygons do not encode a unique centre-line orientation/corner target. Mask those two
    # heads instead of fabricating labels; exact procedural samples supervise them separately.
    corners = np.zeros((size, size), dtype=np.float32)
    corner_mask = np.zeros((size, size), dtype=np.float32)
    orientation = np.zeros((2, size, size), dtype=np.float32)
    orientation_mask = np.zeros((size, size), dtype=np.float32)

    return {
        "image": image,
        "semantic": semantic,
        "semantic_confidence": semantic_confidence,
        "supervision_mask": supervision,
        "corners": corners,
        "corner_mask": corner_mask,
        "orientation": orientation,
        "wall_mask": wall_mask,
        "orientation_mask": orientation_mask,
    }


def load_dataset(archive: pathlib.Path):
    with zipfile.ZipFile(archive) as bundle:
        pickle_names = [name for name in bundle.namelist() if name.lower().endswith((".pkl", ".pickle"))]
        if len(pickle_names) != 1:
            raise RuntimeError(f"Expected exactly one ResPlan pickle, found {pickle_names}")
        with bundle.open(pickle_names[0]) as stream:
            return pickle.load(stream)


def select_plans(data, ids: set[str], count: int, seed: int):
    eligible = [plan for plan in data if str(plan.get("id")) in ids]
    rng = random.Random(seed)
    rng.shuffle(eligible)
    if len(eligible) < count:
        raise RuntimeError(f"Requested {count} plans but split only contains {len(eligible)} available records")
    return eligible


def write_split(plans, output: pathlib.Path, count: int, size: int, seed: int) -> int:
    output.mkdir(parents=True, exist_ok=True)
    written = 0
    for index, plan in enumerate(plans):
        payload = render(plan, size=size, seed=seed + index * 7919)
        if payload is None:
            continue
        plan_id = str(plan.get("id", index)).replace("/", "_")
        np.savez_compressed(output / f"resplan_{plan_id}_{written:05d}.npz", **payload)
        written += 1
        if written >= count:
            break
    if written != count:
        raise RuntimeError(f"Only rendered {written}/{count} requested ResPlan samples")
    return written


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=pathlib.Path, required=True)
    parser.add_argument("--split-json", type=pathlib.Path, required=True)
    parser.add_argument("--train-output", type=pathlib.Path, required=True)
    parser.add_argument("--val-output", type=pathlib.Path, required=True)
    parser.add_argument("--train-count", type=int, default=80)
    parser.add_argument("--val-count", type=int, default=16)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--seed", type=int, default=20260901)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.train_count <= 0 or args.val_count <= 0 or args.size < 128:
        raise SystemExit("invalid output counts/size")
    splits = json.loads(args.split_json.read_text(encoding="utf-8"))
    train_ids = {str(value) for value in splits["train"]}
    val_ids = {str(value) for value in splits["val"]}
    data = load_dataset(args.archive)
    train_candidates = select_plans(data, train_ids, max(args.train_count * 2, args.train_count + 24), args.seed)
    val_candidates = select_plans(data, val_ids, max(args.val_count * 2, args.val_count + 16), args.seed + 1)
    train_written = write_split(train_candidates, args.train_output, args.train_count, args.size, args.seed + 100)
    val_written = write_split(val_candidates, args.val_output, args.val_count, args.size, args.seed + 200)
    print(f"rendered ResPlan: train={train_written}, val={val_written}; CC BY 4.0 geometry only")


if __name__ == "__main__":
    main()
