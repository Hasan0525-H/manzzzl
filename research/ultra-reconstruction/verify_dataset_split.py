#!/usr/bin/env python3
"""Fail closed when Manzl train/validation corpora share source rasters.

A validation score is only meaningful when the held-out split is independent. File names and
container encodings are not reliable identity signals, so this tool canonicalizes every NPZ `image`
to uint8 RGB and hashes its shape + pixels. An identical source plan therefore collides even when one
sample stores floats in [0,1] and another stores uint8 values, or when the files have different names.

This is intentionally exact rather than perceptual. Near-duplicate plan detection belongs in the
real-corpus curation pipeline; this guard prevents the unambiguous leakage case without risking false
positives that could silently remove legitimate distinct plans.
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


def discover_npz(root: pathlib.Path) -> list[pathlib.Path]:
    if not root.is_dir():
        raise FileNotFoundError(f"dataset split does not exist: {root}")
    files = sorted(root.rglob("*.npz"))
    if not files:
        raise RuntimeError(f"dataset split contains no NPZ samples: {root}")
    return files


def canonical_source_image(path: pathlib.Path) -> np.ndarray:
    with np.load(path, allow_pickle=False) as sample:
        if "image" not in sample.files:
            raise ValueError(f"sample is missing required image key: {path}")
        image = np.asarray(sample["image"])

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


def source_digest(path: pathlib.Path) -> str:
    image = canonical_source_image(path)
    digest = hashlib.sha256()
    digest.update(b"manzl-source-raster-v1\0")
    digest.update(np.asarray(image.shape, dtype="<i8").tobytes())
    digest.update(image.tobytes(order="C"))
    return digest.hexdigest()


def index_split(root: pathlib.Path) -> tuple[list[SampleIdentity], dict[str, list[pathlib.Path]]]:
    identities: list[SampleIdentity] = []
    by_digest: dict[str, list[pathlib.Path]] = {}
    for path in discover_npz(root):
        identity = SampleIdentity(path=path, digest=source_digest(path))
        identities.append(identity)
        by_digest.setdefault(identity.digest, []).append(path)
    return identities, by_digest


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

    train_samples, train_index = index_split(train_root)
    validation_samples, validation_index = index_split(validation_root)

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

    overlap = sorted(set(train_index) & set(validation_index))
    if overlap:
        details = []
        for digest in overlap[:12]:
            train_name = display_path(train_index[digest][0], train_root)
            val_name = display_path(validation_index[digest][0], validation_root)
            details.append(f"{digest[:12]} train={train_name} validation={val_name}")
        raise RuntimeError(
            "held-out dataset leakage detected: source raster appears in both train and validation; "
            + "; ".join(details)
        )

    return {
        "schema": 1,
        "identity": "sha256(canonical-uint8-source-raster-shape+pixels)",
        "trainSamples": len(train_samples),
        "validationSamples": len(validation_samples),
        "trainUniqueSources": len(train_index),
        "validationUniqueSources": len(validation_index),
        "crossSplitOverlap": 0,
        "exactSourceLeakage": False,
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
