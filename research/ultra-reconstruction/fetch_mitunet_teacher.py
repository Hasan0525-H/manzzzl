#!/usr/bin/env python3
"""Fetch the pinned MitUNet wall-segmentation teacher for personal/non-commercial distillation.

MitUNet code is MIT, while the published pretrained weight is explicitly documented by its authors as
CC-BY-NC-4.0 because it was trained on CubiCasa5K. Manzl therefore treats this 257 MB checkpoint as a
development-time optional teacher only: it is ignored by git and never packaged into the Android APK.
For this personal/non-commercial project it can provide a strong, independent wall-mask opinion that
is distilled into Manzl's rights-tracked student. If the project ever becomes commercial, this teacher
must be excluded or replaced with weights trained from commercial-compatible data.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import tempfile
import urllib.request

REPOSITORY = "aliasstudio/mitunet"
REVISION = "ade0aa6ba01c72f02a32a33a605c36b54b264a7a"
WEIGHT_PATH = "experiments/models/mitunet_finetune_a6_mit_b4_tversky_8864_28E.pth"
WEIGHT_SHA256 = "9c56c86723b0b5099ea63c82b5cac2f9c98c1816536003a78e422b7fcfadfbaf"
WEIGHT_BYTES = 257_383_307


def media_url() -> str:
    return f"https://media.githubusercontent.com/media/{REPOSITORY}/{REVISION}/{WEIGHT_PATH}"


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: pathlib.Path) -> None:
    if path.stat().st_size != WEIGHT_BYTES:
        raise RuntimeError(f"MitUNet size mismatch: {path.stat().st_size} != {WEIGHT_BYTES}")
    digest = sha256(path)
    if digest != WEIGHT_SHA256:
        raise RuntimeError(f"MitUNet SHA-256 mismatch: {digest}")


def download(destination: pathlib.Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(media_url(), headers={"User-Agent": "Manzl-Ultra-Reconstruction/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--destination",
        type=pathlib.Path,
        default=pathlib.Path("research/ultra-reconstruction/.cache/mitunet/mitunet_wall_teacher.pth"),
    )
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()

    if not args.verify_only:
        with tempfile.TemporaryDirectory(prefix="manzl-mitunet-") as tmp:
            temporary = pathlib.Path(tmp) / "teacher.pth"
            print("Downloading pinned MitUNet teacher (~257 MB)...")
            download(temporary)
            verify(temporary)
            args.destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(temporary), args.destination)

    if not args.destination.exists():
        raise SystemExit("MitUNet teacher is missing; run without --verify-only first")
    verify(args.destination)
    metadata = {
        "teacher": "MitUNet wall segmentation",
        "revision": REVISION,
        "sha256": WEIGHT_SHA256,
        "bytes": WEIGHT_BYTES,
        "codeLicense": "MIT",
        "weightLicense": "CC-BY-NC-4.0",
        "usage": "personal/non-commercial development-time teacher only",
        "shipInAndroidApk": False,
    }
    metadata_path = args.destination.with_suffix(args.destination.suffix + ".verified.json")
    metadata_path.write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
