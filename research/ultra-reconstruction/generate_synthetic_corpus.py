#!/usr/bin/env python3
"""Generate unlimited rights-safe floor-plan supervision for Manzl.

The generator is intentionally geometry-first: every raster is rendered from an exact vector scene,
so wall faces, openings, corners and wall orientation have perfect ground truth. It is not a substitute
for real-plan regression data; it is the free curriculum that teaches the mobile student invariants
before teacher-consensus and real-plan fine-tuning.

Output NPZ files are directly consumable by ``train_student.py``.
"""

from __future__ import annotations

import argparse
import math
import pathlib
from dataclasses import dataclass

import cv2
import numpy as np

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


@dataclass(frozen=True)
class Segment:
    a: tuple[float, float]
    b: tuple[float, float]


@dataclass(frozen=True)
class Opening:
    segment_index: int
    t: float
    width_px: float
    kind: int


@dataclass
class Sample:
    image: np.ndarray
    semantic: np.ndarray
    corners: np.ndarray
    orientation: np.ndarray
    wall_mask: np.ndarray

    def as_npz(self) -> dict[str, np.ndarray]:
        supervision = np.ones(self.semantic.shape, dtype=np.float32)
        confidence = np.ones(self.semantic.shape, dtype=np.float32)
        wall_mask = self.wall_mask.astype(np.float32)
        return {
            "image": self.image.astype(np.uint8),
            "semantic": self.semantic.astype(np.int64),
            "semantic_confidence": confidence,
            "supervision_mask": supervision,
            "corners": self.corners.astype(np.float32),
            "corner_mask": supervision.copy(),
            "orientation": self.orientation.astype(np.float32),
            "wall_mask": wall_mask,
            "orientation_mask": wall_mask.copy(),
        }


def _point(segment: Segment, t: float) -> tuple[float, float]:
    return (
        segment.a[0] + (segment.b[0] - segment.a[0]) * t,
        segment.a[1] + (segment.b[1] - segment.a[1]) * t,
    )


def _segment_length(segment: Segment) -> float:
    return math.hypot(segment.b[0] - segment.a[0], segment.b[1] - segment.a[1])


def _line_mask(size: int, segment: Segment, thickness: int) -> np.ndarray:
    mask = np.zeros((size, size), dtype=np.uint8)
    cv2.line(
        mask,
        tuple(np.round(segment.a).astype(int)),
        tuple(np.round(segment.b).astype(int)),
        255,
        thickness=thickness,
        lineType=cv2.LINE_AA,
    )
    return mask > 64


def _draw_wall(
    image: np.ndarray,
    semantic: np.ndarray,
    wall_mask: np.ndarray,
    orientation: np.ndarray,
    segment: Segment,
    thickness: int,
    color: tuple[int, int, int],
) -> None:
    pixels = _line_mask(image.shape[0], segment, thickness)
    image[pixels] = color
    semantic[pixels] = WALL
    wall_mask[pixels] = 1
    dx = segment.b[0] - segment.a[0]
    dy = segment.b[1] - segment.a[1]
    length = math.hypot(dx, dy)
    if length > 1e-6:
        orientation[0, pixels] = dx / length
        orientation[1, pixels] = dy / length


def _erase_opening(
    image: np.ndarray,
    semantic: np.ndarray,
    wall_mask: np.ndarray,
    orientation: np.ndarray,
    segment: Segment,
    center: tuple[float, float],
    width_px: float,
    wall_thickness: int,
    background: tuple[int, int, int],
) -> None:
    length = _segment_length(segment)
    if length <= 1e-6:
        return
    ux = (segment.b[0] - segment.a[0]) / length
    uy = (segment.b[1] - segment.a[1]) / length
    half = width_px * 0.5
    a = (center[0] - ux * half, center[1] - uy * half)
    b = (center[0] + ux * half, center[1] + uy * half)
    erase = _line_mask(image.shape[0], Segment(a, b), wall_thickness + 5)
    image[erase] = background
    semantic[erase] = BACKGROUND
    wall_mask[erase] = 0
    orientation[:, erase] = 0.0


def _draw_door_symbol(
    image: np.ndarray,
    semantic: np.ndarray,
    segment: Segment,
    center: tuple[float, float],
    width_px: float,
    ink: tuple[int, int, int],
) -> None:
    length = _segment_length(segment)
    ux = (segment.b[0] - segment.a[0]) / max(length, 1e-6)
    uy = (segment.b[1] - segment.a[1]) / max(length, 1e-6)
    nx, ny = -uy, ux
    hinge = (center[0] - ux * width_px * 0.5, center[1] - uy * width_px * 0.5)
    leaf_end = (hinge[0] + nx * width_px * 0.92, hinge[1] + ny * width_px * 0.92)
    symbol = np.zeros(semantic.shape, dtype=np.uint8)
    cv2.line(symbol, tuple(np.round(hinge).astype(int)), tuple(np.round(leaf_end).astype(int)), 255, 2, cv2.LINE_AA)
    radius = max(3, int(round(width_px * 0.9)))
    start_angle = math.degrees(math.atan2(uy, ux))
    end_angle = start_angle + 88.0
    cv2.ellipse(
        symbol,
        tuple(np.round(hinge).astype(int)),
        (radius, radius),
        0.0,
        start_angle,
        end_angle,
        255,
        1,
        cv2.LINE_AA,
    )
    pixels = symbol > 64
    image[pixels] = ink
    semantic[pixels] = DOOR


def _draw_window_symbol(
    image: np.ndarray,
    semantic: np.ndarray,
    segment: Segment,
    center: tuple[float, float],
    width_px: float,
    ink: tuple[int, int, int],
) -> None:
    length = _segment_length(segment)
    ux = (segment.b[0] - segment.a[0]) / max(length, 1e-6)
    uy = (segment.b[1] - segment.a[1]) / max(length, 1e-6)
    nx, ny = -uy, ux
    symbol = np.zeros(semantic.shape, dtype=np.uint8)
    for normal_offset in (-2.0, 2.0):
        half = width_px * 0.5
        a = (
            center[0] - ux * half + nx * normal_offset,
            center[1] - uy * half + ny * normal_offset,
        )
        b = (
            center[0] + ux * half + nx * normal_offset,
            center[1] + uy * half + ny * normal_offset,
        )
        cv2.line(symbol, tuple(np.round(a).astype(int)), tuple(np.round(b).astype(int)), 255, 2, cv2.LINE_AA)
    pixels = symbol > 64
    image[pixels] = ink
    semantic[pixels] = WINDOW


def _draw_rect_feature(
    image: np.ndarray,
    semantic: np.ndarray,
    center: tuple[int, int],
    half_size: tuple[int, int],
    class_id: int,
    ink: tuple[int, int, int],
    hatch: bool,
) -> None:
    x, y = center
    hw, hh = half_size
    x0, y0 = max(1, x - hw), max(1, y - hh)
    x1, y1 = min(image.shape[1] - 2, x + hw), min(image.shape[0] - 2, y + hh)
    if x1 <= x0 or y1 <= y0:
        return
    semantic[y0:y1 + 1, x0:x1 + 1] = class_id
    cv2.rectangle(image, (x0, y0), (x1, y1), ink, 2, cv2.LINE_AA)
    if hatch:
        for offset in range(-hh * 2, hw * 2 + hh * 2, 9):
            a = (max(x0, x + offset - hh), y1)
            b = (min(x1, x + offset + hh), y0)
            cv2.line(image, a, b, ink, 1, cv2.LINE_AA)


def _draw_stair(
    image: np.ndarray,
    semantic: np.ndarray,
    center: tuple[int, int],
    width: int,
    run: int,
    horizontal: bool,
    ink: tuple[int, int, int],
) -> None:
    cx, cy = center
    if horizontal:
        x0, x1 = cx - run // 2, cx + run // 2
        y0, y1 = cy - width // 2, cy + width // 2
    else:
        x0, x1 = cx - width // 2, cx + width // 2
        y0, y1 = cy - run // 2, cy + run // 2
    x0, x1 = max(2, x0), min(image.shape[1] - 3, x1)
    y0, y1 = max(2, y0), min(image.shape[0] - 3, y1)
    if x1 - x0 < 8 or y1 - y0 < 8:
        return
    semantic[y0:y1 + 1, x0:x1 + 1] = STAIR
    cv2.rectangle(image, (x0, y0), (x1, y1), ink, 2, cv2.LINE_AA)
    steps = max(5, min(18, run // 8))
    for index in range(1, steps):
        t = index / steps
        if horizontal:
            x = int(round(x0 + (x1 - x0) * t))
            cv2.line(image, (x, y0), (x, y1), ink, 1, cv2.LINE_AA)
        else:
            y = int(round(y0 + (y1 - y0) * t))
            cv2.line(image, (x0, y), (x1, y), ink, 1, cv2.LINE_AA)


def _outer_polygon(size: int, rng: np.random.Generator) -> list[tuple[float, float]]:
    margin = int(rng.integers(max(18, size // 14), max(24, size // 9)))
    left, top = margin, margin
    right, bottom = size - margin, size - margin
    if rng.random() < 0.38:
        cut = float(rng.integers(max(18, size // 12), max(24, size // 7)))
        return [
            (left, top),
            (right, top),
            (right, bottom),
            (left + cut, bottom),
            (left, bottom - cut),
        ]
    return [(left, top), (right, top), (right, bottom), (left, bottom)]


def _build_segments(size: int, polygon: list[tuple[float, float]], rng: np.random.Generator) -> list[Segment]:
    segments = [Segment(polygon[i], polygon[(i + 1) % len(polygon)]) for i in range(len(polygon))]
    xs = [p[0] for p in polygon]
    ys = [p[1] for p in polygon]
    min_x, max_x = min(xs), max(xs)
    min_y, max_y = min(ys), max(ys)

    vertical_splits: list[float] = []
    horizontal_splits: list[float] = []
    for _ in range(int(rng.integers(1, 4))):
        if rng.random() < 0.5:
            x = float(rng.uniform(min_x + size * 0.20, max_x - size * 0.20))
            vertical_splits.append(x)
            segments.append(Segment((x, min_y), (x, max_y)))
        else:
            y = float(rng.uniform(min_y + size * 0.20, max_y - size * 0.20))
            horizontal_splits.append(y)
            segments.append(Segment((min_x, y), (max_x, y)))

    # Free-angle evidence is intentionally present in a subset of samples. It terminates on the outer
    # shell and teaches the student that non-orthogonal walls are first-class geometry, not noise.
    if rng.random() < 0.32:
        y0 = float(rng.uniform(min_y + size * 0.18, max_y - size * 0.28))
        y1 = float(rng.uniform(min_y + size * 0.28, max_y - size * 0.18))
        segments.append(Segment((min_x, y0), (max_x, y1)))
    return segments


def _choose_openings(segments: list[Segment], size: int, rng: np.random.Generator) -> list[Opening]:
    openings: list[Opening] = []
    eligible = [index for index, segment in enumerate(segments) if _segment_length(segment) >= size * 0.28]
    rng.shuffle(eligible)
    for index in eligible[: max(2, min(6, len(eligible)))]:
        segment = segments[index]
        kind = DOOR if rng.random() < 0.60 else WINDOW
        width = float(rng.uniform(size * 0.045, size * (0.075 if kind == DOOR else 0.11)))
        openings.append(Opening(index, float(rng.uniform(0.25, 0.75)), width, kind))
    return openings


def _draw_distractors(image: np.ndarray, rng: np.random.Generator, ink: tuple[int, int, int]) -> None:
    size = image.shape[0]
    for _ in range(int(rng.integers(3, 9))):
        y = int(rng.integers(5, max(6, size // 10))) if rng.random() < 0.5 else int(rng.integers(size - size // 10, size - 5))
        x0 = int(rng.integers(4, size // 3))
        x1 = int(rng.integers(size * 2 // 3, size - 4))
        cv2.line(image, (x0, y), (x1, y), ink, 1, cv2.LINE_AA)
        if rng.random() < 0.7:
            text = f"{rng.integers(250, 950) / 100:.2f}"
            cv2.putText(image, text, (x0, max(10, y - 3)), cv2.FONT_HERSHEY_SIMPLEX, 0.28, ink, 1, cv2.LINE_AA)


def _degrade_image(image: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    result = image.astype(np.float32)
    if rng.random() < 0.55:
        sigma = float(rng.uniform(0.25, 0.95))
        k = 3 if sigma < 0.65 else 5
        result = cv2.GaussianBlur(result, (k, k), sigmaX=sigma)
    gain = float(rng.uniform(0.82, 1.12))
    bias = float(rng.uniform(-12.0, 12.0))
    result = result * gain + bias
    if rng.random() < 0.70:
        noise = rng.normal(0.0, rng.uniform(1.0, 7.0), result.shape).astype(np.float32)
        result += noise
    return np.clip(result, 0, 255).astype(np.uint8)


def generate_sample(size: int, rng: np.random.Generator, force_features: bool = False) -> Sample:
    if size < 128:
        raise ValueError("size must be >= 128")

    blueprint = rng.random() < 0.24
    if blueprint:
        background = (33, 57, 85)
        wall_ink = (225, 238, 247)
        symbol_ink = (246, 246, 235)
        distractor_ink = (144, 174, 194)
    else:
        background = (248, 248, 246)
        if rng.random() < 0.25:
            wall_ink = (155, 84, 34)  # blue-ish after BGR conversion; exercises colour plans.
        else:
            value = int(rng.integers(18, 70))
            wall_ink = (value, value, value)
        symbol_ink = tuple(max(0, channel - 8) for channel in wall_ink)
        distractor_ink = (118, 118, 118)

    image = np.empty((size, size, 3), dtype=np.uint8)
    image[:, :] = np.asarray(background, dtype=np.uint8)
    semantic = np.zeros((size, size), dtype=np.uint8)
    wall_mask = np.zeros((size, size), dtype=np.uint8)
    orientation = np.zeros((2, size, size), dtype=np.float32)
    corner_seed = np.zeros((size, size), dtype=np.float32)

    polygon = _outer_polygon(size, rng)
    segments = _build_segments(size, polygon, rng)
    thickness = int(rng.integers(max(4, size // 90), max(6, size // 55)))
    for segment in segments:
        _draw_wall(image, semantic, wall_mask, orientation, segment, thickness, wall_ink)
        for p in (segment.a, segment.b):
            cv2.circle(corner_seed, tuple(np.round(p).astype(int)), max(2, thickness // 2), 1.0, -1, cv2.LINE_AA)

    openings = _choose_openings(segments, size, rng)
    for opening in openings:
        segment = segments[opening.segment_index]
        center = _point(segment, opening.t)
        _erase_opening(
            image,
            semantic,
            wall_mask,
            orientation,
            segment,
            center,
            opening.width_px,
            thickness,
            background,
        )
        if opening.kind == DOOR:
            _draw_door_symbol(image, semantic, segment, center, opening.width_px, symbol_ink)
        else:
            _draw_window_symbol(image, semantic, segment, center, opening.width_px, symbol_ink)

    min_x = int(min(p[0] for p in polygon))
    max_x = int(max(p[0] for p in polygon))
    min_y = int(min(p[1] for p in polygon))
    max_y = int(max(p[1] for p in polygon))
    inner_w = max_x - min_x
    inner_h = max_y - min_y

    # Structural columns: compact filled primitives with independent semantic class.
    column_count = int(rng.integers(1 if force_features else 0, 4))
    for _ in range(column_count):
        cx = int(rng.integers(min_x + inner_w // 6, max_x - inner_w // 6))
        cy = int(rng.integers(min_y + inner_h // 6, max_y - inner_h // 6))
        half = int(rng.integers(max(3, size // 85), max(5, size // 55)))
        _draw_rect_feature(image, semantic, (cx, cy), (half, half), COLUMN, wall_ink, hatch=False)

    if force_features or rng.random() < 0.58:
        cx = int(rng.integers(min_x + inner_w // 3, max_x - inner_w // 3))
        cy = int(rng.integers(min_y + inner_h // 3, max_y - inner_h // 3))
        _draw_stair(
            image,
            semantic,
            (cx, cy),
            width=max(18, size // 11),
            run=max(40, size // 4),
            horizontal=bool(rng.integers(0, 2)),
            ink=symbol_ink,
        )

    if force_features or rng.random() < 0.34:
        cx = int(rng.integers(min_x + inner_w // 3, max_x - inner_w // 3))
        cy = int(rng.integers(min_y + inner_h // 3, max_y - inner_h // 3))
        _draw_rect_feature(
            image,
            semantic,
            (cx, cy),
            (max(13, size // 14), max(13, size // 14)),
            COURTYARD,
            symbol_ink,
            hatch=True,
        )

    if force_features or rng.random() < 0.28:
        cx = int(rng.integers(min_x + inner_w // 4, max_x - inner_w // 4))
        cy = int(rng.integers(min_y + inner_h // 4, max_y - inner_h // 4))
        _draw_rect_feature(
            image,
            semantic,
            (cx, cy),
            (max(8, size // 24), max(10, size // 21)),
            SHAFT,
            symbol_ink,
            hatch=True,
        )

    _draw_distractors(image, rng, distractor_ink)
    image = _degrade_image(image, rng)

    corners = cv2.GaussianBlur(corner_seed, (0, 0), sigmaX=max(1.1, thickness * 0.55))
    maximum = float(corners.max())
    if maximum > 1e-6:
        corners /= maximum

    # Labels must remain internally consistent even where a semantic symbol was drawn over wall ink.
    wall_mask = (semantic == WALL).astype(np.uint8)
    orientation[:, wall_mask == 0] = 0.0
    norms = np.sqrt(orientation[0] ** 2 + orientation[1] ** 2)
    valid = norms > 1e-6
    orientation[0, valid] /= norms[valid]
    orientation[1, valid] /= norms[valid]

    if semantic.min() < 0 or semantic.max() >= CLASS_COUNT:
        raise AssertionError("semantic class out of range")
    return Sample(image, semantic, corners, orientation, wall_mask)


def generate_corpus(output: pathlib.Path, count: int, size: int, seed: int) -> None:
    output.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    for index in range(count):
        sample = generate_sample(size=size, rng=rng)
        destination = output / f"synthetic_{index:06d}.npz"
        np.savez_compressed(destination, **sample.as_npz())
    print(f"generated {count} rights-safe samples in {output}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--count", type=int, default=1000)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--seed", type=int, default=260901)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.count <= 0:
        raise SystemExit("--count must be > 0")
    generate_corpus(args.output, args.count, args.size, args.seed)


if __name__ == "__main__":
    main()
