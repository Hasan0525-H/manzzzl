#!/usr/bin/env python3
"""Build Manzl's real-plan consensus from the three complementary pinned teacher streams.

This is the strict local bridge used after teacher inference:

    Raster2Seq  -> doors, windows, room-boundary topology
    MitUNet     -> wall/background probability
    CubiCasa    -> wall/background + door/window probability

The script intentionally requires the *same* relative NPZ sample set from all three teachers and
verifies that every teacher stored the exact same source raster. A same-sized but different crop is a
hard failure because spatially misregistered pseudo-labels would poison the mobile student.

No quality gate is relaxed here. Semantic classes still require the quorum configured in
``build_teacher_consensus.py`` (two independent votes by default), so Raster2Seq-only room-boundary
labels remain unsupervised until a second independent polygon teacher is available. That is safer than
promoting single-teacher topology into release training.

Everything runs locally; no cloud or paid API is used.
"""

from __future__ import annotations

import argparse
import json
import pathlib
from dataclasses import dataclass

import numpy as np

import build_teacher_consensus as consensus


@dataclass(frozen=True)
class TeacherRoot:
    teacher_id: str
    root: pathlib.Path


TEACHER_ORDER = ("raster2seq", "mitunet", "cubicasa")


def discover_npz(root: pathlib.Path) -> set[pathlib.Path]:
    if not root.is_dir():
        raise FileNotFoundError(f"Teacher directory does not exist: {root}")
    files = {path.relative_to(root) for path in root.rglob("*.npz")}
    if not files:
        raise RuntimeError(f"No teacher NPZ samples found under {root}")
    return files


def normalize_image_for_comparison(image: np.ndarray, source: pathlib.Path) -> np.ndarray:
    value = np.asarray(image)
    if value.ndim != 3 or value.shape[-1] != 3:
        raise ValueError(f"image must be [H,W,3] in {source}, got {value.shape}")
    if not np.isfinite(value).all():
        raise ValueError(f"image contains non-finite values in {source}")
    if np.issubdtype(value.dtype, np.floating):
        value = value.astype(np.float32)
        if float(value.max(initial=0.0)) <= 1.5:
            value = value * 255.0
        value = np.rint(np.clip(value, 0.0, 255.0)).astype(np.uint8)
    else:
        value = np.clip(value, 0, 255).astype(np.uint8)
    return value


def read_source_image(path: pathlib.Path) -> np.ndarray:
    with np.load(path, allow_pickle=False) as sample:
        if "image" not in sample.files:
            raise ValueError(f"Teacher sample is missing the source image required for alignment: {path}")
        return normalize_image_for_comparison(np.asarray(sample["image"]), path)


def validate_exact_alignment(teachers: list[TeacherRoot]) -> list[pathlib.Path]:
    if [teacher.teacher_id for teacher in teachers] != list(TEACHER_ORDER):
        raise ValueError(f"Expected teacher order {TEACHER_ORDER}")

    sets = {teacher.teacher_id: discover_npz(teacher.root) for teacher in teachers}
    reference = sets[TEACHER_ORDER[0]]
    for teacher_id in TEACHER_ORDER[1:]:
        current = sets[teacher_id]
        if current != reference:
            missing = sorted(reference - current)[:10]
            extra = sorted(current - reference)[:10]
            raise RuntimeError(
                f"Teacher sample sets are not identical for {teacher_id}; "
                f"missing={missing} extra={extra}"
            )

    ordered = sorted(reference)
    for relative in ordered:
        images = [read_source_image(teacher.root / relative) for teacher in teachers]
        shape = images[0].shape
        if any(image.shape != shape for image in images[1:]):
            raise RuntimeError(
                f"Teacher source image shapes disagree for {relative}: "
                f"{[image.shape for image in images]}"
            )
        for index, image in enumerate(images[1:], start=1):
            if not np.array_equal(images[0], image):
                difference = np.abs(images[0].astype(np.int16) - image.astype(np.int16))
                raise RuntimeError(
                    f"Teacher source rasters are not pixel-aligned for {relative}: "
                    f"{teachers[0].teacher_id} vs {teachers[index].teacher_id}; "
                    f"max_abs_difference={int(difference.max(initial=0))}"
                )
    return ordered


def class_supervision_counts(semantic: np.ndarray, supervision: np.ndarray) -> dict[str, int]:
    active = supervision > 0.5
    return {
        class_name: int(((semantic == class_index) & active).sum())
        for class_index, class_name in enumerate(consensus.SEMANTIC_CLASSES)
    }


def build(args: argparse.Namespace) -> dict:
    teachers = [
        TeacherRoot("raster2seq", args.raster2seq),
        TeacherRoot("mitunet", args.mitunet),
        TeacherRoot("cubicasa", args.cubicasa),
    ]
    samples = validate_exact_alignment(teachers)

    specs = [
        consensus.TeacherSpec(teacher.teacher_id, teacher.root, 1.0)
        for teacher in teachers
    ]
    build_args = argparse.Namespace(
        min_votes=args.min_votes,
        critical_min_votes=args.critical_min_votes,
        min_probability=args.min_probability,
        min_margin=args.min_margin,
        corner_min_votes=args.corner_min_votes,
        max_corner_spread=args.max_corner_spread,
        orientation_min_votes=args.orientation_min_votes,
    )

    args.output.mkdir(parents=True, exist_ok=True)
    supervised_pixels = 0
    total_pixels = 0
    per_class = {name: 0 for name in consensus.SEMANTIC_CLASSES}

    for relative in samples:
        predictions = []
        for spec in specs:
            prediction = consensus.load_prediction(spec, relative)
            if prediction is None:
                raise RuntimeError(f"Teacher sample disappeared during build: {spec.teacher_id}/{relative}")
            predictions.append(prediction)
        consensus.validate_shapes(predictions, relative)
        output = consensus.build_sample(predictions, build_args)
        if "image" not in output:
            raise RuntimeError(f"Consensus sample unexpectedly lost source image: {relative}")

        destination = args.output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        np.savez_compressed(destination, **output)

        supervision = np.asarray(output["supervision_mask"], dtype=np.float32)
        semantic = np.asarray(output["semantic"], dtype=np.int64)
        supervised_pixels += int(supervision.sum())
        total_pixels += int(supervision.size)
        for name, count in class_supervision_counts(semantic, supervision).items():
            per_class[name] += count

    coverage = supervised_pixels / max(total_pixels, 1)
    manifest = {
        "schema": 1,
        "pipeline": "raster2seq+mitunet+cubicasa-fail-closed",
        "teacherIds": list(TEACHER_ORDER),
        "samples": len(samples),
        "supervisedPixels": supervised_pixels,
        "totalPixels": total_pixels,
        "supervisionCoverage": coverage,
        "supervisedPixelsByClass": per_class,
        "thresholds": {
            "minVotes": args.min_votes,
            "criticalMinVotes": args.critical_min_votes,
            "minProbability": args.min_probability,
            "minMargin": args.min_margin,
            "cornerMinVotes": args.corner_min_votes,
            "maxCornerSpread": args.max_corner_spread,
            "orientationMinVotes": args.orientation_min_votes,
        },
        "alignment": "exact-relative-set + exact-source-raster",
        "releaseReady": False,
        "reason": (
            "Teacher consensus is training evidence only. Real Saudi/Arabic plan benchmark and runtime "
            "reconstruction gates must pass before Ultra release readiness can be asserted."
        ),
    }
    manifest_path = args.output / "consensus_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raster2seq", type=pathlib.Path, required=True)
    parser.add_argument("--mitunet", type=pathlib.Path, required=True)
    parser.add_argument("--cubicasa", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--min-votes", type=int, default=2)
    parser.add_argument("--critical-min-votes", type=int, default=2)
    parser.add_argument("--min-probability", type=float, default=0.72)
    parser.add_argument("--min-margin", type=float, default=0.18)
    parser.add_argument("--corner-min-votes", type=int, default=2)
    parser.add_argument("--max-corner-spread", type=float, default=0.18)
    parser.add_argument("--orientation-min-votes", type=int, default=2)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.min_votes < 2:
        raise ValueError("Real teacher consensus requires at least two semantic votes")
    if args.critical_min_votes < 2:
        raise ValueError("Geometry-critical classes require at least two independent votes")
    manifest = build(args)
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
