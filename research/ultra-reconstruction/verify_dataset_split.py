#!/usr/bin/env python3
"""Fail closed when Manzl train/validation corpora are not independent.

Two complementary identities are checked:
1. Exact source-raster identity. Every NPZ ``image`` is canonicalized to uint8 RGB and hashed, so a
   renamed copy still collides across integer/float container encodings.
2. Optional floor-plan family identity. Samples can carry a scalar string ``source_group`` (for
   example ``resplan:1234`` or a private corpus UUID). Different scans, screenshots, crops or renders
   of the same underlying plan must use the same group and are forbidden from crossing train/validation.

Exact duplicate rasters inside a split are also rejected because they silently re-weight examples.
Multiple distinct variants from one ``source_group`` inside the *same* split are allowed and useful.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class SampleIdentity:
    path: pathlib.Path
    digest: str
    source_group: str | None


def discover_npz(root: pathlib.Path) -> list[pathlib.Path]:
    if not root.is_dir():
        raise FileNotFoundError(f"dataset split does not exist: {root}")
    files = sorted(root.rglob("*.npz"))
    if not files:
        raise RuntimeError(f"dataset split contains no NPZ samples: {root}")
    return files


def _canonicalize_image(image: np.ndarray, path: pathlib.Path) -> np.ndarray:
    if image.ndim != 3 or image.shape[-1] != 3:
        raise ValueError(f"image must be [H,W,3] in {path}, got {image.shape}")
    if not np.isfinite(image).all():
        raise ValueError(f"image contains non-finite values: {path}")

    if np.issubdtype(image.dtype, np.floating):
        value = image.astype(np.float32)
        if float(value.max(initial=0.0)) <= 1.5:
            value = value * 255.0
        image = np.rint(np.clip(value, 0.0, 255.0)).astype(np.uint8)
    else:
        image = np.clip(image, 0, 255).astype(np.uint8)
    return np.ascontiguousarray(image)


def _read_source_group(sample, path: pathlib.Path) -> str | None:
    if "source_group" not in sample.files:
        return None
    raw = np.asarray(sample["source_group"])
    if raw.size != 1:
        raise ValueError(f"source_group must be a scalar string in {path}, got shape {raw.shape}")
    value = raw.reshape(()).item()
    if isinstance(value, bytes):
        value = value.decode("utf-8")
    if not isinstance(value, str):
        raise ValueError(f"source_group must be a scalar string in {path}, got {type(value).__name__}")
    value = value.strip()
    if not value:
        raise ValueError(f"source_group must not be empty in {path}")
    if len(value) > 256:
        raise ValueError(f"source_group is unreasonably long in {path}")
    return value


def read_identity(path: pathlib.Path) -> SampleIdentity:
    with np.load(path, allow_pickle=False) as sample:
        if "image" not in sample.files:
            raise ValueError(f"sample is missing required image key: {path}")
        image = _canonicalize_image(np.asarray(sample["image"]), path)
        source_group = _read_source_group(sample, path)

    digest = hashlib.sha256()
    digest.update(b"manzl-source-raster-v1\0")
    digest.update(np.asarray(image.shape, dtype="<i8").tobytes())
    digest.update(image.tobytes(order="C"))
    return SampleIdentity(path=path, digest=digest.hexdigest(), source_group=source_group)


def canonical_source_image(path: pathlib.Path) -> np.ndarray:
    """Compatibility helper used by external corpus tooling/tests."""
    with np.load(path, allow_pickle=False) as sample:
        if "image" not in sample.files:
            raise ValueError(f"sample is missing required image key: {path}")
        return _canonicalize_image(np.asarray(sample["image"]), path)


def source_digest(path: pathlib.Path) -> str:
    return read_identity(path).digest


def index_split(
    root: pathlib.Path,
) -> tuple[list[SampleIdentity], dict[str, list[pathlib.Path]], dict[str, list[pathlib.Path]]]:
    identities: list[SampleIdentity] = []
    by_digest: dict[str, list[pathlib.Path]] = {}
    by_group: dict[str, list[pathlib.Path]] = {}
    for path in discover_npz(root):
        identity = read_identity(path)
        identities.append(identity)
        by_digest.setdefault(identity.digest, []).append(path)
        if identity.source_group is not None:
            by_group.setdefault(identity.source_group, []).append(path)
    return identities, by_digest, by_group


def duplicate_groups(index: dict[str, list[pathlib.Path]]) -> dict[str, list[pathlib.Path]]:
    return {digest: paths for digest, paths in index.items() if len(paths) > 1}


def display_path(path: pathlib.Path, root: pathlib.Path) -> str:
    try:
        return str(path.relative_to(root))
    except ValueError:
        return str(path)


def verify_split_independence(train_root: pathlib.Path, validation_root: pathlib.Path) -> dict:
    train_root = train_root.resolve()
    validation_root = validation_root.resolve()
    if train_root == validation_root:
        raise RuntimeError("train and validation roots resolve to the same directory")

    train_samples, train_index, train_groups = index_split(train_root)
    validation_samples, validation_index, validation_groups = index_split(validation_root)

    train_duplicates = duplicate_groups(train_index)
    validation_duplicates = duplicate_groups(validation_index)
    if train_duplicates or validation_duplicates:
        details = []
        for split_name, root, groups in (
            ("train", train_root, train_duplicates),
            ("validation", validation_root, validation_duplicates),
        ):
            for digest, paths in sorted(groups.items())[:8]:
                names = ", ".join(display_path(path, root) for path in paths[:4])
                details.append(f"{split_name}:{digest[:12]}=[{names}]")
        raise RuntimeError(
            "duplicate source rasters found inside a dataset split; duplicated plans would distort "
            "training/evaluation weighting: " + "; ".join(details)
        )

    exact_overlap = sorted(set(train_index) & set(validation_index))
    if exact_overlap:
        details = []
        for digest in exact_overlap[:12]:
            train_name = display_path(train_index[digest][0], train_root)
            val_name = display_path(validation_index[digest][0], validation_root)
            details.append(f"{digest[:12]} train={train_name} validation={val_name}")
        raise RuntimeError(
            "held-out dataset leakage detected: source raster appears in both train and validation; "
            + "; ".join(details)
        )

    group_overlap = sorted(set(train_groups) & set(validation_groups))
    if group_overlap:
        details = []
        for group in group_overlap[:12]:
            train_name = display_path(train_groups[group][0], train_root)
            val_name = display_path(validation_groups[group][0], validation_root)
            details.append(f"{group!r} train={train_name} validation={val_name}")
        raise RuntimeError(
            "held-out floor-plan family leakage detected: source_group appears in both train and "
            "validation; keep all scans/renders/variants of one underlying plan in one split only; "
            + "; ".join(details)
        )

    return {
        "schema": 2,
        "exactIdentity": "sha256(canonical-uint8-source-raster-shape+pixels)",
        "familyIdentity": "optional NPZ source_group scalar string",
        "trainSamples": len(train_samples),
        "validationSamples": len(validation_samples),
        "trainUniqueRasters": len(train_index),
        "validationUniqueRasters": len(validation_index),
        "trainSourceGroups": len(train_groups),
        "validationSourceGroups": len(validation_groups),
        "crossSplitExactOverlap": 0,
        "crossSplitGroupOverlap": 0,
        "exactSourceLeakage": False,
        "floorPlanFamilyLeakage": False,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--train", type=pathlib.Path, required=True)
    parser.add_argument("--validation", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = verify_split_independence(args.train, args.validation)
    text = json.dumps(report, indent=2)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
