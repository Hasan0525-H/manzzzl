#!/usr/bin/env python3
"""Re-key Manzl's privacy-safe real-corpus manifest to the exact teacher-aligned raster.

``prepare_private_real_corpus.py`` establishes family identity and held-out split assignment from the
user's private original files. Raster2Seq then applies the pinned ResizeAndPad transform to 512x512 and
that transformed raster becomes the exact source image shared by Raster2Seq, MitUNet and CubiCasa.
Because sample IDs are raster-derived, they MUST be recomputed after this transform before strict
consensus can be materialized into train/validation/test.

This bridge preserves only the already-opaque ``sourceGroup -> split`` policy from the original safe
manifest. It joins transformed ``*.png`` images to ``source-groups.local.json`` by the corresponding
``*.npz`` relative path, computes new corpus-salted sample IDs from the transformed pixels, and emits a
new privacy-safe schema-2 manifest. No private source path is written to the output.
"""

from __future__ import annotations

import argparse
import json
import pathlib

import cv2

import build_real_teacher_consensus as real_consensus
import prepare_private_real_corpus as private_corpus


def load_json(path: pathlib.Path) -> dict:
    if not path.is_file():
        raise FileNotFoundError(path)
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return payload


def load_group_splits(safe_manifest_path: pathlib.Path) -> dict[str, str]:
    payload = load_json(safe_manifest_path)
    if payload.get("schema") != 2:
        raise ValueError("original private split manifest must use schema 2")
    privacy = payload.get("privacy")
    if not isinstance(privacy, dict) or privacy.get("safeToCommit") is not True:
        raise ValueError("original split manifest must be privacy-safe")
    if privacy.get("sourcePathsStored") is not False or privacy.get("rawRasterHashesStored") is not False:
        raise ValueError("original split manifest leaks source paths or raw raster hashes")
    records = payload.get("records")
    if not isinstance(records, list) or not records:
        raise ValueError("original split manifest contains no records")

    result: dict[str, str] = {}
    for record in records:
        if not isinstance(record, dict):
            raise ValueError("split manifest record must be an object")
        group = record.get("sourceGroup")
        split = record.get("split")
        if not isinstance(group, str) or not group.startswith("private:"):
            raise ValueError("split manifest contains an invalid sourceGroup")
        if split not in ("train", "validation", "test"):
            raise ValueError(f"split manifest contains invalid split: {split!r}")
        previous = result.setdefault(group, split)
        if previous != split:
            raise RuntimeError(f"one sourceGroup crosses held-out splits: {group}")
    return result


def discover_teacher_images(root: pathlib.Path) -> dict[pathlib.Path, pathlib.Path]:
    if not root.is_dir():
        raise FileNotFoundError(f"teacher-aligned image root does not exist: {root}")
    result: dict[pathlib.Path, pathlib.Path] = {}
    for path in sorted(root.rglob("*.png")):
        if "jsons" in path.parts:
            continue
        relative_npz = path.relative_to(root).with_suffix(".npz")
        if relative_npz in result:
            raise RuntimeError(f"duplicate teacher-aligned sample path: {relative_npz}")
        result[relative_npz] = path
    if not result:
        raise RuntimeError(f"no teacher-aligned PNG rasters found under {root}")
    return result


def read_rgb(path: pathlib.Path):
    bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if bgr is None:
        raise ValueError(f"OpenCV could not decode teacher-aligned raster: {path}")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def rekey(
    teacher_image_root: pathlib.Path,
    source_groups_path: pathlib.Path,
    original_split_manifest_path: pathlib.Path,
    salt: str,
) -> dict:
    if not salt:
        raise ValueError("private corpus salt must be non-empty")
    images = discover_teacher_images(teacher_image_root)
    ordered_samples = sorted(images)
    local_groups = real_consensus.load_source_groups(source_groups_path, ordered_samples)
    group_splits = load_group_splits(original_split_manifest_path)

    unknown_groups = sorted(set(local_groups.values()) - set(group_splits))
    if unknown_groups:
        raise RuntimeError(f"teacher-aligned corpus contains source groups absent from split policy: {unknown_groups[:10]}")

    records = []
    seen_ids: set[str] = set()
    counts = {name: 0 for name in ("train", "validation", "test")}
    groups_by_split = {name: set() for name in ("train", "validation", "test")}
    for relative in ordered_samples:
        image = read_rgb(images[relative])
        digest = private_corpus.raster_digest(image)
        sample_id = private_corpus.opaque_sample_id(digest, salt)
        if sample_id in seen_ids:
            raise RuntimeError("duplicate transformed teacher raster detected after alignment")
        seen_ids.add(sample_id)
        group = local_groups[relative]
        split = group_splits[group]
        counts[split] += 1
        groups_by_split[split].add(group)
        records.append(
            {
                "sampleId": sample_id,
                "width": int(image.shape[1]),
                "height": int(image.shape[0]),
                "sourceGroup": group,
                "split": split,
            }
        )

    manifest = {
        "schema": 2,
        "purpose": "private-held-out-teacher-aligned-floorplan-corpus",
        "privacy": {
            "sourceImagesCopied": False,
            "rawFamilyLabelsStored": False,
            "sourcePathsStored": False,
            "rawRasterHashesStored": False,
            "sourceGroupScheme": "sha256-domain-separated-corpus-salted-pseudonym",
            "sampleIdScheme": "sha256-domain-separated-corpus-salted-pseudonym",
            "safeToCommit": True,
        },
        "alignment": {
            "sampleIdentityRaster": "pinned-Raster2Seq-ResizeAndPad-output-shared-by-all-teachers",
            "sourceGroupAndSplitInheritedFromOriginalPrivateManifest": True,
            "sampleIdsRecomputedAfterTeacherAlignment": True,
        },
        "splitPolicy": {
            "unit": "source_group",
            "deterministic": True,
            "sameFamilyCrossSplitAllowed": False,
        },
        "samples": len(records),
        "uniqueSourceGroups": len(set(local_groups.values())),
        "samplesBySplit": counts,
        "sourceGroupsBySplit": {name: len(groups) for name, groups in groups_by_split.items()},
        "records": records,
    }
    return manifest


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--teacher-images", type=pathlib.Path, required=True)
    parser.add_argument("--source-groups", type=pathlib.Path, required=True)
    parser.add_argument("--original-split-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--salt", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    manifest = rekey(
        teacher_image_root=args.teacher_images,
        source_groups_path=args.source_groups,
        original_split_manifest_path=args.original_split_manifest,
        salt=args.salt,
    )
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(manifest, indent=2, sort_keys=True), encoding="utf-8")
    print(json.dumps({
        "samples": manifest["samples"],
        "uniqueSourceGroups": manifest["uniqueSourceGroups"],
        "samplesBySplit": manifest["samplesBySplit"],
        "sampleIdsRecomputedAfterTeacherAlignment": True,
        "safeToCommit": True,
    }, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
