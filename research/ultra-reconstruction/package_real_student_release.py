#!/usr/bin/env python3
"""Package one fully measured Manzl real-plan student into Android assets.

Packaging is strictly downstream of final release evidence. It never promotes a model. The candidate
ONNX, training provenance and final evidence bundle are staged and verified before app assets change.
The release and manifest carry the aggregate held-out NPZ artifact fingerprint, corpus-scale policy and
semantic quality-floor versions so Android can fail closed on legacy or mismatched evidence.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import tempfile

import verify_packaged_release_student as packaged_verifier

MODEL_NAME = packaged_verifier.MODEL_NAME
TRAINING_NAME = packaged_verifier.TRAINING_NAME
RELEASE_NAME = packaged_verifier.RELEASE_NAME
MANIFEST_NAME = packaged_verifier.MANIFEST_NAME
STALE_QUALITY_NAME = "manzl_reconstruction_student.quality.json"
HEX64 = re.compile(r"^[0-9a-f]{64}$")


def _load_json(path: pathlib.Path, label: str) -> dict:
    if not path.is_file():
        raise FileNotFoundError(f"{label} is missing: {path}")
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError(f"{label} must be a JSON object")
    return payload


def _digest_field(payload: dict, key: str, label: str) -> str:
    value = payload.get(key)
    if not isinstance(value, str) or HEX64.fullmatch(value) is None:
        raise RuntimeError(f"{label} contains invalid {key}")
    return value


def _candidate_contract(candidate: pathlib.Path) -> tuple[pathlib.Path, pathlib.Path, dict, str, int]:
    candidate = candidate.resolve()
    model = candidate / MODEL_NAME
    training_path = candidate / "real-training-attestation.json"
    training = _load_json(training_path, "real-training attestation")
    if not model.is_file() or model.stat().st_size <= 100_000:
        raise RuntimeError("release candidate must contain a non-trivial student ONNX")
    digest = hashlib.sha256(model.read_bytes()).hexdigest()
    size = model.stat().st_size
    required = {
        "schema": 1,
        "pipeline": "manzl-private-real-student-release-candidate",
        "model": MODEL_NAME,
        "sha256": digest,
        "bytes": size,
        "trainingSplit": "train",
        "modelSelectionSplit": "validation",
        "validationMetricsExactSplitCoverage": True,
        "releaseCorpusScalePreflightPassed": True,
        "releaseCorpusScalePolicyVersion": 1,
        "splitArtifactsStableAcrossTraining": True,
        "artifactFingerprintIsAggregateOnly": True,
        "perSampleContentHashesStored": False,
        "testSplitPresentAndVerified": True,
        "testUsedForTraining": False,
        "testUsedForModelSelection": False,
        "testUsedForValidationMetrics": False,
        "testReservedForFinalEvaluation": True,
        "realTrainingPreflightPassed": True,
        "releaseReady": False,
    }
    for key, expected in required.items():
        if training.get(key) != expected:
            raise RuntimeError(
                f"release candidate training contract failed for {key}: "
                f"{training.get(key)!r} != {expected!r}"
            )
    for key in (
        "trainSetFingerprint", "validationSetFingerprint", "testSetFingerprint",
        "trainArtifactFingerprint", "validationArtifactFingerprint", "testArtifactFingerprint",
    ):
        _digest_field(training, key, "real-training attestation")
    return model, training_path, training, digest, size


def _release_contract(release_path: pathlib.Path, digest: str, size: int, training: dict) -> dict:
    release = _load_json(release_path, "final real-student release evidence")
    required = {
        "schema": 2,
        "pipeline": "manzl-real-student-release-evidence-bundle",
        "model": MODEL_NAME,
        "sha256": digest,
        "bytes": size,
        "trainingAttestationVerified": True,
        "candidateArtifactIntegrityPassed": True,
        "candidateSplitBindingsVerified": True,
        "splitArtifactsStableAcrossFinalization": True,
        "heldOutCorpusIdentityMatchedAcrossEvidence": True,
        "heldOutArtifactIdentityMatchedAcrossEvidence": True,
        "artifactFingerprintIsAggregateOnly": True,
        "releaseCorpusScalePassed": True,
        "releaseCorpusScalePolicyVersion": 1,
        "releaseCorpusScaleRecomputedAtFinalize": True,
        "semanticMetricsExactHeldOutSampleCoverage": True,
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
    for key, expected in required.items():
        if release.get(key) != expected:
            raise RuntimeError(
                f"final release contract failed for {key}: {release.get(key)!r} != {expected!r}"
            )
    heldout = _digest_field(release, "heldOutArtifactFingerprint", "final release evidence")
    if heldout != training.get("testArtifactFingerprint"):
        raise RuntimeError("final release held-out artifact fingerprint differs from training provenance")
    return release


def _release_manifest(manifest: dict, digest: str, size: int, heldout_artifact: str, replace: bool) -> dict:
    required_models = manifest.get("required")
    if not isinstance(required_models, list):
        raise ValueError("runtime model manifest required list is missing")
    student = next(
        (item for item in required_models if isinstance(item, dict) and item.get("id") == "manzl_reconstruction_student"),
        None,
    )
    if student is None:
        raise RuntimeError("runtime model manifest does not declare the reconstruction student")
    if student.get("releaseReady") is True and not replace:
        raise RuntimeError("a release-ready student is already packaged; pass --replace to replace it explicitly")

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

    student.update(
        {
            "path": f"models/{MODEL_NAME}",
            "status": "real-held-out-release-ready",
            "sha256": digest,
            "bytes": size,
            "releaseReady": True,
            "releaseEvidence": f"models/{RELEASE_NAME}",
            "trainingProvenance": f"models/{TRAINING_NAME}",
            "semanticQualityFloorVersion": 1,
            "releaseCorpusScalePolicyVersion": 1,
            "heldOutArtifactFingerprint": heldout_artifact,
        }
    )
    for stale_key in ("generatedValidation", "proposalOnly", "trainingSource", "attribution"):
        student.pop(stale_key, None)
    return manifest


def package_release(
    candidate: pathlib.Path,
    release_path: pathlib.Path,
    assets: pathlib.Path,
    replace: bool = False,
) -> dict:
    assets = assets.resolve()
    if not assets.is_dir():
        raise FileNotFoundError(f"Android model assets directory is missing: {assets}")

    model, training_path, training, digest, size = _candidate_contract(candidate)
    release = _release_contract(release_path.resolve(), digest, size, training)
    heldout_artifact = release["heldOutArtifactFingerprint"]
    manifest_path = assets / MANIFEST_NAME
    manifest = _load_json(manifest_path, "runtime model manifest")
    staged_manifest = _release_manifest(json.loads(json.dumps(manifest)), digest, size, heldout_artifact, replace)

    staging = pathlib.Path(tempfile.mkdtemp(prefix=".manzl-release-stage-", dir=str(assets.parent)))
    try:
        shutil.copy2(model, staging / MODEL_NAME)
        shutil.copy2(training_path, staging / TRAINING_NAME)
        shutil.copy2(release_path.resolve(), staging / RELEASE_NAME)
        (staging / MANIFEST_NAME).write_text(
            json.dumps(staged_manifest, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        report = packaged_verifier.verify(staging)

        for name in (MODEL_NAME, TRAINING_NAME, RELEASE_NAME):
            os.replace(staging / name, assets / name)
        stale_quality = assets / STALE_QUALITY_NAME
        if stale_quality.exists():
            stale_quality.unlink()
        os.replace(staging / MANIFEST_NAME, manifest_path)

        final_report = packaged_verifier.verify(assets)
        if final_report != report:
            raise RuntimeError("packaged release verification changed after committing Android assets")
        return final_report
    finally:
        shutil.rmtree(staging, ignore_errors=True)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate", type=pathlib.Path, required=True)
    parser.add_argument("--release-evidence", type=pathlib.Path, required=True)
    parser.add_argument("--assets", type=pathlib.Path, default=pathlib.Path("manzl-app/src/main/assets/models"))
    parser.add_argument("--replace", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    report = package_release(args.candidate, args.release_evidence, args.assets, args.replace)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
