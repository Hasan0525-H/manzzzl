#!/usr/bin/env python3
"""Fetch verified zero-cost MobileSAM ONNX assets for Manzl.

The files are downloaded from a pinned public Hugging Face revision and verified byte-for-byte with
published SHA-256 hashes before they can be copied into the Android assets directory. The script never
uses an inference API and does not fetch the still-to-be-trained Manzl reconstruction student.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil
import sys
import tempfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "manzl-app" / "src" / "main" / "assets" / "models"
REVISION = "0d3b403339b4674a82493d5e97964dd78089ddc8"
BASE = f"https://huggingface.co/Acly/MobileSAM/resolve/{REVISION}"

ASSETS = {
    "mobile_sam_encoder.onnx": {
        "url": f"{BASE}/mobile_sam_image_encoder.onnx?download=true",
        "sha256": "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749",
    },
    "mobile_sam_decoder.onnx": {
        "url": f"{BASE}/sam_mask_decoder_single.onnx?download=true",
        "sha256": "93915fc7c993ab9d59ab8c9ccd3bce37f7509c81ab4150a74abd4d2abbd8570d",
    },
}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: pathlib.Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "Manzl-Ultra-Reconstruction/1"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)


def fetch_one(name: str, metadata: dict[str, str], output: pathlib.Path, force: bool) -> None:
    destination = output / name
    expected = metadata["sha256"]
    if destination.exists() and not force:
        actual = sha256(destination)
        if actual == expected:
            print(f"verified existing {name}: {actual}")
            return
        print(f"existing {name} checksum mismatch; replacing", file=sys.stderr)

    output.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(prefix=f"{name}.", delete=False, dir=output) as handle:
        temporary = pathlib.Path(handle.name)
    try:
        print(f"downloading {name}")
        download(metadata["url"], temporary)
        actual = sha256(temporary)
        if actual != expected:
            raise RuntimeError(f"SHA-256 mismatch for {name}: expected {expected}, got {actual}")
        temporary.replace(destination)
        print(f"verified {name}: {actual}")
    finally:
        if temporary.exists():
            temporary.unlink()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    for name, metadata in ASSETS.items():
        fetch_one(name, metadata, args.output, args.force)

    print("MobileSAM runtime assets are ready in:", args.output)
    print("No paid API or network inference is involved; the Android APK reads these local assets.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
