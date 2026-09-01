#!/usr/bin/env python3
"""Local fail-closed orchestration for a measured real-plan Student release."""
from __future__ import annotations

import argparse, json, pathlib, subprocess, sys
import real_semantic_policy, verify_real_training_inputs

HERE = pathlib.Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]


def outside(path: pathlib.Path, label: str) -> pathlib.Path:
    path = path.expanduser().resolve()
    if path == REPO_ROOT or REPO_ROOT in path.parents:
        raise RuntimeError(f"{label} must stay outside the public Git worktree: {path}")
    return path


def paths(workspace: pathlib.Path) -> dict[str, pathlib.Path]:
    root = outside(workspace, "real release workspace")
    candidate = root / "candidate"
    return {
        "root": root,
        "candidate": candidate,
        "policy": root / "semantic-policy.json",
        "semantic": candidate / "final-test-attestation.json",
        "geometry": root / "geometry-release-attestation.json",
        "release": root / "release-evidence.json",
    }


def run(*tokens: object) -> None:
    subprocess.run([sys.executable, *map(str, tokens)], check=True)


def load(path: pathlib.Path) -> dict:
    if not path.is_file():
        raise FileNotFoundError(path)
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def prepare(a: argparse.Namespace) -> dict:
    splits = outside(a.splits, "private real splits")
    p = paths(a.workspace)
    if p["root"].exists():
        raise FileExistsError(f"refusing ambiguous resume: {p['root']}")
    preflight = verify_real_training_inputs.verify(splits)
    if preflight.get("trainerMayRead") != ["train", "validation"] or preflight.get("trainerMustNotRead") != ["test"]:
        raise RuntimeError("held-out split isolation contract failed")
    p["root"].mkdir(parents=True)
    cmd = [HERE / "train_real_student.py", "--splits", splits, "--output", p["candidate"],
           "--size", a.size, "--width", a.width, "--epochs", a.epochs, "--batch", a.batch,
           "--workers", a.workers, "--lr", a.lr, "--seed", a.seed, "--patience", a.patience,
           "--min-improvement", a.min_improvement]
    if a.cpu: cmd.append("--cpu")
    run(*cmd)
    run(HERE / "real_semantic_policy.py", "lock", "--candidate", p["candidate"], "--output", p["policy"])
    policy, _ = real_semantic_policy.load_locked_policy(p["policy"], p["candidate"], require_pre_test=True)
    return {"stage":"prepared", "policyLockedBeforeTest":policy.get("lockedBeforeHeldOutTestArtifacts") is True, "releaseReady":False}


def heldout(a: argparse.Namespace) -> dict:
    splits = outside(a.splits, "private real splits")
    p = paths(a.workspace)
    real_semantic_policy.load_locked_policy(p["policy"], p["candidate"], require_pre_test=True)
    run(HERE / "evaluate_real_student_test.py", "--splits", splits, "--candidate", p["candidate"],
        "--policy", p["policy"], "--size", a.size)
    report = load(p["semantic"])
    if report.get("semanticAcceptancePassed") is not True:
        raise RuntimeError("held-out semantic acceptance failed; release remains blocked")
    return report


def finalize(a: argparse.Namespace) -> dict:
    splits = outside(a.splits, "private real splits")
    evidence = outside(a.geometry_evidence, "private runtime geometry evidence")
    p = paths(a.workspace)
    semantic = load(p["semantic"])
    if semantic.get("semanticAcceptancePassed") is not True or semantic.get("semanticAcceptancePolicyLockedBeforeTest") is not True:
        raise RuntimeError("held-out semantic gate/order proof is incomplete")
    for output in (p["geometry"], p["release"]):
        if output.exists(): raise FileExistsError(f"refusing overwrite: {output}")
    run(HERE / "verify_real_geometry_release.py", "--splits", splits, "--evidence", evidence,
        "--candidate", p["candidate"], "--output", p["geometry"])
    run(HERE / "finalize_real_student_release.py", "--splits", splits, "--candidate", p["candidate"],
        "--semantic-policy", p["policy"], "--semantic-attestation", p["semantic"],
        "--geometry-attestation", p["geometry"], "--output", p["release"])
    release = load(p["release"])
    if release.get("releaseReady") is not True: raise RuntimeError("final release bundle did not pass")
    cmd = [HERE / "package_real_student_release.py", "--candidate", p["candidate"],
           "--release-evidence", p["release"], "--assets", a.assets]
    if a.replace: cmd.append("--replace")
    run(*cmd)
    return {"stage":"packaged", "sha256":release.get("sha256"), "releaseReady":True}


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(); sub = root.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare"); p.add_argument("--splits",type=pathlib.Path,required=True); p.add_argument("--workspace",type=pathlib.Path,required=True)
    for name,typ,val in (("size",int,512),("width",int,32),("epochs",int,60),("batch",int,4),("workers",int,2),("lr",float,2e-4),("seed",int,439),("patience",int,8),("min-improvement",float,1e-4)):
        p.add_argument("--"+name,type=typ,default=val)
    p.add_argument("--cpu",action="store_true")
    h=sub.add_parser("heldout"); h.add_argument("--splits",type=pathlib.Path,required=True); h.add_argument("--workspace",type=pathlib.Path,required=True); h.add_argument("--size",type=int,default=512)
    f=sub.add_parser("finalize"); f.add_argument("--splits",type=pathlib.Path,required=True); f.add_argument("--workspace",type=pathlib.Path,required=True); f.add_argument("--geometry-evidence",type=pathlib.Path,required=True); f.add_argument("--assets",type=pathlib.Path,default=pathlib.Path("manzl-app/src/main/assets/models")); f.add_argument("--replace",action="store_true")
    return root


def main() -> int:
    a=parser().parse_args(); report={"prepare":prepare,"heldout":heldout,"finalize":finalize}[a.command](a)
    print(json.dumps(report,indent=2,sort_keys=True)); return 0

if __name__ == "__main__": raise SystemExit(main())
