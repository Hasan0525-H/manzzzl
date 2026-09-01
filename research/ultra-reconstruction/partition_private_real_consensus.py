#!/usr/bin/env python3
"""Partition a private Manzl real-plan consensus corpus by opaque floor-plan family.

Inputs are deliberately split by sensitivity:
* ``source-groups.local.json``: local-only mapping from consensus NPZ relative path to opaque family.
* ``split-manifest.json``: privacy-safe metadata mapping opaque families to train/validation/test.
* strict consensus NPZ root produced by ``build_real_teacher_consensus.py``.

The script verifies exact sample-set coverage and verifies every NPZ's embedded ``source_group`` before
copying it into train/validation/test. No sample is assigned by filename. A family may occur in exactly
one split. Pairwise train/validation/test leakage checks are run after partitioning.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import shutil

import numpy as np

import verify_dataset_split

VALID_SPLITS = ("train", "validation", "test")


def load_json(path: pathlib.Path) -> dict:
    if not path.is_file():
        raise FileNotFoundError(path)
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"JSON root must be an object: {path}")
    return payload


def safe_relative(raw: str) -> pathlib.Path:
    if not isinstance(raw, str) or not raw.strip():
        raise ValueError("sample path must be a non-empty string")
    pure = pathlib.PurePosixPath(raw.strip())
    if pure.is_absolute() or ".." in pure.parts:
        raise ValueError(f"unsafe consensus relative path: {raw!r}")
    if pure.suffix.lower() != ".npz":
        raise ValueError(f"consensus sample must be an NPZ path: {raw!r}")
    return pathlib.Path(*pure.parts)


def load_local_groups(path: pathlib.Path) -> dict[pathlib.Path, str]:
    payload = load_json(path)
    privacy = payload.get("privacy")
    if isinstance(privacy, dict):
        if privacy.get("localOnly") is not True or privacy.get("safeToCommit") is not False:
            raise ValueError("source-group operational manifest must declare localOnly=true and safeToCommit=false")
    groups = payload.get("groups")
    if not isinstance(groups, dict) or not groups:
        raise ValueError("source-group operational manifest must contain non-empty groups")
    result: dict[pathlib.Path, str] = {}
    for raw_relative, raw_group in groups.items():
        relative = safe_relative(raw_relative)
        if relative in result:
            raise ValueError(f"duplicate normalized consensus path: {raw_relative!r}")
        if not isinstance(raw_group, str) or not raw_group.startswith("private:"):
            raise ValueError(f"source_group must be an opaque private:* id for {raw_relative!r}")
        result[relative] = raw_group
    return result


def load_group_splits(path: pathlib.Path) -> dict[str, str]:
    payload = load_json(path)
    privacy = payload.get("privacy")
    if not isinstance(privacy, dict) or privacy.get("safeToCommit") is not True:
        raise ValueError("split manifest must declare privacy.safeToCommit=true")
    records = payload.get("records")
    if not isinstance(records, list) or not records:
        raise ValueError("split manifest must contain non-empty records")
    group_splits: dict[str, str] = {}
    for record in records:
        if not isinstance(record, dict):
            raise ValueError("split manifest record must be an object")
        group = record.get("sourceGroup")
        split = record.get("split")
        if not isinstance(group, str) or not group.startswith("private:"):
            raise ValueError("split manifest contains invalid opaque sourceGroup")
        if split not in VALID_SPLITS:
            raise ValueError(f"invalid real-corpus split for {group}: {split!r}")
        prior = group_splits.setdefault(group, split)
        if prior != split:
            raise RuntimeError(f"one source_group is assigned to multiple splits: {group}")
    return group_splits


def discover_consensus(root: pathlib.Path) -> set[pathlib.Path]:
    if not root.is_dir():
        raise FileNotFoundError(f"consensus root does not exist: {root}")
    files = {path.relative_to(root) for path in root.rglob("*.npz")}
    if not files:
        raise RuntimeError(f"consensus root has no NPZ files: {root}")
    return files


def embedded_source_group(path: pathlib.Path) -> str:
    with np.load(path, allow_pickle=False) as sample:
        if "source_group" not in sample.files:
            raise ValueError(f"strict real consensus is missing source_group: {path}")
        raw = np.asarray(sample["source_group"])
        if raw.size != 1:
            raise ValueError(f"source_group must be scalar in {path}")
        value = raw.reshape(()).item()
        if isinstance(value, bytes):
            value = value.decode("utf-8")
        if not isinstance(value, str) or not value.startswith("private:"):
            raise ValueError(f"invalid embedded source_group in {path}")
        return value


def verify_exact_coverage(consensus_root: pathlib.Path, local_groups: dict[pathlib.Path, str]) -> list[pathlib.Path]:
    consensus = discover_consensus(consensus_root)
    expected = set(local_groups)
    if consensus != expected:
        missing = sorted(path.as_posix() for path in expected - consensus)[:10]
        extra = sorted(path.as_posix() for path in consensus - expected)[:10]
        raise RuntimeError(
            "real consensus and local source-group manifest must cover the exact same NPZ set; "
            f"missing={missing} extra={extra}"
        )
    return sorted(consensus)


def partition(
    consensus_root: pathlib.Path,
    local_groups_path: pathlib.Path,
    split_manifest_path: pathlib.Path,
    output_root: pathlib.Path,
) -> dict:
    local_groups = load_local_groups(local_groups_path)
    group_splits = load_group_splits(split_manifest_path)
    samples = verify_exact_coverage(consensus_root, local_groups)

    missing_groups = sorted(set(local_groups.values()) - set(group_splits))
    if missing_groups:
        raise RuntimeError(f"split manifest is missing source groups: {missing_groups[:10]}")

    if output_root.exists():
        shutil.rmtree(output_root)
    for split in VALID_SPLITS:
        (output_root / split).mkdir(parents=True, exist_ok=True)

    counts = {split: 0 for split in VALID_SPLITS}
    groups_by_split = {split: set() for split in VALID_SPLITS}
    for relative in samples:
        source = consensus_root / relative
        expected_group = local_groups[relative]
        actual_group = embedded_source_group(source)
        if actual_group != expected_group:
            raise RuntimeError(
                f"embedded source_group does not match local manifest for {relative}: "
                f"{actual_group!r} != {expected_group!r}"
            )
        split = group_splits[actual_group]
        destination = output_root / split / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        counts[split] += 1
        groups_by_split[split].add(actual_group)

    active = [split for split in VALID_SPLITS if counts[split] > 0]
    if "train" not in active:
        raise RuntimeError("real consensus partition contains no train samples")
    for split in active:
        if not groups_by_split[split]:
            raise RuntimeError(f"split has samples but no source groups: {split}")

    pairwise = {}
    for index, left in enumerate(active):
        for right in active[index + 1:]:
            key = f"{left}_vs_{right}"
            pairwise[key] = verify_dataset_split.verify_split_independence(
                output_root / left,
                output_root / right,
            )

    return {
        "schema": 1,
        "samples": sum(counts.values()),
        "samplesBySplit": counts,
        "sourceGroupsBySplit": {split: len(groups) for split, groups in groups_by_split.items()},
        "pairwiseLeakageChecks": pairwise,
        "assignmentAuthority": "opaque source_group only; filenames never select split",
        "passed": True,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--consensus", type=pathlib.Path, required=True)
    parser.add_argument("--source-groups", type=pathlib.Path, required=True)
    parser.add_argument("--split-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--report", type=pathlib.Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = partition(args.consensus, args.source_groups, args.split_manifest, args.output)
    text = json.dumps(report, indent=2, sort_keys=True)
    if args.report is not None:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
