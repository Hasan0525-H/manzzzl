#!/usr/bin/env python3
"""Materialize private real-plan consensus into leakage-safe train/validation/test directories.

Inputs remain local/private:
* the strict consensus NPZ tree (which may still use private relative filenames),
* ``source-groups.local.json`` from ``prepare_private_real_corpus.py``, and
* the private corpus salt.

The safe ``split-manifest.json`` contains only opaque sample/family IDs. This bridge recomputes each
sample ID from the consensus source raster, proves the NPZ's embedded ``source_group`` agrees with the
local operational manifest, then writes opaque filenames into train/validation/test. The test partition
is materialized but is never an input to the trainer; it is reserved for final held-out evaluation.

No network code is used and no private source path is written to the output report.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import shutil

import numpy as np

import build_real_teacher_consensus as real_consensus
import prepare_private_real_corpus as private_corpus

SPLITS = ("train", "validation", "test")


def load_safe_manifest(path: pathlib.Path) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"safe split manifest does not exist: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict) or payload.get("schema") != 2:
        raise ValueError("safe split manifest must use schema 2")
    privacy = payload.get("privacy")
    if not isinstance(privacy, dict):
        raise ValueError("safe split manifest is missing privacy metadata")
    required_privacy = {
        "safeToCommit": True,
        "sourcePathsStored": False,
        "rawRasterHashesStored": False,
        "rawFamilyLabelsStored": False,
        "sourceImagesCopied": False,
    }
    for key, expected in required_privacy.items():
        if privacy.get(key) is not expected:
            raise ValueError(f"safe split manifest privacy contract failed for {key}")
    records = payload.get("records")
    if not isinstance(records, list) or not records:
        raise ValueError("safe split manifest must contain records")
    return payload


def normalize_scalar_text(value: np.ndarray, field: str, source: pathlib.Path) -> str:
    array = np.asarray(value)
    if array.size != 1:
        raise ValueError(f"{field} must be scalar in {source}")
    text = str(array.reshape(-1)[0]).strip()
    if not text:
        raise ValueError(f"{field} must be non-empty in {source}")
    return text


def parse_manifest_records(manifest: dict) -> dict[str, dict]:
    records: dict[str, dict] = {}
    group_split: dict[str, str] = {}
    for raw in manifest["records"]:
        if not isinstance(raw, dict):
            raise ValueError("safe split manifest record must be an object")
        allowed = {"sampleId", "width", "height", "sourceGroup", "split"}
        if set(raw) != allowed:
            raise ValueError(f"safe split manifest record has unexpected fields: {sorted(set(raw) - allowed)}")
        sample_id = raw.get("sampleId")
        group = raw.get("sourceGroup")
        split = raw.get("split")
        if not isinstance(sample_id, str) or not sample_id.startswith("sample:"):
            raise ValueError("safe split manifest contains an invalid opaque sampleId")
        if sample_id in records:
            raise ValueError(f"duplicate sampleId in safe split manifest: {sample_id}")
        if not isinstance(group, str) or not group.startswith("private:"):
            raise ValueError(f"invalid sourceGroup for {sample_id}")
        if split not in SPLITS:
            raise ValueError(f"invalid split for {sample_id}: {split!r}")
        prior_split = group_split.setdefault(group, split)
        if prior_split != split:
            raise RuntimeError(f"source family crosses held-out splits: {group}")
        width = raw.get("width")
        height = raw.get("height")
        if not isinstance(width, int) or width <= 0 or not isinstance(height, int) or height <= 0:
            raise ValueError(f"invalid dimensions for {sample_id}")
        records[sample_id] = raw
    return records


def opaque_filename(sample_id: str) -> str:
    # ':' is legal in JSON IDs but not in Windows filenames.
    prefix, separator, digest = sample_id.partition(":")
    if prefix != "sample" or separator != ":" or len(digest) != 32:
        raise ValueError(f"invalid sampleId for filename: {sample_id!r}")
    if any(character not in "0123456789abcdef" for character in digest):
        raise ValueError(f"sampleId digest must be lowercase hex: {sample_id!r}")
    return f"sample-{digest}.npz"


def validate_manifest_counts(manifest: dict, records: dict[str, dict]) -> None:
    actual_samples = {split: 0 for split in SPLITS}
    actual_groups = {split: set() for split in SPLITS}
    for record in records.values():
        split = record["split"]
        actual_samples[split] += 1
        actual_groups[split].add(record["sourceGroup"])

    if int(manifest.get("samples", -1)) != len(records):
        raise RuntimeError("safe split manifest sample total is inconsistent with records")
    if int(manifest.get("uniqueSourceGroups", -1)) != len({record["sourceGroup"] for record in records.values()}):
        raise RuntimeError("safe split manifest source-group total is inconsistent with records")
    expected_samples = manifest.get("samplesBySplit")
    expected_groups = manifest.get("sourceGroupsBySplit")
    if expected_samples != actual_samples:
        raise RuntimeError(f"safe split manifest sample counts are inconsistent: {expected_samples} != {actual_samples}")
    measured_groups = {split: len(groups) for split, groups in actual_groups.items()}
    if expected_groups != measured_groups:
        raise RuntimeError(f"safe split manifest group counts are inconsistent: {expected_groups} != {measured_groups}")


def materialize(
    consensus_root: pathlib.Path,
    source_groups_path: pathlib.Path,
    split_manifest_path: pathlib.Path,
    salt: str,
    output_root: pathlib.Path,
) -> dict:
    if not salt:
        raise ValueError("private corpus salt must be non-empty")
    manifest = load_safe_manifest(split_manifest_path)
    manifest_records = parse_manifest_records(manifest)
    validate_manifest_counts(manifest, manifest_records)

    samples = sorted(real_consensus.discover_npz(consensus_root))
    local_groups = real_consensus.load_source_groups(source_groups_path, samples)

    if output_root.resolve() == consensus_root.resolve():
        raise ValueError("output root must differ from consensus root")
    if output_root.exists():
        shutil.rmtree(output_root)
    for split in SPLITS:
        (output_root / split).mkdir(parents=True, exist_ok=True)

    matched: set[str] = set()
    actual_counts = {split: 0 for split in SPLITS}
    groups_by_split = {split: set() for split in SPLITS}

    for relative in samples:
        source = consensus_root / relative
        with np.load(source, allow_pickle=False) as sample:
            if "image" not in sample.files:
                raise ValueError(f"consensus sample is missing image: {relative}")
            if "source_group" not in sample.files:
                raise ValueError(f"consensus sample is missing source_group: {relative}")
            image = real_consensus.normalize_image_for_comparison(np.asarray(sample["image"]), source)
            embedded_group = normalize_scalar_text(sample["source_group"], "source_group", source)

        expected_local_group = local_groups[relative]
        if embedded_group != expected_local_group:
            raise RuntimeError(
                f"consensus source_group disagrees with local provenance for {relative}; "
                "refusing a potentially leaked/misjoined sample"
            )

        pixel_sha256 = private_corpus.raster_digest(image)
        sample_id = private_corpus.opaque_sample_id(pixel_sha256, salt)
        record = manifest_records.get(sample_id)
        if record is None:
            raise RuntimeError(f"consensus sample is absent from safe split manifest: {sample_id}")
        if sample_id in matched:
            raise RuntimeError(f"duplicate consensus raster/sampleId detected: {sample_id}")
        matched.add(sample_id)

        if record["sourceGroup"] != embedded_group:
            raise RuntimeError(f"safe manifest sourceGroup mismatch for {sample_id}")
        if int(record["width"]) != int(image.shape[1]) or int(record["height"]) != int(image.shape[0]):
            raise RuntimeError(f"safe manifest dimensions mismatch for {sample_id}")

        split = record["split"]
        destination = output_root / split / opaque_filename(sample_id)
        shutil.copy2(source, destination)
        actual_counts[split] += 1
        groups_by_split[split].add(embedded_group)

    missing = set(manifest_records) - matched
    if missing:
        raise RuntimeError(f"safe split manifest contains samples absent from consensus: {sorted(missing)[:10]}")

    # Final independent family-leakage assertion on the materialized result.
    for first_index, first in enumerate(SPLITS):
        for second in SPLITS[first_index + 1:]:
            overlap = groups_by_split[first] & groups_by_split[second]
            if overlap:
                raise RuntimeError(f"source families leaked across {first}/{second}: {sorted(overlap)[:10]}")

    if actual_counts != manifest["samplesBySplit"]:
        raise RuntimeError(f"materialized split counts mismatch: {actual_counts} != {manifest['samplesBySplit']}")

    report = {
        "schema": 1,
        "pipeline": "private-real-consensus-to-held-out-splits",
        "samples": len(matched),
        "samplesBySplit": actual_counts,
        "sourceGroupsBySplit": {split: len(groups_by_split[split]) for split in SPLITS},
        "privatePathsStored": False,
        "rawRasterHashesStored": False,
        "opaqueOutputFilenames": True,
        "testReservedForFinalEvaluation": True,
        "releaseReady": False,
        "reason": "Materialization proves provenance/split integrity only; measured held-out reconstruction gates must still pass.",
    }
    (output_root / "materialization_report.json").write_text(
        json.dumps(report, indent=2, sort_keys=True), encoding="utf-8"
    )
    return report


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--consensus", type=pathlib.Path, required=True)
    parser.add_argument("--source-groups", type=pathlib.Path, required=True)
    parser.add_argument("--split-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--salt", required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = materialize(
        consensus_root=args.consensus,
        source_groups_path=args.source_groups,
        split_manifest_path=args.split_manifest,
        salt=args.salt,
        output_root=args.output,
    )
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
