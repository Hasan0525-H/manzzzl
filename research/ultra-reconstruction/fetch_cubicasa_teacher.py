#!/usr/bin/env python3
"""Fetch and verify the official CubiCasa5K FloorTrans checkpoint.

The official CubiCasa5K README links Google Drive file ``1gRB7ez1e4H7a9Y09lLqRuna0luZO5VRK``
as ``model_best_val_loss_var.pkl``. Manzl independently fingerprinted those exact bytes on a clean
GitHub Actions runner before pinning the constants below. The checkpoint is CC-BY-NC-4.0 lineage and
is used only as a personal/non-commercial development-time teacher; it is never committed or shipped
inside the Android APK.

Google Drive's large-file confirmation flow is handled by the small open-source ``gdown`` package.
Install the pinned downloader with ``python -m pip install gdown==5.2.0`` when fetching is needed.
Verification itself uses only the Python standard library and works offline with ``--verify-only``.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
FILE_ID = "1gRB7ez1e4H7a9Y09lLqRuna0luZO5VRK"
FILE_NAME = "model_best_val_loss_var.pkl"
WEIGHT_SHA256 = "dd20b4e1bf1d670f2125107b079df06958b1ccd36e49a464ab739aeb00b8e7a2"
WEIGHT_BYTES = 208_651_193
DEFAULT_DESTINATION = (
    ROOT / ".cache" / "manzl-teachers" / "cubicasa-floortrans" / FILE_NAME
)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify(path: pathlib.Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"CubiCasa checkpoint is missing: {path}")
    size = path.stat().st_size
    if size != WEIGHT_BYTES:
        raise RuntimeError(f"CubiCasa size mismatch: {size} != {WEIGHT_BYTES}")
    digest = sha256(path)
    if digest != WEIGHT_SHA256:
        raise RuntimeError(
            "CubiCasa SHA-256 mismatch: "
            f"{digest} != {WEIGHT_SHA256}. Refusing unpinned teacher bytes."
        )
    with path.open("rb") as handle:
        prefix = handle.read(64).lstrip().lower()
    if prefix.startswith(b"<!doctype html") or prefix.startswith(b"<html"):
        raise RuntimeError("CubiCasa checkpoint path contains HTML instead of model bytes")


def download(destination: pathlib.Path) -> None:
    try:
        import gdown
    except ImportError as error:
        raise RuntimeError(
            "gdown==5.2.0 is required only for the Google Drive download. "
            "Install it with: python -m pip install gdown==5.2.0"
        ) from error

    destination.parent.mkdir(parents=True, exist_ok=True)
    result = gdown.download(id=FILE_ID, output=str(destination), quiet=False)
    if result is None or not destination.is_file():
        raise RuntimeError("gdown did not produce the CubiCasa checkpoint")


def metadata() -> dict:
    return {
        "teacher": "CubiCasa5K FloorTrans",
        "sourceFileId": FILE_ID,
        "fileName": FILE_NAME,
        "sha256": WEIGHT_SHA256,
        "bytes": WEIGHT_BYTES,
        "codeAndDataLicense": "CC-BY-NC-4.0",
        "usage": "personal/non-commercial development-time teacher only",
        "shipInAndroidApk": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--destination", type=pathlib.Path, default=DEFAULT_DESTINATION)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()

    if not args.verify_only:
        with tempfile.TemporaryDirectory(prefix="manzl-cubicasa-") as temporary_root:
            temporary = pathlib.Path(temporary_root) / FILE_NAME
            print("Downloading pinned CubiCasa teacher (~209 MB)...")
            download(temporary)
            verify(temporary)
            args.destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(temporary), args.destination)

    if not args.destination.exists():
        raise SystemExit("CubiCasa teacher is missing; run without --verify-only first")
    verify(args.destination)

    report = metadata()
    metadata_path = args.destination.with_suffix(args.destination.suffix + ".verified.json")
    metadata_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
