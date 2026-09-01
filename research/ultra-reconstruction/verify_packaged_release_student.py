#!/usr/bin/env python3
"""Verify the packaged Manzl student is an actual measured release model.

This is the APK boundary. A packaged student is accepted only when the app assets contain the exact
ONNX artifact referenced by the final real-student release bundle produced after a release-scale real
benchmark, locked relative semantic acceptance, immutable absolute semantic quality, and end-to-end
geometry PASS. The verifier never upgrades an artifact or infers readiness from a training file.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib

MODEL_NAME = "manzl_reconstruction_student.onnx"
RELEASE_NAME = "manzl_reconstruction_student.release.json"
TRAINING_NAME = "manzl_reconstruction_student.training.json"
MANIFEST_NAME = "manifest.json"


def _load_json(path: pathlib.Path, label: str) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"{label} is missing: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must be a JSON object")
    return payload


def verify(asset_root: pathlib.Path) -> dict:
    asset_root = asset_root.resolve()
    model = asset_root / MODEL_NAME
    release_path = asset_root / RELEASE_NAME
    training_path = asset_root / TRAINING_NAME
    manifest_path = asset_root / MANIFEST_NAME

    if not model.is_file() or model.stat().st_size <= 100_000:
        raise RuntimeError("release APK requires a non-trivial packaged reconstruction student ONNX")
    release = _load_json(release_path, "student release evidence")
    training = _load_json(training_path, "student training provenance")
    manifest = _load_json(manifest_path, "runtime model manifest")

    digest = hashlib.sha256(model.read_bytes()).hexdigest()
    size = model.stat().st_size
    release_required = {
        "schema": 2,
        "pipeline": "manzl-real-student-release-evidence-bundle",
        "model": MODEL_NAME,
        "sha256": digest,
        "bytes": size,
        "trainingAttestationVerified": True,
        "candidateArtifactIntegrityPassed": True,
        "heldOutCorpusIdentityMatchedAcrossEvidence": True,
        "releaseCorpusScalePassed": True,
        "releaseCorpusScalePolicyVersion": 1,
        "releaseCorpusScaleRecomputedAtFinalize": True,
        "semanticAcceptancePolicyLocked": True,
        "semanticAcceptancePolicyEvaluated": True,
        "relativeSemanticAcceptancePassed": True,
        "absoluteSemanticQualityPassed": True,
        "absoluteSemanticQualityFloorVersion": 1,
        "semanticEvidenceRecomputedAtFinalize": True,
        "semanticAcceptancePassed": True,
        "semanticHeldOutMeasurementCompleted": True,
        "geometryReleaseEvidencePassed": True,
        "allEvidenceBoundToExactModelDigest": True,
        "releaseEvidenceBundleComplete": True,
        "releaseReady": True,
        "blockingReason": None,
    }
    for key, expected in release_required.items():
        if release.get(key) != expected:
            raise RuntimeError(
                f"packaged student release contract failed for {key}: "
                f"{release.get(key)!r} != {expected!r}"
            )

    training_required = {
        "schema": 1,
        "pipeline": "manzl-private-real-student-release-candidate",
        "model": MODEL_NAME,
        "sha256": digest,
        "bytes": size,
        "trainingSplit": "train",
        "modelSelectionSplit": "validation",
        "testSplitPresentAndVerified": True,
        "testUsedForTraining": False,
        "testUsedForModelSelection": False,
        "testUsedForValidationMetrics": False,
        "testReservedForFinalEvaluation": True,
        "realTrainingPreflightPassed": True,
        "releaseReady": False,
    }
    for key, expected in training_required.items():
        if training.get(key) != expected:
            raise RuntimeError(
                f"packaged student training provenance failed for {key}: "
                f"{training.get(key)!r} != {expected!r}"
            )

    required_models = manifest.get("required")
    if not isinstance(required_models, list):
        raise ValueError("runtime model manifest required list is missing")
    student = next(
        (item for item in required_models if isinstance(item, dict) and item.get("id") == "manzl_reconstruction_student"),
        None,
    )
    if student is None:
        raise RuntimeError("runtime model manifest does not declare the reconstruction student")
    manifest_required = {
        "path": f"models/{MODEL_NAME}",
        "status": "real-held-out-release-ready",
        "sha256": digest,
        "bytes": size,
        "releaseReady": True,
        "releaseEvidence": f"models/{RELEASE_NAME}",
        "trainingProvenance": f"models/{TRAINING_NAME}",
        "semanticQualityFloorVersion": 1,
        "releaseCorpusScalePolicyVersion": 1,
    }
    for key, expected in manifest_required.items():
        if student.get(key) != expected:
            raise RuntimeError(
                f"runtime manifest student contract failed for {key}: "
                f"{student.get(key)!r} != {expected!r}"
            )

    policy = manifest.get("policy")
    if not isinstance(policy, dict):
        raise ValueError("runtime model manifest policy is missing")
    for key, expected in {
        "networkAtRuntime": False,
        "paidApiFallback": False,
        "silentQualityDowngrade": False,
    }.items():
        if policy.get(key) != expected:
            raise RuntimeError(f"runtime model policy changed unexpectedly for {key}")

    return {
        "schema": 1,
        "pipeline": "manzl-packaged-real-student-apk-gate",
        "model": MODEL_NAME,
        "sha256": digest,
        "bytes": size,
        "releaseCorpusScalePassed": True,
        "releaseCorpusScalePolicyVersion": 1,
        "releaseCorpusScaleRecomputedAtFinalize": True,
        "relativeSemanticAcceptancePassed": True,
        "absoluteSemanticQualityPassed": True,
        "absoluteSemanticQualityFloorVersion": 1,
        "semanticEvidenceRecomputedAtFinalize": True,
        "semanticAcceptancePassed": True,
        "geometryReleaseEvidencePassed": True,
        "releaseEvidenceBundleVerified": True,
        "runtimeManifestVerified": True,
        "releaseReady": True,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--assets",
        type=pathlib.Path,
        default=pathlib.Path("manzl-app/src/main/assets/models"),
    )
    return parser.parse_args()


def main() -> int:
    report = verify(parse_args().assets)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
