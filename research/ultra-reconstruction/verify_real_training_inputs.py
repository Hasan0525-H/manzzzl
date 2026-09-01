#!/usr/bin/env python3
"""Verify Manzl's private real-plan splits before release-candidate student training.

This gate is intentionally stricter than the generic dataset split checker. A release-candidate training
run must consume the exact transactional output of ``materialize_private_real_splits.py`` and must keep
three independent partitions:

* ``train``: the only split used for gradient updates.
* ``validation``: model selection / early stopping only.
* ``test``: reserved and untouched until final held-out evaluation.

The materialization report is treated as a provenance contract, not as evidence of model quality. This
script re-measures sample/family counts and pairwise leakage from the NPZ files themselves, requires
opaque output filenames, and fails closed if the report or directory layout is inconsistent.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re

import verify_dataset_split

SPLITS = ("train", "validation", "test")
OPAQUE_SAMPLE = re.compile(r"^sample-[0-9a-f]{32}\.npz$")


def load_report(split_root: pathlib.Path) -> dict:
    report_path = split_root / "materialization_report.json"
    if not report_path.is_file():
        raise FileNotFoundError(f"materialization report is missing: {report_path}")
    payload = json.loads(report_path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("materialization report must be a JSON object")
    required = {
        "schema": 2,
        "pipeline": "private-real-consensus-to-held-out-splits",
        "splitAssignmentsReverifiedFromSaltAndPolicy": True,
        "privatePathsStored": False,
        "rawRasterHashesStored": False,
        "opaqueOutputFilenames": True,
        "transactionalMaterialization": True,
        "existingOutputOverwritten": False,
        "testReservedForFinalEvaluation": True,
        "releaseReady": False,
    }
    for key, expected in required.items():
        if payload.get(key) != expected:
            raise ValueError(
                f"materialization report contract failed for {key}: "
                f"{payload.get(key)!r} != {expected!r}"
            )
    if not isinstance(payload.get("splitPolicy"), dict):
        raise ValueError("materialization report is missing splitPolicy")
    return payload


def discover_split(root: pathlib.Path, name: str) -> list[pathlib.Path]:
    split = root / name
    if not split.is_dir():
        raise FileNotFoundError(f"required real-plan split directory is missing: {split}")
    nested = [path for path in split.rglob("*.npz") if path.parent != split]
    if nested:
        raise RuntimeError(
            f"release real-plan split must contain only opaque flat NPZ files; nested sample found in {name}"
        )
    files = sorted(split.glob("*.npz"))
    if not files:
        raise RuntimeError(f"release real-plan split contains no samples: {name}")
    invalid = [path.name for path in files if OPAQUE_SAMPLE.fullmatch(path.name) is None]
    if invalid:
        raise RuntimeError(
            f"release real-plan split contains non-opaque filenames in {name}: {invalid[:8]}"
        )
    if len({path.name for path in files}) != len(files):
        raise RuntimeError(f"duplicate opaque filenames detected in {name}")
    return files


def measured_group_count(root: pathlib.Path) -> int:
    _, _, groups = verify_dataset_split.index_split(root)
    if not groups:
        raise RuntimeError(f"real-plan split has no source_group provenance: {root.name}")
    return len(groups)


def verify(split_root: pathlib.Path) -> dict:
    split_root = split_root.resolve()
    report = load_report(split_root)
    files = {name: discover_split(split_root, name) for name in SPLITS}

    measured_samples = {name: len(files[name]) for name in SPLITS}
    expected_samples = report.get("samplesBySplit")
    if measured_samples != expected_samples:
        raise RuntimeError(
            f"real-plan split sample counts disagree with materialization report: "
            f"{measured_samples} != {expected_samples}"
        )
    if int(report.get("samples", -1)) != sum(measured_samples.values()):
        raise RuntimeError("materialization report total sample count is inconsistent")

    measured_groups = {
        name: measured_group_count(split_root / name)
        for name in SPLITS
    }
    expected_groups = report.get("sourceGroupsBySplit")
    if measured_groups != expected_groups:
        raise RuntimeError(
            f"real-plan source-group counts disagree with materialization report: "
            f"{measured_groups} != {expected_groups}"
        )

    pairwise = {}
    for left_index, left in enumerate(SPLITS):
        for right in SPLITS[left_index + 1:]:
            pairwise[f"{left}_vs_{right}"] = verify_dataset_split.verify_split_independence(
                split_root / left,
                split_root / right,
            )

    all_names = [path.name for name in SPLITS for path in files[name]]
    if len(all_names) != len(set(all_names)):
        raise RuntimeError("one opaque sampleId/filename appears in more than one held-out split")

    return {
        "schema": 1,
        "pipeline": "private-real-release-training-input-gate",
        "splitRoot": split_root.name,
        "trainSamples": measured_samples["train"],
        "validationSamples": measured_samples["validation"],
        "testSamples": measured_samples["test"],
        "trainSourceGroups": measured_groups["train"],
        "validationSourceGroups": measured_groups["validation"],
        "testSourceGroups": measured_groups["test"],
        "pairwiseLeakageChecks": pairwise,
        "trainerMayRead": ["train", "validation"],
        "trainerMustNotRead": ["test"],
        "testReservedForFinalEvaluation": True,
        "materializationContractVerified": True,
        "passed": True,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--splits", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = verify(args.splits)
    text = json.dumps(report, indent=2, sort_keys=True)
    if args.output is not None:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(text, encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
