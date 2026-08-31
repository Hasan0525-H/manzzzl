#!/usr/bin/env python3
"""Fetch the exact official Raster2Seq Raster2Graph-512 teacher checkpoint.

The official Hugging Face model repository is marked MIT. We pin the commit that introduced the
512px checkpoint and verify the Git-LFS SHA-256/size before the file is accepted. The 1.13 GB teacher
is development-time only and must never be copied into the Android APK; it is used to generate
teacher vectors/pseudo-labels for distilling the much smaller Manzl student.

No API key, paid service, or Hugging Face account is required for the public checkpoint.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import shutil
import tempfile
import urllib.request

REVISION = "6be66814dcc2420f0a3ac87591ba4b6e1b0b44b8"
REPO = "haopt/Raster2Seq"
CHECKPOINT_PATH = "Raster2Graph-512/checkpoint.pth"
CONFIG_PATH = "Raster2Graph-512/config.json"
CHECKPOINT_SHA256 = "5736bf4fe1ebd8ef6cca63ea51c2c3f8971d6d1848dd9049fc5ca251bf565229"
CHECKPOINT_SIZE = 1_130_134_240
EXPECTED_ROOM_F1 = 98.1


def resolve_url(path: str) -> str:
    return f"https://huggingface.co/{REPO}/resolve/{REVISION}/{path}?download=true"


def download(url: str, destination: pathlib.Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "Manzl-Ultra-Reconstruction/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(4 * 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return digest.hexdigest()


def verify_checkpoint(path: pathlib.Path) -> None:
    size = path.stat().st_size
    if size != CHECKPOINT_SIZE:
        raise RuntimeError(f"Raster2Seq checkpoint size mismatch: {size} != {CHECKPOINT_SIZE}")
    digest = sha256(path)
    if digest != CHECKPOINT_SHA256:
        raise RuntimeError(f"Raster2Seq checkpoint SHA-256 mismatch: {digest}")


def verify_config(path: pathlib.Path) -> dict:
    config = json.loads(path.read_text(encoding="utf-8"))
    if config.get("checkpoint_key") != "raster2graph-512":
        raise RuntimeError("Unexpected Raster2Seq checkpoint key")
    if int(config.get("inference_args", {}).get("image_size", 0)) != 512:
        raise RuntimeError("Unexpected Raster2Seq image size")
    room_f1 = float(config.get("metrics", {}).get("room_f1", 0.0))
    if abs(room_f1 - EXPECTED_ROOM_F1) > 1e-6:
        raise RuntimeError(f"Unexpected published RoomF1: {room_f1}")
    return config


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--destination",
        type=pathlib.Path,
        default=pathlib.Path("research/ultra-reconstruction/.cache/raster2seq/Raster2Graph-512"),
    )
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()

    checkpoint = args.destination / "checkpoint.pth"
    config = args.destination / "config.json"

    if not args.verify_only:
        args.destination.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="manzl-r2s-") as tmp:
            temp = pathlib.Path(tmp)
            temp_checkpoint = temp / "checkpoint.pth"
            temp_config = temp / "config.json"
            print("Downloading pinned Raster2Seq Raster2Graph-512 teacher (~1.13 GB)...")
            download(resolve_url(CHECKPOINT_PATH), temp_checkpoint)
            download(resolve_url(CONFIG_PATH), temp_config)
            verify_checkpoint(temp_checkpoint)
            verify_config(temp_config)
            shutil.move(str(temp_checkpoint), checkpoint)
            shutil.move(str(temp_config), config)

    if not checkpoint.exists() or not config.exists():
        raise SystemExit("Teacher files are missing; run without --verify-only first")
    verify_checkpoint(checkpoint)
    teacher_config = verify_config(config)
    metadata = {
        "teacher": "Raster2Seq/Raster2Graph-512",
        "revision": REVISION,
        "checkpointSha256": CHECKPOINT_SHA256,
        "checkpointBytes": CHECKPOINT_SIZE,
        "publishedRoomF1": teacher_config["metrics"]["room_f1"],
        "license": "MIT (official model repository metadata)",
        "shipInAndroidApk": False,
    }
    (args.destination / "verified.json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
