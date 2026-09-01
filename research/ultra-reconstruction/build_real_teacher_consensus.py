#!/usr/bin/env python3
"""Build Manzl's strict real-plan teacher consensus with source-family provenance.

Raster2Seq, MitUNet and CubiCasa must emit the exact same relative NPZ sample set over the exact same
source raster. Real-corpus samples must also have a separate source-group manifest so all scans,
screenshots, crops or exports derived from one underlying floor plan keep one stable family identity.
That identity is copied into each consensus NPZ and can later be used to block train/validation family
leakage.

No quality gate is relaxed here. Semantic classes still require independent quorum. In particular,
``room_boundary`` is trainable only where two in-domain signals agree: Raster2Seq's predicted room
polygon outline and CubiCasa/FloorTrans's independently derived architectural room-segmentation
transition. CubiCasa room-to-background crop edges abstain, and an old CubiCasa export that lacks the
room-boundary channel is rejected before consensus is written. RoomFormer remains an architecture
reference unless it is explicitly retrained/adapted for raster drawings; its off-the-shelf density-map
checkpoint is not counted as direct evidence.

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
ROOM_BOUNDARY_TEACHERS = ("raster2seq", "cubicasa")


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


def load_source_groups(path: pathlib.Path, samples: list[pathlib.Path]) -> dict[pathlib.Path, str]:
    """Load an exact relative-sample -> underlying-floor-plan-family mapping."""
    if not path.is_file():
        raise FileNotFoundError(f"source-group manifest does not exist: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and "groups" in payload:
        if payload.get("schema") not in (None, 1):
            raise ValueError(f"unsupported source-group manifest schema: {payload.get('schema')}")
        payload = payload["groups"]
    if not isinstance(payload, dict):
        raise ValueError("source-group manifest must be an object or {schema:1, groups:{...}}")

    groups: dict[pathlib.Path, str] = {}
    for raw_relative, raw_group in payload.items():
        if not isinstance(raw_relative, str) or not raw_relative.strip():
            raise ValueError("source-group manifest contains an invalid relative path")
        relative = pathlib.PurePosixPath(raw_relative.strip())
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"source-group path must be safe and relative: {raw_relative!r}")
        normalized = pathlib.Path(*relative.parts)
        if not isinstance(raw_group, str) or not raw_group.strip():
            raise ValueError(f"source group must be a non-empty string for {raw_relative!r}")
        group = raw_group.strip()
        if len(group) > 256:
            raise ValueError(f"source group is unreasonably long for {raw_relative!r}")
        if normalized in groups:
            raise ValueError(f"duplicate normalized source-group path: {raw_relative!r}")
        groups[normalized] = group

    expected = set(samples)
    actual = set(groups)
    if actual != expected:
        missing = sorted(expected - actual)[:10]
        extra = sorted(actual - expected)[:10]
        raise RuntimeError(
            "source-group manifest must cover the exact teacher sample set; "
            f"missing={missing} extra={extra}"
        )
    return groups


def validate_room_boundary_quorum_capability(
    predictions: list[consensus.TeacherPrediction],
    relative: pathlib.Path,
) -> None:
    """Require both independent in-domain room-boundary sources on every real sample."""
    by_id = {prediction.teacher_id: prediction for prediction in predictions}
    room_index = consensus.CLASS_TO_INDEX["room_boundary"]
    for teacher_id in ROOM_BOUNDARY_TEACHERS:
        prediction = by_id.get(teacher_id)
        if prediction is None:
            raise RuntimeError(
                f"room-boundary quorum teacher {teacher_id} is missing for {relative}"
            )
        if not bool(prediction.class_known[room_index]):
            raise RuntimeError(
                f"room-boundary quorum teacher {teacher_id} does not export room_boundary for "
                f"{relative}; regenerate predictions with the current adapter"
            )


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
    source_groups = load_source_groups(args.source_groups, samples)

    specs = [consensus.TeacherSpec(teacher.teacher_id, teacher.root, 1.0) for teacher in teachers]
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
        validate_room_boundary_quorum_capability(predictions, relative)
        output = consensus.build_sample(predictions, build_args)
        if "image" not in output:
            raise RuntimeError(f"Consensus sample unexpectedly lost source image: {relative}")
        output["source_group"] = np.asarray(source_groups[relative])

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
        "schema": 3,
        "pipeline": "raster2seq+mitunet+cubicasa-fail-closed",
        "teacherIds": list(TEACHER_ORDER),
        "samples": len(samples),
        "sourceGroupManifest": str(args.source_groups),
        "sourceGroupCoverage": 1.0,
        "uniqueSourceGroups": len(set(source_groups.values())),
        "supervisedPixels": supervised_pixels,
        "totalPixels": total_pixels,
        "supervisionCoverage": coverage,
        "supervisedPixelsByClass": per_class,
        "roomBoundaryEvidence": {
            "quorumTeachers": list(ROOM_BOUNDARY_TEACHERS),
            "raster2seqSignal": "predicted-room-polygon-outline",
            "cubicasaSignal": "architecturally-supported-room-segmentation-transition",
            "minimumIndependentVotes": args.min_votes,
            "directRoomToBackgroundAllowed": False,
        },
        "thresholds": {
            "minVotes": args.min_votes,
            "criticalMinVotes": args.critical_min_votes,
            "minProbability": args.min_probability,
            "minMargin": args.min_margin,
            "cornerMinVotes": args.corner_min_votes,
            "maxCornerSpread": args.max_corner_spread,
            "orientationMinVotes": args.orientation_min_votes,
        },
        "alignment": "exact-relative-set + exact-source-raster + exact-source-group-manifest",
        "releaseReady": False,
        "reason": (
            "Teacher consensus is training evidence only. Real Saudi/Arabic held-out reconstruction "
            "gates must pass before Ultra release readiness can be asserted."
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
    parser.add_argument(
        "--source-groups",
        type=pathlib.Path,
        required=True,
        help="JSON mapping every relative teacher NPZ path to one stable underlying floor-plan family id",
    )
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
