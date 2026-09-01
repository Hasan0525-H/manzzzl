#!/usr/bin/env python3
"""Build fail-closed pseudo-labels from multiple independent floor-plan teachers.

This is the bridge between the heavy development-time ensemble and the mobile Manzl student.
It intentionally does *not* average disagreement into fake geometry. A pixel/class is supervised only
when enough independent teachers agree and the weighted probability margin is strong. Uncertain areas
remain masked out of student training.

Each teacher directory contains matching relative ``.npz`` files. Supported fields per teacher sample:

  image                 uint8/float32 [H,W,3]      optional; first available copy is retained
  semantic_probs        float32       [C,H,W]      preferred
  semantic              int64         [H,W]        accepted as hard labels
  semantic_classes      str           [C]          optional local class names
  confidence            float32       scalar/[H,W] optional teacher confidence multiplier
  valid_mask            float32/bool  [H,W]        optional coverage mask
  corners               float32       [H,W]        optional corner heatmap
  corner_confidence      float32       [H,W]        optional corner confidence
  orientation           float32       [2,H,W]      optional wall tangent cos/sin (axial)

Output NPZ keys are directly consumable by ``train_student.py``:

  image, semantic, semantic_confidence, supervision_mask,
  corners, corner_mask, orientation, wall_mask, orientation_mask, teacher_count

Example:
  python build_teacher_consensus.py \
    --teacher raster2seq=.cache/pred/raster2seq \
    --teacher roomformer=.cache/pred/roomformer \
    --teacher cage=.cache/pred/cage \
    --teacher mitunet=.cache/pred/mitunet \
    --output .cache/manzl-consensus

No cloud API is used. Teacher checkpoints remain outside the public repository/APK.
"""

from __future__ import annotations

import argparse
import pathlib
from dataclasses import dataclass
from typing import Iterable

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
CLASS_TO_INDEX = {name: index for index, name in enumerate(SEMANTIC_CLASSES)}

# Geometry-bearing classes require corroboration by default. A single neural teacher is not allowed
# to manufacture an opening, column or void in the distilled model.
CRITICAL_CLASSES = {"door", "window", "stair", "column", "courtyard", "shaft"}


@dataclass(frozen=True)
class TeacherSpec:
    teacher_id: str
    root: pathlib.Path
    weight: float


@dataclass
class TeacherPrediction:
    teacher_id: str
    weight: float
    probs: np.ndarray          # [K,H,W], global class order
    class_known: np.ndarray    # [K] bool
    confidence: np.ndarray     # [H,W]
    valid: np.ndarray          # [H,W] bool
    image: np.ndarray | None
    corners: np.ndarray | None
    corner_confidence: np.ndarray | None
    orientation: np.ndarray | None


def parse_teacher(value: str) -> TeacherSpec:
    # id=path[:weight]. Weight suffix is optional; Windows drive letters are safe because only the
    # last colon is interpreted and only when its suffix parses as float.
    if "=" not in value:
        raise argparse.ArgumentTypeError("Teacher must use id=path or id=path:weight")
    teacher_id, raw = value.split("=", 1)
    teacher_id = teacher_id.strip()
    if not teacher_id:
        raise argparse.ArgumentTypeError("Teacher id cannot be empty")
    weight = 1.0
    path_text = raw
    if ":" in raw:
        maybe_path, maybe_weight = raw.rsplit(":", 1)
        try:
            weight = float(maybe_weight)
            path_text = maybe_path
        except ValueError:
            pass
    if weight <= 0:
        raise argparse.ArgumentTypeError("Teacher weight must be positive")
    return TeacherSpec(teacher_id=teacher_id, root=pathlib.Path(path_text), weight=weight)


def discover_samples(teachers: list[TeacherSpec], min_teachers: int) -> list[pathlib.Path]:
    counts: dict[pathlib.Path, int] = {}
    for teacher in teachers:
        if not teacher.root.exists():
            raise FileNotFoundError(f"Teacher directory does not exist: {teacher.root}")
        for path in teacher.root.rglob("*.npz"):
            relative = path.relative_to(teacher.root)
            counts[relative] = counts.get(relative, 0) + 1
    selected = sorted(relative for relative, count in counts.items() if count >= min_teachers)
    if not selected:
        raise RuntimeError("No matching NPZ samples have enough independent teacher predictions")
    return selected


def read_string_array(value: np.ndarray | None) -> list[str] | None:
    if value is None:
        return None
    flat = np.asarray(value).reshape(-1)
    return [str(item.decode("utf-8") if isinstance(item, bytes) else item) for item in flat]


def load_prediction(spec: TeacherSpec, relative: pathlib.Path) -> TeacherPrediction | None:
    path = spec.root / relative
    if not path.exists():
        return None
    with np.load(path, allow_pickle=False) as sample:
        names = set(sample.files)
        classes = read_string_array(sample["semantic_classes"] if "semantic_classes" in names else None)
        local_probs: np.ndarray
        if "semantic_probs" in names:
            local_probs = np.asarray(sample["semantic_probs"], dtype=np.float32)
            if local_probs.ndim != 3:
                raise ValueError(f"semantic_probs must be [C,H,W] in {path}, got {local_probs.shape}")
            # Accept logits as a convenience when the export explicitly says so.
            if "semantic_is_logits" in names and bool(np.asarray(sample["semantic_is_logits"]).reshape(-1)[0]):
                local_probs = softmax(local_probs, axis=0)
            else:
                local_probs = np.clip(local_probs, 0.0, 1.0)
                denominator = local_probs.sum(axis=0, keepdims=True)
                local_probs = np.divide(
                    local_probs,
                    np.maximum(denominator, 1e-6),
                    out=np.zeros_like(local_probs),
                    where=denominator > 1e-6,
                )
        elif "semantic" in names:
            labels = np.asarray(sample["semantic"], dtype=np.int64)
            if labels.ndim != 2:
                raise ValueError(f"semantic must be [H,W] in {path}, got {labels.shape}")
            class_count = len(classes) if classes is not None else len(SEMANTIC_CLASSES)
            local_probs = np.zeros((class_count, *labels.shape), dtype=np.float32)
            valid_label = (labels >= 0) & (labels < class_count)
            ys, xs = np.nonzero(valid_label)
            local_probs[labels[ys, xs], ys, xs] = 1.0
        else:
            raise ValueError(f"Missing semantic_probs/semantic in {path}")

        if classes is None:
            if local_probs.shape[0] != len(SEMANTIC_CLASSES):
                raise ValueError(
                    f"{path} has {local_probs.shape[0]} channels but no semantic_classes mapping"
                )
            classes = SEMANTIC_CLASSES
        if len(classes) != local_probs.shape[0]:
            raise ValueError(f"semantic_classes length does not match channels in {path}")

        height, width = local_probs.shape[1:]
        probs = np.zeros((len(SEMANTIC_CLASSES), height, width), dtype=np.float32)
        class_known = np.zeros(len(SEMANTIC_CLASSES), dtype=bool)
        for local_index, name in enumerate(classes):
            global_index = CLASS_TO_INDEX.get(name)
            if global_index is None:
                continue
            probs[global_index] = local_probs[local_index]
            class_known[global_index] = True

        confidence = np.asarray(sample["confidence"], dtype=np.float32) if "confidence" in names else np.array(1.0, dtype=np.float32)
        if confidence.ndim == 0:
            confidence = np.full((height, width), float(confidence), dtype=np.float32)
        if confidence.shape != (height, width):
            raise ValueError(f"confidence must be scalar/[H,W] in {path}")
        confidence = np.clip(confidence, 0.0, 1.0)

        valid = np.asarray(sample["valid_mask"]).astype(bool) if "valid_mask" in names else np.ones((height, width), dtype=bool)
        if valid.shape != (height, width):
            raise ValueError(f"valid_mask must be [H,W] in {path}")

        image = np.asarray(sample["image"]) if "image" in names else None
        if image is not None and (image.ndim != 3 or image.shape[:2] != (height, width) or image.shape[-1] != 3):
            raise ValueError(f"image must be [H,W,3] and match semantic size in {path}")

        corners = np.asarray(sample["corners"], dtype=np.float32) if "corners" in names else None
        if corners is not None and corners.shape != (height, width):
            raise ValueError(f"corners must be [H,W] in {path}")
        corner_confidence = np.asarray(sample["corner_confidence"], dtype=np.float32) if "corner_confidence" in names else None
        if corner_confidence is not None and corner_confidence.shape != (height, width):
            raise ValueError(f"corner_confidence must be [H,W] in {path}")

        orientation = np.asarray(sample["orientation"], dtype=np.float32) if "orientation" in names else None
        if orientation is not None and orientation.shape != (2, height, width):
            raise ValueError(f"orientation must be [2,H,W] in {path}")

    return TeacherPrediction(
        teacher_id=spec.teacher_id,
        weight=spec.weight,
        probs=probs,
        class_known=class_known,
        confidence=confidence,
        valid=valid,
        image=image,
        corners=corners,
        corner_confidence=corner_confidence,
        orientation=orientation,
    )


def softmax(values: np.ndarray, axis: int) -> np.ndarray:
    shifted = values - np.max(values, axis=axis, keepdims=True)
    exp = np.exp(np.clip(shifted, -80.0, 80.0))
    return exp / np.maximum(exp.sum(axis=axis, keepdims=True), 1e-6)


def validate_shapes(predictions: list[TeacherPrediction], relative: pathlib.Path) -> tuple[int, int]:
    shapes = {prediction.probs.shape[1:] for prediction in predictions}
    if len(shapes) != 1:
        raise ValueError(f"Teacher spatial sizes disagree for {relative}: {sorted(shapes)}")
    return next(iter(shapes))


def semantic_consensus(
    predictions: list[TeacherPrediction],
    min_votes: int,
    critical_min_votes: int,
    min_probability: float,
    min_margin: float,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    height, width = predictions[0].probs.shape[1:]
    class_count = len(SEMANTIC_CLASSES)
    weighted_sum = np.zeros((class_count, height, width), dtype=np.float32)
    weight_sum = np.zeros((class_count, height, width), dtype=np.float32)
    hard_votes = np.zeros((class_count, height, width), dtype=np.int16)

    for prediction in predictions:
        effective = prediction.weight * prediction.confidence * prediction.valid.astype(np.float32)
        # Hard-vote only among classes the teacher actually exports. Missing channels are abstentions.
        masked_probs = np.where(prediction.class_known[:, None, None], prediction.probs, -1.0)
        winner = np.argmax(masked_probs, axis=0)
        winner_prob = np.take_along_axis(prediction.probs, winner[None, ...], axis=0)[0]
        for class_index in np.nonzero(prediction.class_known)[0]:
            weighted_sum[class_index] += prediction.probs[class_index] * effective
            weight_sum[class_index] += effective
            hard_votes[class_index] += (
                (winner == class_index) &
                (winner_prob >= min_probability) &
                prediction.valid
            ).astype(np.int16)

    mean_probs = np.divide(
        weighted_sum,
        np.maximum(weight_sum, 1e-6),
        out=np.zeros_like(weighted_sum),
        where=weight_sum > 1e-6,
    )

    # A class must first earn the required number of independent hard votes before it can compete to
    # become the final winner. Without this filter, one unsupported high-confidence teacher could
    # outrank two agreeing teachers, fail min_votes afterwards, and erase otherwise valid supervision.
    # Missing channels remain abstentions and a one-vote class cannot act as a veto against quorum.
    required_votes_by_class = np.full(class_count, min_votes, dtype=np.int16)
    for class_name in CRITICAL_CLASSES:
        required_votes_by_class[CLASS_TO_INDEX[class_name]] = critical_min_votes

    eligible = (
        (hard_votes >= required_votes_by_class[:, None, None]) &
        (mean_probs >= min_probability) &
        (weight_sum > 1e-6)
    )
    eligible_probs = np.where(eligible, mean_probs, -1.0)
    order = np.argsort(eligible_probs, axis=0)
    winner = order[-1]
    runner = order[-2]
    winner_prob = np.take_along_axis(eligible_probs, winner[None, ...], axis=0)[0]
    runner_prob = np.take_along_axis(eligible_probs, runner[None, ...], axis=0)[0]
    has_winner = winner_prob >= 0.0
    # If only one class reaches quorum, there is no eligible competing class; compare against zero.
    runner_prob = np.where(runner_prob >= 0.0, runner_prob, 0.0)
    margin = winner_prob - runner_prob
    winning_votes = np.take_along_axis(hard_votes, winner[None, ...], axis=0)[0]

    supervision = has_winner & (margin >= min_margin)
    semantic = winner.astype(np.int64)
    # Label value is irrelevant where supervision=0, but background keeps debug visualizations sane.
    semantic[~supervision] = CLASS_TO_INDEX["background"]
    winner_prob = np.where(supervision, winner_prob, 0.0)
    winning_votes = np.where(supervision, winning_votes, 0)
    return semantic, winner_prob.astype(np.float32), supervision.astype(np.float32), winning_votes


def corner_consensus(
    predictions: list[TeacherPrediction],
    min_votes: int,
    max_spread: float,
) -> tuple[np.ndarray, np.ndarray]:
    height, width = predictions[0].probs.shape[1:]
    values = []
    weights = []
    for prediction in predictions:
        if prediction.corners is None:
            continue
        confidence = prediction.corner_confidence if prediction.corner_confidence is not None else prediction.confidence
        weight = prediction.weight * np.clip(confidence, 0.0, 1.0) * prediction.valid.astype(np.float32)
        values.append(np.clip(prediction.corners, 0.0, 1.0))
        weights.append(weight)
    if not values:
        return np.zeros((height, width), dtype=np.float32), np.zeros((height, width), dtype=np.float32)

    value_stack = np.stack(values, axis=0)
    weight_stack = np.stack(weights, axis=0)
    active = weight_stack > 1e-6
    vote_count = active.sum(axis=0)
    denominator = np.maximum(weight_stack.sum(axis=0), 1e-6)
    mean = (value_stack * weight_stack).sum(axis=0) / denominator
    variance = (((value_stack - mean[None, ...]) ** 2) * weight_stack).sum(axis=0) / denominator
    spread = np.sqrt(np.maximum(variance, 0.0))
    mask = (vote_count >= min_votes) & (spread <= max_spread)
    return mean.astype(np.float32), mask.astype(np.float32)


def orientation_consensus(
    predictions: list[TeacherPrediction],
    wall_mask: np.ndarray,
    min_votes: int,
) -> tuple[np.ndarray, np.ndarray]:
    height, width = wall_mask.shape
    # Axial orientation means v and -v are identical. Average doubled-angle vectors to avoid sign
    # cancellation, then convert back to a unit tangent.
    sum_cos2 = np.zeros((height, width), dtype=np.float32)
    sum_sin2 = np.zeros((height, width), dtype=np.float32)
    sum_weight = np.zeros((height, width), dtype=np.float32)
    votes = np.zeros((height, width), dtype=np.int16)

    for prediction in predictions:
        if prediction.orientation is None:
            continue
        x = prediction.orientation[0]
        y = prediction.orientation[1]
        norm = np.sqrt(x * x + y * y)
        usable = (norm > 0.25) & prediction.valid
        nx = np.divide(x, np.maximum(norm, 1e-6))
        ny = np.divide(y, np.maximum(norm, 1e-6))
        cos2 = nx * nx - ny * ny
        sin2 = 2.0 * nx * ny
        weight = prediction.weight * prediction.confidence * usable.astype(np.float32)
        sum_cos2 += cos2 * weight
        sum_sin2 += sin2 * weight
        sum_weight += weight
        votes += usable.astype(np.int16)

    angle = 0.5 * np.arctan2(sum_sin2, sum_cos2)
    orientation = np.stack([np.cos(angle), np.sin(angle)], axis=0).astype(np.float32)
    resultant = np.sqrt(sum_cos2 * sum_cos2 + sum_sin2 * sum_sin2) / np.maximum(sum_weight, 1e-6)
    mask = (wall_mask > 0.5) & (votes >= min_votes) & (resultant >= 0.70)
    orientation[:, ~mask] = 0.0
    return orientation, mask.astype(np.float32)


def choose_image(predictions: Iterable[TeacherPrediction]) -> np.ndarray | None:
    reference = None
    for prediction in predictions:
        if prediction.image is None:
            continue
        if reference is None:
            reference = prediction.image
        elif reference.shape != prediction.image.shape:
            raise ValueError("Teacher image copies disagree in shape")
    return reference


def build_sample(
    predictions: list[TeacherPrediction],
    args: argparse.Namespace,
) -> dict[str, np.ndarray]:
    validate_shapes(predictions, pathlib.Path("<sample>"))
    semantic, semantic_confidence, supervision, teacher_count = semantic_consensus(
        predictions=predictions,
        min_votes=args.min_votes,
        critical_min_votes=args.critical_min_votes,
        min_probability=args.min_probability,
        min_margin=args.min_margin,
    )
    corners, corner_mask = corner_consensus(
        predictions=predictions,
        min_votes=args.corner_min_votes,
        max_spread=args.max_corner_spread,
    )
    wall_mask = ((semantic == CLASS_TO_INDEX["wall_face"]) & (supervision > 0.5)).astype(np.float32)
    orientation, orientation_mask = orientation_consensus(
        predictions=predictions,
        wall_mask=wall_mask,
        min_votes=args.orientation_min_votes,
    )

    output: dict[str, np.ndarray] = {
        "semantic": semantic,
        "semantic_confidence": semantic_confidence,
        "supervision_mask": supervision,
        "corners": corners,
        "corner_mask": corner_mask,
        "orientation": orientation,
        "wall_mask": wall_mask,
        "orientation_mask": orientation_mask,
        "teacher_count": teacher_count.astype(np.int16),
        "semantic_classes": np.asarray(SEMANTIC_CLASSES, dtype="U32"),
        "teacher_ids": np.asarray([prediction.teacher_id for prediction in predictions], dtype="U64"),
    }
    image = choose_image(predictions)
    if image is not None:
        output["image"] = image
    return output


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--teacher", action="append", type=parse_teacher, required=True,
                        help="id=prediction_dir or id=prediction_dir:weight; repeat for each independent teacher")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--min-teachers-per-sample", type=int, default=2)
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
    teachers: list[TeacherSpec] = args.teacher
    if len({teacher.teacher_id for teacher in teachers}) != len(teachers):
        raise ValueError("Teacher ids must be unique; duplicated evidence is not independent evidence")
    if args.min_teachers_per_sample < 2:
        raise ValueError("Ultra consensus requires at least two independent teachers per sample")
    if args.min_votes < 1 or args.critical_min_votes < 2:
        raise ValueError("Geometry-critical pseudo-labels require at least two votes")

    samples = discover_samples(teachers, args.min_teachers_per_sample)
    written = 0
    skipped_no_image = 0
    supervised_pixels = 0
    total_pixels = 0

    for relative in samples:
        predictions = [
            prediction
            for teacher in teachers
            if (prediction := load_prediction(teacher, relative)) is not None
        ]
        if len(predictions) < args.min_teachers_per_sample:
            continue
        validate_shapes(predictions, relative)
        output = build_sample(predictions, args)
        if "image" not in output:
            skipped_no_image += 1
            continue
        destination = args.output / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        np.savez_compressed(destination, **output)
        supervised_pixels += int(output["supervision_mask"].sum())
        total_pixels += int(output["supervision_mask"].size)
        written += 1

    if written == 0:
        raise RuntimeError("Consensus produced no trainable samples with an image")

    coverage = supervised_pixels / max(total_pixels, 1)
    print(f"wrote={written} skipped_without_image={skipped_no_image} supervision_coverage={coverage:.4f}")
    print("Disagreement was masked, not averaged into geometry.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
