#!/usr/bin/env python3
"""Generate a fully rights-safe Manzl floor-plan corpus with exact dense ground truth.

The generator creates original procedural residential layouts; it does not copy or trace any external
floor plan. Samples match ``train_student.py`` directly and include semantic classes, wall tangent
orientation, corner heatmaps and supervision masks. The intent is to give the distilled student a
large exact-geometry base before teacher-consensus samples are mixed in.

This synthetic corpus is *not* treated as proof of real-plan accuracy. Real/screenshot/CAD regression
sets remain mandatory before Ultra is considered release-ready.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import random
from dataclasses import dataclass

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


@dataclass(frozen=True)
class Point:
    x: float
    y: float


@dataclass(frozen=True)
class Segment:
    a: Point
    b: Point
    thickness: float


@dataclass(frozen=True)
class Opening:
    wall_index: int
    center_t: float
    span: float
    kind: str


@dataclass(frozen=True)
class Rect:
    x0: float
    y0: float
    x1: float
    y1: float

    @property
    def width(self) -> float:
        return self.x1 - self.x0

    @property
    def height(self) -> float:
        return self.y1 - self.y0


class Raster:
    def __init__(self, size: int, rng: random.Random) -> None:
        self.size = size
        self.rng = rng
        self.semantic = np.zeros((size, size), dtype=np.int64)
        self.wall_mask = np.zeros((size, size), dtype=np.float32)
        self.orientation = np.zeros((2, size, size), dtype=np.float32)
        self.corners = np.zeros((size, size), dtype=np.float32)
        self.image = np.full((size, size, 3), 245.0, dtype=np.float32)

        style = rng.choice(["black", "blue", "gray"])
        if style == "blue":
            self.ink = np.array([35.0, 82.0, 160.0], dtype=np.float32)
            self.background = np.array([246.0, 248.0, 250.0], dtype=np.float32)
        elif style == "gray":
            self.ink = np.array([62.0, 62.0, 62.0], dtype=np.float32)
            self.background = np.array([250.0, 248.0, 244.0], dtype=np.float32)
        else:
            self.ink = np.array([24.0, 24.0, 24.0], dtype=np.float32)
            self.background = np.array([250.0, 250.0, 250.0], dtype=np.float32)
        self.image[:] = self.background

    def draw_segment(
        self,
        segment: Segment,
        class_name: str,
        width_scale: float = 1.0,
        orientation: bool = False,
        overwrite: bool = True,
    ) -> None:
        radius = max(0.75, segment.thickness * width_scale * 0.5)
        min_x = max(0, int(math.floor(min(segment.a.x, segment.b.x) - radius - 2)))
        max_x = min(self.size - 1, int(math.ceil(max(segment.a.x, segment.b.x) + radius + 2)))
        min_y = max(0, int(math.floor(min(segment.a.y, segment.b.y) - radius - 2)))
        max_y = min(self.size - 1, int(math.ceil(max(segment.a.y, segment.b.y) + radius + 2)))
        if min_x > max_x or min_y > max_y:
            return

        yy, xx = np.mgrid[min_y : max_y + 1, min_x : max_x + 1]
        ax, ay = segment.a.x, segment.a.y
        vx = segment.b.x - ax
        vy = segment.b.y - ay
        length_sq = vx * vx + vy * vy
        if length_sq <= 1e-6:
            return
        t = np.clip(((xx - ax) * vx + (yy - ay) * vy) / length_sq, 0.0, 1.0)
        qx = ax + t * vx
        qy = ay + t * vy
        mask = (xx - qx) ** 2 + (yy - qy) ** 2 <= radius * radius
        ys = slice(min_y, max_y + 1)
        xs = slice(min_x, max_x + 1)

        if overwrite:
            target = self.semantic[ys, xs]
            target[mask] = CLASS[class_name]
        self._ink_region(ys, xs, mask, strength=1.0)

        if class_name == "wall_face":
            wall = self.wall_mask[ys, xs]
            wall[mask] = 1.0
            if orientation:
                length = math.sqrt(length_sq)
                ux = vx / length
                uy = vy / length
                ox = self.orientation[0, ys, xs]
                oy = self.orientation[1, ys, xs]
                ox[mask] = ux
                oy[mask] = uy

    def erase_wall_segment(self, segment: Segment, extra: float = 1.0) -> None:
        radius = max(1.0, segment.thickness * 0.5 + extra)
        min_x = max(0, int(math.floor(min(segment.a.x, segment.b.x) - radius - 2)))
        max_x = min(self.size - 1, int(math.ceil(max(segment.a.x, segment.b.x) + radius + 2)))
        min_y = max(0, int(math.floor(min(segment.a.y, segment.b.y) - radius - 2)))
        max_y = min(self.size - 1, int(math.ceil(max(segment.a.y, segment.b.y) + radius + 2)))
        yy, xx = np.mgrid[min_y : max_y + 1, min_x : max_x + 1]
        vx = segment.b.x - segment.a.x
        vy = segment.b.y - segment.a.y
        length_sq = vx * vx + vy * vy
        if length_sq <= 1e-6:
            return
        t = np.clip(((xx - segment.a.x) * vx + (yy - segment.a.y) * vy) / length_sq, 0.0, 1.0)
        qx = segment.a.x + t * vx
        qy = segment.a.y + t * vy
        mask = (xx - qx) ** 2 + (yy - qy) ** 2 <= radius * radius
        ys = slice(min_y, max_y + 1)
        xs = slice(min_x, max_x + 1)
        semantic = self.semantic[ys, xs]
        semantic[mask] = CLASS["background"]
        wall = self.wall_mask[ys, xs]
        wall[mask] = 0.0
        ox = self.orientation[0, ys, xs]
        oy = self.orientation[1, ys, xs]
        ox[mask] = 0.0
        oy[mask] = 0.0
        image = self.image[ys, xs]
        image[mask] = self.background

    def draw_rect_fill(self, rect: Rect, class_name: str, image_alpha: float = 0.0) -> None:
        x0 = max(0, int(round(rect.x0)))
        x1 = min(self.size, int(round(rect.x1)))
        y0 = max(0, int(round(rect.y0)))
        y1 = min(self.size, int(round(rect.y1)))
        if x1 <= x0 or y1 <= y0:
            return
        self.semantic[y0:y1, x0:x1] = CLASS[class_name]
        if image_alpha > 0:
            tint = np.array([225.0, 232.0, 229.0], dtype=np.float32)
            self.image[y0:y1, x0:x1] = (
                self.image[y0:y1, x0:x1] * (1.0 - image_alpha) + tint * image_alpha
            )

    def draw_column(self, center: Point, width: float, depth: float, angle: float) -> None:
        ux, uy = math.cos(angle), math.sin(angle)
        nx, ny = -uy, ux
        half_w = width * 0.5
        half_d = depth * 0.5
        radius = math.hypot(half_w, half_d) + 2.0
        min_x = max(0, int(center.x - radius))
        max_x = min(self.size - 1, int(center.x + radius))
        min_y = max(0, int(center.y - radius))
        max_y = min(self.size - 1, int(center.y + radius))
        yy, xx = np.mgrid[min_y : max_y + 1, min_x : max_x + 1]
        dx = xx - center.x
        dy = yy - center.y
        along = dx * ux + dy * uy
        normal = dx * nx + dy * ny
        mask = (np.abs(along) <= half_w) & (np.abs(normal) <= half_d)
        ys = slice(min_y, max_y + 1)
        xs = slice(min_x, max_x + 1)
        self.semantic[ys, xs][mask] = CLASS["column"]
        self._ink_region(ys, xs, mask, strength=0.92)

    def draw_corner(self, point: Point, sigma: float = 2.2) -> None:
        radius = int(math.ceil(sigma * 3.0))
        cx, cy = int(round(point.x)), int(round(point.y))
        x0 = max(0, cx - radius)
        x1 = min(self.size - 1, cx + radius)
        y0 = max(0, cy - radius)
        y1 = min(self.size - 1, cy + radius)
        yy, xx = np.mgrid[y0 : y1 + 1, x0 : x1 + 1]
        heat = np.exp(-((xx - point.x) ** 2 + (yy - point.y) ** 2) / (2.0 * sigma * sigma))
        self.corners[y0 : y1 + 1, x0 : x1 + 1] = np.maximum(
            self.corners[y0 : y1 + 1, x0 : x1 + 1],
            heat.astype(np.float32),
        )

    def finalize(self) -> np.ndarray:
        # Light synthetic scan artefacts are image-only; labels remain exact.
        if self.rng.random() < 0.75:
            yy, xx = np.mgrid[0 : self.size, 0 : self.size]
            gradient = (
                (xx / max(1, self.size - 1) - 0.5) * self.rng.uniform(-10.0, 10.0)
                + (yy / max(1, self.size - 1) - 0.5) * self.rng.uniform(-10.0, 10.0)
            )
            self.image += gradient[..., None]
        noise_sigma = self.rng.uniform(0.0, 5.5)
        if noise_sigma > 0:
            np_rng = np.random.default_rng(self.rng.randrange(1 << 32))
            self.image += np_rng.normal(0.0, noise_sigma, self.image.shape).astype(np.float32)
        return np.clip(self.image, 0, 255).astype(np.uint8)

    def _ink_region(self, ys: slice, xs: slice, mask: np.ndarray, strength: float) -> None:
        image = self.image[ys, xs]
        target = self.ink * strength + self.background * (1.0 - strength)
        image[mask] = target


def rotate(point: Point, center: Point, angle: float) -> Point:
    if abs(angle) <= 1e-9:
        return point
    dx = point.x - center.x
    dy = point.y - center.y
    c = math.cos(angle)
    s = math.sin(angle)
    return Point(center.x + dx * c - dy * s, center.y + dx * s + dy * c)


def segment_with_gap(segment: Segment, center_t: float, span: float) -> tuple[Segment, Segment, Segment]:
    vx = segment.b.x - segment.a.x
    vy = segment.b.y - segment.a.y
    length = math.hypot(vx, vy)
    if length <= 1e-6:
        return segment, segment, segment
    half_t = span * 0.5 / length
    t0 = max(0.0, center_t - half_t)
    t1 = min(1.0, center_t + half_t)

    def p(t: float) -> Point:
        return Point(segment.a.x + vx * t, segment.a.y + vy * t)

    return (
        Segment(segment.a, p(t0), segment.thickness),
        Segment(p(t0), p(t1), segment.thickness),
        Segment(p(t1), segment.b, segment.thickness),
    )


def recursive_rooms(rect: Rect, rng: random.Random, depth: int) -> tuple[list[Rect], list[Segment]]:
    if depth <= 0 or min(rect.width, rect.height) < 105 or rng.random() < 0.25:
        return [rect], []
    split_vertical = rect.width >= rect.height if rng.random() < 0.68 else rect.width < rect.height
    ratio = rng.uniform(0.36, 0.64)
    wall_thickness = rng.uniform(5.0, 11.0)
    rooms: list[Rect] = []
    walls: list[Segment] = []
    if split_vertical:
        x = rect.x0 + rect.width * ratio
        left = Rect(rect.x0, rect.y0, x, rect.y1)
        right = Rect(x, rect.y0, rect.x1, rect.y1)
        wall = Segment(Point(x, rect.y0), Point(x, rect.y1), wall_thickness)
        for child in (left, right):
            child_rooms, child_walls = recursive_rooms(child, rng, depth - 1)
            rooms += child_rooms
            walls += child_walls
        walls.append(wall)
    else:
        y = rect.y0 + rect.height * ratio
        top = Rect(rect.x0, rect.y0, rect.x1, y)
        bottom = Rect(rect.x0, y, rect.x1, rect.y1)
        wall = Segment(Point(rect.x0, y), Point(rect.x1, y), wall_thickness)
        for child in (top, bottom):
            child_rooms, child_walls = recursive_rooms(child, rng, depth - 1)
            rooms += child_rooms
            walls += child_walls
        walls.append(wall)
    return rooms, walls


def opening_for_wall(index: int, wall: Segment, rng: random.Random, kind: str) -> Opening | None:
    length = math.hypot(wall.b.x - wall.a.x, wall.b.y - wall.a.y)
    minimum = 22.0 if kind == "door" else 18.0
    if length < minimum * 2.6:
        return None
    span = rng.uniform(minimum, min(length * 0.28, 70.0 if kind == "door" else 95.0))
    margin_t = min(0.28, span * 0.75 / length)
    center_t = rng.uniform(max(0.20, margin_t), min(0.80, 1.0 - margin_t))
    return Opening(index, center_t, span, kind)


def draw_door_symbol(raster: Raster, gap: Segment, wall: Segment) -> None:
    # One leaf plus a sparse swing arc. Geometry labels remain door even if the drawing style varies.
    vx = wall.b.x - wall.a.x
    vy = wall.b.y - wall.a.y
    length = math.hypot(vx, vy)
    if length <= 1e-6:
        return
    ux, uy = vx / length, vy / length
    nx, ny = -uy, ux
    gap_length = math.hypot(gap.b.x - gap.a.x, gap.b.y - gap.a.y)
    hinge = gap.a
    leaf_end = Point(hinge.x + nx * gap_length * 0.82, hinge.y + ny * gap_length * 0.82)
    raster.draw_segment(Segment(hinge, leaf_end, 1.5), "door", width_scale=1.0)
    center = hinge
    radius = gap_length * 0.82
    for i in range(10):
        t0 = i / 10.0
        t1 = (i + 0.65) / 10.0
        a0 = math.atan2(uy, ux) + math.pi * 0.5 * t0
        a1 = math.atan2(uy, ux) + math.pi * 0.5 * t1
        p0 = Point(center.x + math.cos(a0) * radius, center.y + math.sin(a0) * radius)
        p1 = Point(center.x + math.cos(a1) * radius, center.y + math.sin(a1) * radius)
        raster.draw_segment(Segment(p0, p1, 1.0), "door", width_scale=1.0)


def draw_window_symbol(raster: Raster, gap: Segment, wall: Segment) -> None:
    vx = wall.b.x - wall.a.x
    vy = wall.b.y - wall.a.y
    length = math.hypot(vx, vy)
    if length <= 1e-6:
        return
    ux, uy = vx / length, vy / length
    nx, ny = -uy, ux
    for offset in (-2.2, 2.2):
        a = Point(gap.a.x + nx * offset, gap.a.y + ny * offset)
        b = Point(gap.b.x + nx * offset, gap.b.y + ny * offset)
        raster.draw_segment(Segment(a, b, 1.4), "window")


def generate_sample(size: int, rng: random.Random) -> dict[str, np.ndarray]:
    raster = Raster(size, rng)
    margin = rng.uniform(size * 0.09, size * 0.16)
    base_rect = Rect(margin, margin, size - margin, size - margin)
    rooms, partitions = recursive_rooms(base_rect, rng, depth=rng.choice([2, 3]))

    outer_thickness = rng.uniform(8.0, 15.0)
    walls = [
        Segment(Point(base_rect.x0, base_rect.y0), Point(base_rect.x1, base_rect.y0), outer_thickness),
        Segment(Point(base_rect.x1, base_rect.y0), Point(base_rect.x1, base_rect.y1), outer_thickness),
        Segment(Point(base_rect.x1, base_rect.y1), Point(base_rect.x0, base_rect.y1), outer_thickness),
        Segment(Point(base_rect.x0, base_rect.y1), Point(base_rect.x0, base_rect.y0), outer_thickness),
    ] + partitions

    # Rotate the entire measured structure by a small arbitrary angle in a subset of samples. This
    # directly supervises non-axis wall orientation without resampling the label raster afterwards.
    rotation = 0.0
    if rng.random() < 0.34:
        rotation = math.radians(rng.uniform(-24.0, 24.0))
    center = Point(size * 0.5, size * 0.5)
    if rotation:
        walls = [
            Segment(rotate(w.a, center, rotation), rotate(w.b, center, rotation), w.thickness)
            for w in walls
        ]

    openings: list[Opening] = []
    # Internal walls receive doors. Exterior walls receive a mix of windows and occasional doors.
    for index, wall in enumerate(walls):
        probability = 0.88 if index >= 4 else 0.72
        if rng.random() > probability:
            continue
        kind = "door" if index >= 4 or rng.random() < 0.28 else "window"
        opening = opening_for_wall(index, wall, rng, kind)
        if opening is not None:
            openings.append(opening)

    opening_by_wall = {opening.wall_index: opening for opening in openings}
    for index, wall in enumerate(walls):
        opening = opening_by_wall.get(index)
        if opening is None:
            raster.draw_segment(wall, "wall_face", orientation=True)
        else:
            before, gap, after = segment_with_gap(wall, opening.center_t, opening.span)
            raster.draw_segment(before, "wall_face", orientation=True)
            raster.draw_segment(after, "wall_face", orientation=True)
            if opening.kind == "door":
                draw_door_symbol(raster, gap, wall)
            else:
                draw_window_symbol(raster, gap, wall)
        raster.draw_corner(wall.a)
        raster.draw_corner(wall.b)

    # Columns are independent compact structural primitives rather than fat wall fragments.
    for _ in range(rng.randint(0, 4)):
        column_center = Point(
            rng.uniform(base_rect.x0 + 30, base_rect.x1 - 30),
            rng.uniform(base_rect.y0 + 30, base_rect.y1 - 30),
        )
        if rotation:
            column_center = rotate(column_center, center, rotation)
        raster.draw_column(
            column_center,
            width=rng.uniform(9.0, 18.0),
            depth=rng.uniform(9.0, 18.0),
            angle=rotation + rng.choice([0.0, math.pi * 0.5]),
        )

    # One stair band in many samples. Treads can be arbitrary-angle and deliberately include double
    # raster strokes so the mobile OpenCV expert sees the same failure modes as real scans.
    if rng.random() < 0.62:
        stair_center = Point(
            rng.uniform(base_rect.x0 + 70, base_rect.x1 - 70),
            rng.uniform(base_rect.y0 + 70, base_rect.y1 - 70),
        )
        stair_angle = rotation + rng.choice([0.0, math.pi * 0.5]) + rng.uniform(-0.06, 0.06)
        tread_length = rng.uniform(34.0, 58.0)
        spacing = rng.uniform(6.0, 11.0)
        count = rng.randint(8, 17)
        ux, uy = math.cos(stair_angle), math.sin(stair_angle)
        nx, ny = -uy, ux
        for index in range(count):
            offset = (index - (count - 1) * 0.5) * spacing
            cx = stair_center.x + nx * offset
            cy = stair_center.y + ny * offset
            a = Point(cx - ux * tread_length * 0.5, cy - uy * tread_length * 0.5)
            b = Point(cx + ux * tread_length * 0.5, cy + uy * tread_length * 0.5)
            raster.draw_segment(Segment(a, b, 1.5), "stair")

    # Closed semantic voids/courtyards are intentionally sparse. Their image appearance is mostly
    # empty; the model must learn them from teacher/room context rather than a magic fill colour.
    void_choice = rng.random()
    if rooms and void_choice < 0.18:
        room = rng.choice(rooms)
        inset = min(room.width, room.height) * 0.20
        if inset > 5:
            label = "shaft" if rng.random() < 0.52 else "courtyard"
            rect = Rect(room.x0 + inset, room.y0 + inset, room.x1 - inset, room.y1 - inset)
            if rotation == 0.0:  # area labels are exact only without polygon resampling in this generator
                raster.draw_rect_fill(rect, label, image_alpha=0.03 if label == "courtyard" else 0.0)

    image = raster.finalize()
    supervision = np.ones((size, size), dtype=np.float32)
    semantic_confidence = np.ones((size, size), dtype=np.float32)
    orientation_mask = raster.wall_mask.copy()
    corner_mask = np.ones((size, size), dtype=np.float32)
    return {
        "image": image,
        "semantic": raster.semantic,
        "semantic_confidence": semantic_confidence,
        "supervision_mask": supervision,
        "corners": raster.corners,
        "corner_mask": corner_mask,
        "orientation": raster.orientation,
        "wall_mask": raster.wall_mask,
        "orientation_mask": orientation_mask,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--count", type=int, default=2000)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--seed", type=int, default=43926)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()

    if args.count <= 0:
        raise SystemExit("--count must be positive")
    if args.size < 192:
        raise SystemExit("--size must be at least 192")
    args.output.mkdir(parents=True, exist_ok=True)
    rng = random.Random(args.seed)

    created = 0
    for index in range(args.count):
        destination = args.output / f"synthetic-{index:06d}.npz"
        if destination.exists() and not args.overwrite:
            # Advance deterministic RNG so later missing samples still match the requested seed.
            _ = generate_sample(args.size, rng)
            continue
        sample = generate_sample(args.size, rng)
        np.savez_compressed(destination, **sample)
        created += 1
        if created % 100 == 0:
            print(f"created {created} samples")

    manifest = {
        "schema": 1,
        "generator": "Manzl rights-safe procedural floor-plan generator",
        "rights": "original procedural geometry; no external floor-plan imagery",
        "countRequested": args.count,
        "size": args.size,
        "seed": args.seed,
        "semanticClasses": SEMANTIC_CLASSES,
        "releaseBenchmark": False,
        "note": "Synthetic training data is not evidence of real-plan accuracy.",
    }
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
