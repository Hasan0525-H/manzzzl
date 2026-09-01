#!/usr/bin/env python3
"""Prepare privacy-safe manifests for Manzl's private real-floorplan benchmark.

Real source images and their operational file paths stay outside git. This tool reads a local image
root plus a private ``families.json`` mapping, then emits two deliberately different artifacts:

* ``source-groups.local.json`` is LOCAL-ONLY operational state. It maps exact ``*.npz`` relative paths
  to opaque source groups so ``build_real_teacher_consensus.py`` can preserve family provenance. It is
  explicitly marked unsafe to commit because paths themselves can contain names, cities or projects.
* ``split-manifest.json`` is privacy-safe metadata. It contains no source path, filename, raw family
  label, or unsalted source-raster hash. Samples and families are represented only by corpus-salted,
  domain-separated pseudonyms.

A family is one underlying floor plan/house. All scans, screenshots, crops and CAD exports derived from
that plan MUST share one family label in the private input mapping. Exact canonical raster duplicates
are rejected locally so renamed copies cannot re-weight the benchmark. A deliberately conservative
perceptual guard also rejects near-identical large rasters assigned to different families (for example,
recompressed/rescaled/whitespace-cropped copies), preventing one house from leaking across splits.

This tool is intentionally local-only and has no network code.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
from dataclasses import dataclass

import cv2
import numpy as np

IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp"}
FAMILY_HASH_DOMAIN = b"manzl-private-real-family-v1\0"
RASTER_HASH_DOMAIN = b"manzl-source-raster-v1\0"
SAMPLE_HASH_DOMAIN = b"manzl-private-real-sample-v1\0"
SPLIT_HASH_DOMAIN = b"manzl-private-real-split-v1\0"

# Perceptual duplicate detection is intentionally conservative. Small synthetic/thumbnail images are
# excluded because 64-bit hashes are not discriminative enough there. A pair is rejected only when
# content aspect ratio and two independent hashes are simultaneously extremely close.
NEAR_DUPLICATE_MIN_SIDE = 96
NEAR_DUPLICATE_ASPECT_DELTA_MAX = 0.02
NEAR_DUPLICATE_PHASH_DISTANCE_MAX = 2
NEAR_DUPLICATE_DHASH_DISTANCE_MAX = 4


@dataclass(frozen=True)
class ImageRecord:
    relative: pathlib.Path
    npz_relative: pathlib.Path
    width: int
    height: int
    pixel_sha256: str
    sample_id: str
    source_group: str
    split: str


@dataclass(frozen=True)
class PerceptualSignature:
    phash: int
    dhash: int
    content_aspect: float


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


def load_family_map(
    path: pathlib.Path,
    expected_images: list[pathlib.Path],
    root: pathlib.Path,
) -> dict[pathlib.Path, str]:
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

    expected = {image.relative_to(root) for image in expected_images}
    actual = set(result)
    if actual != expected:
        missing = sorted(str(item) for item in expected - actual)[:10]
        extra = sorted(str(item) for item in actual - expected)[:10]
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


def _structural_content(gray: np.ndarray) -> np.ndarray:
    """Crop mostly-white margins before perceptual comparison.

    Floor plans may be exported with different page margins or screenshot padding. We crop to the union
    of dark/ink pixels, with a small safety margin, so those presentation changes do not defeat the
    duplicate guard. The operation is used only locally and no fingerprint is emitted to safe metadata.
    """
    if gray.ndim != 2:
        raise ValueError("perceptual signature expects grayscale input")
    blurred = cv2.GaussianBlur(gray, (3, 3), 0)
    threshold = min(248, max(180, int(np.percentile(blurred, 92))))
    ink = (blurred < threshold).astype(np.uint8)
    ys, xs = np.nonzero(ink)
    if len(xs) < 32:
        return gray
    x0, x1 = int(xs.min()), int(xs.max())
    y0, y1 = int(ys.min()), int(ys.max())
    pad_x = max(2, int(round((x1 - x0 + 1) * 0.02)))
    pad_y = max(2, int(round((y1 - y0 + 1) * 0.02)))
    x0 = max(0, x0 - pad_x)
    y0 = max(0, y0 - pad_y)
    x1 = min(gray.shape[1] - 1, x1 + pad_x)
    y1 = min(gray.shape[0] - 1, y1 + pad_y)
    return gray[y0:y1 + 1, x0:x1 + 1]


def _hash_bits(bits: np.ndarray) -> int:
    result = 0
    for index, bit in enumerate(bits.reshape(-1).tolist()):
        if bool(bit):
            result |= 1 << index
    return result


def perceptual_signature(image: np.ndarray) -> PerceptualSignature | None:
    if min(int(image.shape[0]), int(image.shape[1])) < NEAR_DUPLICATE_MIN_SIDE:
        return None
    gray = cv2.cvtColor(image, cv2.COLOR_RGB2GRAY)
    content = _structural_content(gray)
    if min(content.shape[:2]) < 24:
        return None

    phash_input = cv2.resize(content, (32, 32), interpolation=cv2.INTER_AREA).astype(np.float32)
    coefficients = cv2.dct(phash_input)[:8, :8].reshape(-1)
    median = float(np.median(coefficients[1:]))
    phash = _hash_bits(coefficients > median)

    dhash_input = cv2.resize(content, (9, 8), interpolation=cv2.INTER_AREA)
    dhash = _hash_bits(dhash_input[:, 1:] > dhash_input[:, :-1])
    aspect = float(content.shape[1]) / float(max(1, content.shape[0]))
    return PerceptualSignature(phash=phash, dhash=dhash, content_aspect=aspect)


def _hamming(left: int, right: int) -> int:
    return int((left ^ right).bit_count())


def perceptually_same_plan(left: PerceptualSignature, right: PerceptualSignature) -> bool:
    aspect_delta = abs(left.content_aspect - right.content_aspect) / max(
        left.content_aspect,
        right.content_aspect,
        1e-6,
    )
    if aspect_delta > NEAR_DUPLICATE_ASPECT_DELTA_MAX:
        return False
    return (
        _hamming(left.phash, right.phash) <= NEAR_DUPLICATE_PHASH_DISTANCE_MAX
        and _hamming(left.dhash, right.dhash) <= NEAR_DUPLICATE_DHASH_DISTANCE_MAX
    )


def opaque_family_id(label: str, salt: str) -> str:
    digest = hashlib.sha256()
    digest.update(FAMILY_HASH_DOMAIN)
    digest.update(salt.encode("utf-8"))
    digest.update(b"\0")
    digest.update(label.encode("utf-8"))
    return "private:" + digest.hexdigest()[:32]


def opaque_sample_id(pixel_sha256: str, salt: str) -> str:
    digest = hashlib.sha256()
    digest.update(SAMPLE_HASH_DOMAIN)
    digest.update(salt.encode("utf-8"))
    digest.update(b"\0")
    digest.update(pixel_sha256.encode("ascii"))
    return "sample:" + digest.hexdigest()[:32]


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
        raise ValueError("a non-empty --salt is required so opaque IDs are corpus-specific")
    images = discover_images(image_root)
    families = load_family_map(families_path, images, image_root)

    seen_rasters: dict[str, pathlib.Path] = {}
    seen_sample_ids: set[str] = set()
    family_splits: dict[str, str] = {}
    records: list[ImageRecord] = []
    signatures: list[tuple[pathlib.Path, str, PerceptualSignature]] = []

    for source in images:
        relative = source.relative_to(image_root)
        image = canonical_rgb(source)
        pixel_sha256 = raster_digest(image)
        prior = seen_rasters.get(pixel_sha256)
        if prior is not None:
            raise RuntimeError(
                "duplicate canonical real-plan raster detected; renamed duplicates must not re-weight the "
                f"benchmark: {prior} and {relative}"
            )
        seen_rasters[pixel_sha256] = relative

        group = opaque_family_id(families[relative], salt)
        signature = perceptual_signature(image)
        if signature is not None:
            for prior_relative, prior_group, prior_signature in signatures:
                if prior_group == group:
                    continue
                if perceptually_same_plan(prior_signature, signature):
                    raise RuntimeError(
                        "near-duplicate real-plan rasters were assigned to different families; scans, "
                        "screenshots, crops, resized or recompressed variants of one house must share one "
                        f"private family label: {prior_relative} and {relative}"
                    )
            signatures.append((relative, group, signature))

        sample_id = opaque_sample_id(pixel_sha256, salt)
        if sample_id in seen_sample_ids:
            raise RuntimeError("opaque sample-id collision detected; refusing ambiguous real corpus")
        seen_sample_ids.add(sample_id)

        split = family_splits.setdefault(
            group,
            group_split(group, salt, validation_fraction=validation_fraction, test_fraction=test_fraction),
        )
        records.append(
            ImageRecord(
                relative=relative,
                npz_relative=relative.with_suffix(".npz"),
                width=int(image.shape[1]),
                height=int(image.shape[0]),
                pixel_sha256=pixel_sha256,
                sample_id=sample_id,
                source_group=group,
                split=split,
            )
        )

    split_counts = {name: 0 for name in ("train", "validation", "test")}
    group_counts: dict[str, set[str]] = {name: set() for name in ("train", "validation", "test")}
    for record in records:
        split_counts[record.split] += 1
        group_counts[record.split].add(record.source_group)

    source_groups = {
        "schema": 1,
        "format": "manzl-source-groups-local-v2",
        "privacy": {
            "localOnly": True,
            "safeToCommit": False,
            "containsRelativePaths": True,
            "rawFamilyLabelsStored": False,
        },
        "groups": {
            record.npz_relative.as_posix(): record.source_group
            for record in records
        },
    }

    manifest = {
        "schema": 2,
        "purpose": "private-held-out-real-floorplan-corpus",
        "privacy": {
            "sourceImagesCopied": False,
            "rawFamilyLabelsStored": False,
            "sourcePathsStored": False,
            "rawRasterHashesStored": False,
            "sourceGroupScheme": "sha256-domain-separated-corpus-salted-pseudonym",
            "sampleIdScheme": "sha256-domain-separated-corpus-salted-pseudonym",
            "safeToCommit": True,
        },
        "integrityPolicy": {
            "exactRasterDuplicatesRejected": True,
            "crossFamilyNearDuplicatesRejected": True,
            "nearDuplicateFingerprintsStored": False,
            "nearDuplicateGuard": "content-cropped-phash64-plus-dhash64-conservative-v1",
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
                "sampleId": record.sample_id,
                "width": record.width,
                "height": record.height,
                "sourceGroup": record.source_group,
                "split": record.split,
            }
            for record in records
        ],
    }
    return source_groups, manifest


def validate_holdout_coverage(manifest: dict) -> None:
    """Fail closed when requested held-out partitions contain no independent source families."""
    policy = manifest["splitPolicy"]
    groups = manifest["sourceGroupsBySplit"]
    required = ["train"]
    if float(policy["validationFraction"]) > 0.0:
        required.append("validation")
    if float(policy["testFraction"]) > 0.0:
        required.append("test")
    missing = [name for name in required if int(groups.get(name, 0)) <= 0]
    if missing:
        raise RuntimeError(
            "real-plan corpus is too small/unbalanced for the requested held-out split; "
            f"missing independent source groups in {missing}. Add more distinct plan families before benchmarking."
        )


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
    validate_holdout_coverage(manifest)
    write_json(args.output / "source-groups.local.json", source_groups)
    write_json(args.output / "split-manifest.json", manifest)
    print(json.dumps({
        "samples": manifest["samples"],
        "uniqueSourceGroups": manifest["uniqueSourceGroups"],
        "samplesBySplit": manifest["samplesBySplit"],
        "sourceGroupsBySplit": manifest["sourceGroupsBySplit"],
        "crossFamilyNearDuplicatesRejected": manifest["integrityPolicy"]["crossFamilyNearDuplicatesRejected"],
        "safeManifest": "split-manifest.json",
        "localOnlyManifest": "source-groups.local.json",
        "sourceImagesCopied": False,
        "rawFamilyLabelsStored": False,
        "sourcePathsStoredInSafeManifest": False,
        "rawRasterHashesStoredInSafeManifest": False,
    }, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
