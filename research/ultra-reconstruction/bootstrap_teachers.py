#!/usr/bin/env python3
"""Bootstrap Manzl's heavy floor-plan teacher ensemble into a local ignored cache.

This script intentionally downloads only source repositories. When ``teachers.json`` supplies a
``repositoryRevision`` the checkout is detached at that exact commit so a future upstream push cannot
silently change pseudo-label generation. Checkpoints are fetched by the corresponding pinned fetcher
and are never copied into the public repository or Android APK.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
MANIFEST = pathlib.Path(__file__).with_name("teachers.json")
DEFAULT_CACHE = ROOT / ".cache" / "manzl-teachers"


def run(*args: str, cwd: pathlib.Path | None = None) -> None:
    print("+", " ".join(args), flush=True)
    subprocess.run(args, cwd=cwd, check=True)


def clone_or_update(
    repo_url: str,
    destination: pathlib.Path,
    revision: str | None,
) -> None:
    if destination.exists():
        if not (destination / ".git").exists():
            raise RuntimeError(f"Refusing to overwrite non-git directory: {destination}")
        run("git", "fetch", "--all", "--tags", "--prune", cwd=destination)
    else:
        destination.parent.mkdir(parents=True, exist_ok=True)
        run("git", "clone", "--filter=blob:none", repo_url, str(destination))

    if revision:
        # Fetching the exact object makes this work even with a filtered/shallow-ish local cache.
        run("git", "fetch", "origin", revision, cwd=destination)
        run("git", "checkout", "--detach", revision, cwd=destination)
        actual = subprocess.check_output(
            ["git", "rev-parse", "HEAD"],
            cwd=destination,
            text=True,
        ).strip()
        if actual != revision:
            raise RuntimeError(
                f"Pinned revision mismatch for {destination}: {actual} != {revision}"
            )
    else:
        # Unpinned references remain development references only. Runtime-critical teachers should
        # gain repositoryRevision before they become release-training evidence.
        run("git", "checkout", "-", cwd=destination) if _is_detached(destination) else None
        run("git", "pull", "--ff-only", cwd=destination)


def _is_detached(destination: pathlib.Path) -> bool:
    result = subprocess.run(
        ["git", "symbolic-ref", "-q", "HEAD"],
        cwd=destination,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    return result.returncode != 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cache", type=pathlib.Path, default=DEFAULT_CACHE)
    parser.add_argument("--clean", action="store_true")
    args = parser.parse_args()

    if args.clean and args.cache.exists():
        print(f"Removing local ignored cache: {args.cache}")
        shutil.rmtree(args.cache)

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    args.cache.mkdir(parents=True, exist_ok=True)

    for teacher in manifest["teachers"]:
        repo = teacher.get("repository")
        if not repo:
            continue
        destination = args.cache / teacher["id"]
        clone_or_update(repo, destination, teacher.get("repositoryRevision"))

    print("\nTeacher source bootstrap complete.")
    print("Cache:", args.cache)
    print("No checkpoint is copied into the public repository or APK by this script.")
    print("Next pipeline stage: fetch/verify pinned checkpoints, run teacher inference, build strict")
    print("consensus labels, then train/export the standard-operator Manzl ONNX student.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(f"Command failed with exit code {error.returncode}", file=sys.stderr)
        raise SystemExit(error.returncode)
