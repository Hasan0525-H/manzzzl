#!/usr/bin/env python3
"""Bootstrap Manzl's heavy floor-plan teacher ensemble into a local ignored cache.

This script intentionally downloads only source repositories. Checkpoints are fetched by each upstream
project's documented tooling into the ignored cache after their exact terms are reviewed. Nothing here
uploads data, calls a paid API, or modifies Android runtime behavior.
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


def clone_or_update(repo_url: str, destination: pathlib.Path) -> None:
    if destination.exists():
        if not (destination / ".git").exists():
            raise RuntimeError(f"Refusing to overwrite non-git directory: {destination}")
        run("git", "fetch", "--all", "--tags", "--prune", cwd=destination)
        run("git", "pull", "--ff-only", cwd=destination)
        return
    destination.parent.mkdir(parents=True, exist_ok=True)
    run("git", "clone", "--filter=blob:none", repo_url, str(destination))


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
        clone_or_update(repo, destination)

    print("\nTeacher source bootstrap complete.")
    print("Cache:", args.cache)
    print("No checkpoint is copied into the public repository or APK by this script.")
    print("Next pipeline stage: run teacher inference in a free GPU environment, generate consensus labels,")
    print("then train/export the standard-operator Manzl ONNX student for Android.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(f"Command failed with exit code {error.returncode}", file=sys.stderr)
        raise SystemExit(error.returncode)
