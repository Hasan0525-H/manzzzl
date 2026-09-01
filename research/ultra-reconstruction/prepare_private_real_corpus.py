#!/usr/bin/env python3
"""Prepare privacy-safe manifests for Manzl's private real-floorplan benchmark.

The real source images stay outside git. This tool reads a local image directory plus a private
``families.json`` mapping and writes only manifests that can drive the strict teacher-consensus and
held-out split pipeline.

A family is one underlying floor plan/house. All scans, screenshots, crops and CAD exports derived from
that plan MUST share one family label in the private input mapping. Raw family labels are never copied
to the output; they are deterministically pseudonymized with SHA-256. Images are also canonicalized to
RGB uint8 and pixel-hashed so renamed duplicates are rejected.

Example private input (do not commit it):

    {
      "villa_001_scan.png": "villa-001",
      "villa_001_phone.jpg": "villa-001",
      "apartment_004.png": "apartment-004"
    }

Outputs:
* ``source-groups.json``: exact ``*.npz`` relative path -> opaque source_group, directly consumable by
  ``build_real_teacher_consensus.py``.
* ``split-manifest.json``: privacy-safe image metadata and a deterministic group-level train/validation/
  test assignment. No source image bytes are copied.

This tool is intentionally local-only and has no network code.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from dataclasses import dataclass
from typing import Iterable

import cv2
import numpy as np

IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp"}
FAMILY_HASH_DOMAIN = b"manzl-private-real-family-v1\0"
RASTER_HASH_DOMAIN = b"manzl-source-raster-v1\0"
SPLIT_HASH_DOMAIN = b"manzl-private-real-split-v1\0"


@dataclass(frozen=True)
class ImageRecord:
    relative: pathlib.Path
    npz_relative: pathlib.Path
    width: int
    height: int
    pixel_sha256: str
    source_group: str
    split: str


def discover_images(root: pathlib.Path) -> list[pathlib.Path]:
    if not root.is_dir():
        raise FileNotFoundError(f"real-plan image directory does not exist: {root}")
    images = sorted(
        path
        for path in root.rglob("*")
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS
    )
    if not images:
        raise RuntimeError(f"no supported real-plan images found under {root}")
    return images


def normalize_relative(raw: str) -> pathlib.Path:
    if not isinstance(raw, str) or not raw.strip():
        raise ValueError("family-map paths must be non-empty strings")
    pure = pathlib.PurePosixPath(raw.strip().replace("\\", "/"))
    if pure.is_absolute() or ".." in pure.parts:
        raise ValueError(f"family-map path must be safe and relative: {raw!r}")
    if not pure.parts:
        raise ValueError(f"family-map path is empty: {raw!r}")
    return pathlib.Path(*pure.parts)


def load_family_map(path: pathlib.Path, expected_images: list[pathlib.Path], root: pathlib.Path) -> dict[pathlib.Path, str]:
    if not path.is_file():
        raise FileNotFoundError(f"private family map does not exist: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and "families" in payload:
        if payload.get("schema") not in (None, 1):
            raise ValueError(f"unsupported private family-map schema: {payload.get('schema')}")
        payload = payload["families"]
    if not isinstance(payload, dict):
        raise ValueError("private family map must be an object or {schema:1,families:{...}}")

    result: dict[pathlib.Path, str] = {}
    for raw_relative, raw_family in payload.items():
        relative = normalize_relative(raw_relative)
        if relative in result:
            raise ValueError(f"duplicate normalized family-map path: {raw_relative!r}")
        if not isinstance(raw_family, str) or not raw_family.strip():
            raise ValueError(f"family label must be a non-empty string for {raw_relative!r}")
        family = raw_family.strip()
        if len(family) > 512:
            raise ValueError(f"family label is unreasonably long for {raw_relative!r}")
        result[relative] = family

    expected = {path.relative_to(root) for path in expected_images}
    actual = set(result)
    if actual != expected:
        missing = sorted(str(path) for path in expected - actual)[:10]
        extra = sorted(str(path) for path in actual - expected)[:10]
        raise RuntimeError(
            "private family map must cover the exact real-plan image set; "
            f"missing={missing} extra={extra}"
        )
    return result


def canonical_rgb(path: pathlib.Path) -> np.ndarray:
    bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if bgr is None:
        raise ValueError(f"OpenCV could not decode real-plan image: {path}")
    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
    if rgb.ndim != 3 or rgb.shape[2] != 3:
        raise ValueError(f"decoded real-plan image must be RGB: {path}")
    return np.ascontiguousarray(rgb, dtype=np.uint8)


def raster_digest(image: np.ndarray) -> str:
    digest = hashlib.sha256()
    digest.update(RASTER_HASH_DOMAIN)
    digest.update(np.asarray(image.shape, dtype="<i8").tobytes())
    digest.update(image.tobytes(order="C"))
    return digest.hexdigest()


def opaque_family_id(label: str, salt: str) -> str:
    digest = hashlib.sha256()
    digest.update(FAMILY_HASH_DOMAIN)
    digest.update(salt.encode("utf-8"))
    digest.update(b"\0")
    digest.update(label.encode("utf-8"))
    return "private:" + digest.hexdigest()[:32]


def group_split(source_group: str, salt: str, validation_fraction: float, test_fraction: float) -> str:
    if not (0.0 <= validation_fraction < 1.0 and 0.0 <= test_fraction < 1.0):
        raise ValueError("validation/test fractions must be in [0,1)")
    if validation_fraction + test_fraction >= 1.0:
        raise ValueError("validation_fraction + test_fraction must be < 1")
    digest = hashlib.sha256()
    digest.update(SPLIT_HASH_DOMAIN)
    digest.update(salt.encode("utf-8"))
    digest.update(b"\0")
    digest.update(source_group.encode("ascii"))
    bucket = int.from_bytes(digest.digest()[:8], "big") / float(1 << 64)
    if bucket < test_fraction:
        return "test"
    if bucket < test_fraction + validation_fraction:
        return "validation"
    return "train"


def build_manifests(
    image_root: pathlib.Path,
    families_path: pathlib.Path,
    salt: str,
    validation_fraction: float,
    test_fraction: float,
) -> tuple[dict, dict]:
    if not salt:
        raise ValueError("a non-empty --salt is required so opaque family IDs are corpus-specific")
    images = discover_images(image_root)
    families = load_family_map(families_path, images, image_root)

    seen_rasters: dict[str, pathlib.Path] = {}
    family_splits: dict[str, str] = {}
    records: list[ImageRecord] = []

    for source in images:
        relative = source.relative_to(image_root)
        image = canonical_rgb(source)
        digest = raster_digest(image)
        prior = seen_rasters.get(digest)
        if prior is not None:
            raise RuntimeError(
                "duplicate canonical real-plan raster detected; renamed duplicates must not re-weight the "
                f"benchmark: {prior} and {relative}"
            )
        seen_rasters[digest] = relative

        group = opaque_family_id(families[relative], salt)
        split = family_splits.setdefault(
            group,
            group_split(group, salt, validation_fraction=validation_fraction, test_fraction=test_fraction),
        )
        npz_relative = relative.with_suffix(".npz")
        records.append(
            ImageRecord(
                relative=relative,
                npz_relative=npz_relative,
                width=int(image.shape[1]),
                height=int(image.shape[0]),
                pixel_sha256=digest,
                source_group=group,
                split=split,
            )
        )

    split_counts = {name: 0 for name in ("train", "validation", "test")}
    group_counts = {name: set() for name in ("train", "validation", "test")}
    for record in records:
        split_counts[record.split] += 1
        group_counts[record.split].add(record.source_group)

    source_groups = {
        "schema": 1,
        "groups": {
            record.npz_relative.as_posix(): record.source_group
            for record in records
        },
    }
    manifest = {
        "schema": 1,
        "purpose": "private-held-out-real-floorplan-corpus",
        "privacy": {
            "sourceImagesCopied": False,
            "rawFamilyLabelsStored": False,
            "sourceGroupScheme": "sha256-domain-separated-pseudonym",
            "relativePathsStored": True,
        },
        "splitPolicy": {
            "unit": "source_group",
            "deterministic": True,
            "validationFraction": validation_fraction,
            "testFraction": test_fraction,
            "trainFraction": 1.0 - validation_fraction - test_fraction,
            "sameFamilyCrossSplitAllowed": False,
        },
        "samples": len(records),
        "uniqueSourceGroups": len(family_splits),
        "samplesBySplit": split_counts,
        "sourceGroupsBySplit": {name: len(groups) for name, groups in group_counts.items()},
        "records": [
            {
                "relativePath": record.relative.as_posix(),
                "teacherSamplePath": record.npz_relative.as_posix(),
                "width": record.width,
                "height": record.height,
                "pixelSha256": record.pixel_sha256,
                "sourceGroup": record.source_group,
                "split": record.split,
            }
            for record in records
        ],
    }
    return source_groups, manifest


def write_json(path: pathlib.Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2, sort_keys=True), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=pathlib.Path, required=True, help="private real-plan image directory")
    parser.add_argument("--families", type=pathlib.Path, required=True, help="private relative-path -> family-label JSON")
    parser.add_argument("--output", type=pathlib.Path, required=True, help="local manifest output directory")
    parser.add_argument("--salt", required=True, help="private corpus salt; keep stable for repeatable IDs/splits")
    parser.add_argument("--validation-fraction", type=float, default=0.15)
    parser.add_argument("--test-fraction", type=float, default=0.15)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    source_groups, manifest = build_manifests(
        image_root=args.input,
        families_path=args.families,
        salt=args.salt,
        validation_fraction=args.validation_fraction,
        test_fraction=args.test_fraction,
    )
    write_json(args.output / "source-groups.json", source_groups)
    write_json(args.output / "split-manifest.json", manifest)
    print(json.dumps({
        "samples": manifest["samples"],
        "uniqueSourceGroups": manifest["uniqueSourceGroups"],
        "samplesBySplit": manifest["samplesBySplit"],
        "sourceGroupsBySplit": manifest["sourceGroupsBySplit"],
        "sourceImagesCopied": False,
        "rawFamilyLabelsStored": False,
    }, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
