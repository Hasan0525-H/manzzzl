#!/usr/bin/env python3
"""Fail closed when a real-teacher consensus corpus lacks usable semantic coverage.

The consensus builder already records supervised pixels per semantic class. This checker turns those
measurements into an explicit training gate. Thresholds are intentionally CLI/config inputs rather
than hidden release claims: the real Saudi/Arabic benchmark must choose and version the acceptance
criteria. The checker never marks a model release-ready; it only says whether a teacher corpus meets
the requested supervision contract.
"""

from __future__ import annotations

import argparse
import json
import pathlib
from dataclasses import dataclass


SEMANTIC_CLASSES = (
    "background",
    "wall_face",
    "door",
    "window",
    "stair",
    "column",
    "room_boundary",
    "courtyard",
    "shaft",
)


@dataclass(frozen=True)
class ClassRequirement:
    name: str
    min_pixels: int
    min_share: float


def parse_requirement(value: str) -> ClassRequirement:
    parts = value.split(":")
    if len(parts) not in (2, 3):
        raise argparse.ArgumentTypeError("class requirement must be NAME:MIN_PIXELS[:MIN_SHARE]")
    name = parts[0].strip()
    if name not in SEMANTIC_CLASSES:
        raise argparse.ArgumentTypeError(f"unknown semantic class {name!r}")
    try:
        min_pixels = int(parts[1])
        min_share = float(parts[2]) if len(parts) == 3 else 0.0
    except ValueError as error:
        raise argparse.ArgumentTypeError(str(error)) from error
    if min_pixels < 0:
        raise argparse.ArgumentTypeError("MIN_PIXELS must be >= 0")
    if not 0.0 <= min_share <= 1.0:
        raise argparse.ArgumentTypeError("MIN_SHARE must be between 0 and 1")
    return ClassRequirement(name, min_pixels, min_share)


def load_manifest(path: pathlib.Path) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"consensus manifest does not exist: {path}")
    manifest = json.loads(path.read_text(encoding="utf-8"))
    required = ("samples", "supervisedPixels", "totalPixels", "supervisionCoverage", "supervisedPixelsByClass")
    missing = [key for key in required if key not in manifest]
    if missing:
        raise ValueError(f"consensus manifest is missing required fields: {missing}")
    counts = manifest["supervisedPixelsByClass"]
    if not isinstance(counts, dict):
        raise ValueError("supervisedPixelsByClass must be an object")
    for name in SEMANTIC_CLASSES:
        if name not in counts:
            raise ValueError(f"supervisedPixelsByClass is missing {name}")
        if int(counts[name]) < 0:
            raise ValueError(f"negative supervised pixel count for {name}")
    return manifest


def verify_coverage(
    manifest: dict,
    requirements: list[ClassRequirement],
    *,
    min_samples: int,
    min_supervision_coverage: float,
    max_background_share: float,
) -> dict:
    samples = int(manifest["samples"])
    supervised_pixels = int(manifest["supervisedPixels"])
    total_pixels = int(manifest["totalPixels"])
    coverage = float(manifest["supervisionCoverage"])
    counts = {name: int(manifest["supervisedPixelsByClass"][name]) for name in SEMANTIC_CLASSES}

    if samples <= 0 or supervised_pixels <= 0 or total_pixels <= 0:
        raise RuntimeError("teacher corpus has no measurable supervised evidence")
    measured_coverage = supervised_pixels / total_pixels
    if abs(measured_coverage - coverage) > 1e-6:
        raise RuntimeError(
            f"manifest supervisionCoverage is inconsistent: {coverage:.8f} != {measured_coverage:.8f}"
        )
    counted = sum(counts.values())
    if counted != supervised_pixels:
        raise RuntimeError(
            f"per-class supervised pixel counts do not sum to supervisedPixels: {counted} != {supervised_pixels}"
        )

    failures: list[str] = []
    if samples < min_samples:
        failures.append(f"samples {samples} < required {min_samples}")
    if coverage < min_supervision_coverage:
        failures.append(
            f"supervisionCoverage {coverage:.6f} < required {min_supervision_coverage:.6f}"
        )

    background_share = counts["background"] / supervised_pixels
    if background_share > max_background_share:
        failures.append(
            f"background share {background_share:.6f} > allowed {max_background_share:.6f}"
        )

    class_report = {}
    seen_names: set[str] = set()
    for requirement in requirements:
        if requirement.name in seen_names:
            raise ValueError(f"duplicate class requirement for {requirement.name}")
        seen_names.add(requirement.name)
        pixels = counts[requirement.name]
        share = pixels / supervised_pixels
        class_report[requirement.name] = {
            "pixels": pixels,
            "share": share,
            "minPixels": requirement.min_pixels,
            "minShare": requirement.min_share,
            "passed": pixels >= requirement.min_pixels and share >= requirement.min_share,
        }
        if pixels < requirement.min_pixels:
            failures.append(
                f"{requirement.name} supervised pixels {pixels} < required {requirement.min_pixels}"
            )
        if share < requirement.min_share:
            failures.append(
                f"{requirement.name} share {share:.8f} < required {requirement.min_share:.8f}"
            )

    report = {
        "schema": 1,
        "samples": samples,
        "supervisedPixels": supervised_pixels,
        "supervisionCoverage": coverage,
        "backgroundShare": background_share,
        "requirements": class_report,
        "passed": not failures,
        "failures": failures,
        "releaseReady": False,
        "note": "Coverage passing is necessary training evidence only; real-plan reconstruction gates remain mandatory.",
    }
    if failures:
        raise RuntimeError("teacher coverage gate failed: " + "; ".join(failures))
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=pathlib.Path, required=True)
    parser.add_argument("--require-class", action="append", type=parse_requirement, default=[])
    parser.add_argument("--min-samples", type=int, default=1)
    parser.add_argument("--min-supervision-coverage", type=float, default=0.0)
    parser.add_argument("--max-background-share", type=float, default=1.0)
    parser.add_argument("--output", type=pathlib.Path)
    args = parser.parse_args()
    if args.min_samples < 1:
        parser.error("--min-samples must be >= 1")
    if not 0.0 <= args.min_supervision_coverage <= 1.0:
        parser.error("--min-supervision-coverage must be between 0 and 1")
    if not 0.0 <= args.max_background_share <= 1.0:
        parser.error("--max-background-share must be between 0 and 1")
    return args


def main() -> int:
    args = parse_args()
    manifest = load_manifest(args.manifest)
    report = verify_coverage(
        manifest,
        args.require_class,
        min_samples=args.min_samples,
        min_supervision_coverage=args.min_supervision_coverage,
        max_background_share=args.max_background_share,
    )
    text = json.dumps(report, indent=2)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
