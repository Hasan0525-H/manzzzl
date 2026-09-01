#!/usr/bin/env python3
"""Immutable minimum real-corpus scale policy for a Manzl student release.

Semantic and geometry metrics are not credible when measured on a tiny number of underlying homes. The
release bundle therefore requires a minimum number of independent ``source_group`` families in every
partition, in addition to the existing leakage, semantic and geometry gates. Held-out partitions also
limit repeated variants per family so one heavily photographed/scanned house cannot dominate metrics.

These values are a versioned product-quality floor. Changing them requires a new policy version and a
new release evidence bundle; existing releases must not silently inherit changed criteria.
"""

from __future__ import annotations

import json

POLICY_VERSION = 1
MIN_SOURCE_GROUPS = {
    "train": 120,
    "validation": 30,
    "test": 40,
}
MIN_SAMPLES = {
    "train": 120,
    "validation": 30,
    "test": 40,
}
MAX_HELDOUT_SAMPLES_PER_GROUP = {
    "validation": 3,
    "test": 3,
}
MAX_HELDOUT_MEAN_SAMPLES_PER_GROUP = {
    "validation": 2.0,
    "test": 2.0,
}


def _integer(preflight: dict, key: str) -> int:
    value = preflight.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"real-corpus scale input {key} must be a non-negative integer")
    return value


def evaluate(preflight: dict) -> dict:
    if preflight.get("schema") != 2 or preflight.get("pipeline") != "private-real-release-training-input-gate":
        raise ValueError("release corpus scale requires verified private-real training-input evidence")
    if preflight.get("passed") is not True or preflight.get("testReservedForFinalEvaluation") is not True:
        raise ValueError("release corpus scale requires a passing held-out split preflight")

    checks: dict[str, dict] = {}
    passed = True
    for split in ("train", "validation", "test"):
        groups = _integer(preflight, f"{split}SourceGroups")
        samples = _integer(preflight, f"{split}Samples")
        group_floor = MIN_SOURCE_GROUPS[split]
        sample_floor = MIN_SAMPLES[split]
        group_pass = groups >= group_floor
        sample_pass = samples >= sample_floor
        checks[f"{split}:sourceGroups"] = {
            "actual": groups,
            "minimum": group_floor,
            "passed": group_pass,
        }
        checks[f"{split}:samples"] = {
            "actual": samples,
            "minimum": sample_floor,
            "passed": sample_pass,
        }
        passed = passed and group_pass and sample_pass

    density = preflight.get("variantDensityBySplit")
    if not isinstance(density, dict):
        raise ValueError("release corpus scale requires measured variantDensityBySplit evidence")
    for split in ("validation", "test"):
        item = density.get(split)
        if not isinstance(item, dict):
            raise ValueError(f"release corpus scale is missing variant density for {split}")
        maximum = item.get("maximumSamplesPerSourceGroup")
        mean = item.get("meanSamplesPerSourceGroup")
        if isinstance(maximum, bool) or not isinstance(maximum, int) or maximum <= 0:
            raise ValueError(f"invalid maximumSamplesPerSourceGroup for {split}")
        if isinstance(mean, bool) or not isinstance(mean, (int, float)) or float(mean) <= 0.0:
            raise ValueError(f"invalid meanSamplesPerSourceGroup for {split}")
        max_allowed = MAX_HELDOUT_SAMPLES_PER_GROUP[split]
        mean_allowed = MAX_HELDOUT_MEAN_SAMPLES_PER_GROUP[split]
        max_pass = maximum <= max_allowed
        mean_pass = float(mean) <= mean_allowed + 1e-12
        checks[f"{split}:maximumSamplesPerSourceGroup"] = {
            "actual": maximum,
            "maximum": max_allowed,
            "passed": max_pass,
        }
        checks[f"{split}:meanSamplesPerSourceGroup"] = {
            "actual": float(mean),
            "maximum": mean_allowed,
            "passed": mean_pass,
        }
        passed = passed and max_pass and mean_pass

    return {
        "schema": 1,
        "pipeline": "manzl-real-corpus-release-scale-gate",
        "policyVersion": POLICY_VERSION,
        "minimumIndependentSourceGroups": MIN_SOURCE_GROUPS,
        "minimumSamples": MIN_SAMPLES,
        "maximumHeldOutSamplesPerSourceGroup": MAX_HELDOUT_SAMPLES_PER_GROUP,
        "maximumHeldOutMeanSamplesPerSourceGroup": MAX_HELDOUT_MEAN_SAMPLES_PER_GROUP,
        "checks": checks,
        "releaseCorpusScalePassed": bool(passed),
        "releaseReady": False,
    }


def require(preflight: dict) -> dict:
    report = evaluate(preflight)
    if report["releaseCorpusScalePassed"] is not True:
        failed = [name for name, check in report["checks"].items() if check.get("passed") is not True]
        raise RuntimeError(
            "real-plan corpus is below the immutable release benchmark scale; "
            f"failed={failed}. Add more independent homes or rebalance held-out variants before release."
        )
    return report


def main() -> int:
    raise SystemExit("release_corpus_scale.py is a library gate; call it from the verified release pipeline")


if __name__ == "__main__":
    main()
