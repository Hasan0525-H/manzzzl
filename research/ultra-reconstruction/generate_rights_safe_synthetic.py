#!/usr/bin/env python3
"""Boundary-safe public entry point for the rights-safe procedural corpus generator.

The implementation is kept in ``generate_rights_safe_synthetic_impl``. This facade only adds raster
intersection guards around primitives whose geometry may legitimately move fully off-canvas after the
small whole-plan rotation augmentation. Off-canvas geometry is ignored rather than passed to NumPy with
an empty/reversed raster window. Partially visible geometry still delegates to the original exact raster
implementation unchanged.
"""

from __future__ import annotations

import math

import generate_rights_safe_synthetic_impl as _impl

SEMANTIC_CLASSES = _impl.SEMANTIC_CLASSES
CLASS = _impl.CLASS
Point = _impl.Point
Segment = _impl.Segment
Opening = _impl.Opening
Rect = _impl.Rect


def _centered_region_intersects_raster(size: int, x: float, y: float, radius: float) -> bool:
    edge = float(size - 1)
    return not (
        x + radius < 0.0
        or x - radius > edge
        or y + radius < 0.0
        or y - radius > edge
    )


def _segment_region_intersects_raster(size: int, segment: Segment, radius: float) -> bool:
    edge = float(size - 1)
    return not (
        max(segment.a.x, segment.b.x) + radius < 0.0
        or min(segment.a.x, segment.b.x) - radius > edge
        or max(segment.a.y, segment.b.y) + radius < 0.0
        or min(segment.a.y, segment.b.y) - radius > edge
    )


class Raster(_impl.Raster):
    """Original rasterizer with fail-closed clipping for fully off-canvas primitives."""

    def erase_wall_segment(self, segment: Segment, extra: float = 1.0) -> None:
        radius = max(1.0, segment.thickness * 0.5 + extra) + 2.0
        if not _segment_region_intersects_raster(self.size, segment, radius):
            return
        super().erase_wall_segment(segment, extra)

    def draw_column(self, center: Point, width: float, depth: float, angle: float) -> None:
        radius = math.hypot(width * 0.5, depth * 0.5) + 2.0
        if not _centered_region_intersects_raster(self.size, center.x, center.y, radius):
            return
        super().draw_column(center, width, depth, angle)

    def draw_corner(self, point: Point, sigma: float = 2.2) -> None:
        if sigma <= 0.0 or not math.isfinite(sigma):
            raise ValueError("corner sigma must be finite and positive")
        radius = float(int(math.ceil(sigma * 3.0)))
        cx = float(int(round(point.x)))
        cy = float(int(round(point.y)))
        if not _centered_region_intersects_raster(self.size, cx, cy, radius):
            return
        super().draw_corner(point, sigma)


# ``generate_sample`` resolves Raster through the implementation module at call time. Rebind it once so
# every caller, including the CLI main function and existing imports, gets the guarded rasterizer.
_impl.Raster = Raster

rotate = _impl.rotate
segment_with_gap = _impl.segment_with_gap
recursive_rooms = _impl.recursive_rooms
opening_for_wall = _impl.opening_for_wall
draw_door_symbol = _impl.draw_door_symbol
draw_window_symbol = _impl.draw_window_symbol
generate_sample = _impl.generate_sample


def main() -> None:
    _impl.main()


if __name__ == "__main__":
    main()
