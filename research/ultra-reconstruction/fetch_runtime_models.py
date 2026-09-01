#!/usr/bin/env python3
"""Fetch verified zero-cost MobileSAM ONNX assets for Manzl.

The files are downloaded from a pinned public Hugging Face revision and verified byte-for-byte with
published SHA-256 hashes before they can be copied into the Android assets directory. The script never
uses an inference API and does not fetch the still-to-be-trained Manzl reconstruction student.

Network availability is not a quality signal. Public model hosting occasionally returns transient
429/5xx responses, resets long downloads, or truncates a stream. Those failures must not turn into a
silent MobileSAM downgrade. This fetcher retries only transient transport/server failures, overwrites
the temporary file on every attempt, and still requires the exact pinned SHA-256 before an asset can
enter the APK.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import shutil
import socket
import sys
import tempfile
import time
import urllib.error
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

MAX_DOWNLOAD_ATTEMPTS = 8
DOWNLOAD_TIMEOUT_SECONDS = 180
MAX_BACKOFF_SECONDS = 45
RETRYABLE_HTTP_STATUS = {408, 425, 429, 500, 502, 503, 504}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _retryable(error: BaseException) -> bool:
    if isinstance(error, urllib.error.HTTPError):
        return error.code in RETRYABLE_HTTP_STATUS
    return isinstance(
        error,
        (
            urllib.error.URLError,
            TimeoutError,
            socket.timeout,
            ConnectionError,
            ConnectionResetError,
            BrokenPipeError,
        ),
    )


def _backoff_seconds(attempt: int) -> int:
    # 2, 4, 8, 16, 32, 45, 45 ...; bounded so a temporary host outage does not burn the whole CI job.
    return min(MAX_BACKOFF_SECONDS, 2 ** attempt)


def download(url: str, destination: pathlib.Path) -> None:
    request = urllib.request.Request(
        url,
        headers={
            "User-Agent": "Manzl-Ultra-Reconstruction/2",
            "Accept": "application/octet-stream,*/*;q=0.8",
        },
    )
    last_error: BaseException | None = None
    for attempt in range(1, MAX_DOWNLOAD_ATTEMPTS + 1):
        try:
            # Opening with wb on every attempt is intentional: a reset/truncated response must never
            # be concatenated with bytes from a prior failed attempt.
            with urllib.request.urlopen(request, timeout=DOWNLOAD_TIMEOUT_SECONDS) as response:
                content_length = response.headers.get("Content-Length")
                expected_length = int(content_length) if content_length and content_length.isdigit() else None
                written = 0
                with destination.open("wb") as output:
                    while True:
                        chunk = response.read(1024 * 1024)
                        if not chunk:
                            break
                        output.write(chunk)
                        written += len(chunk)
                if expected_length is not None and written != expected_length:
                    raise ConnectionError(
                        f"truncated download: expected {expected_length} bytes, received {written}"
                    )
            return
        except BaseException as error:
            last_error = error
            if attempt >= MAX_DOWNLOAD_ATTEMPTS or not _retryable(error):
                raise
            delay = _backoff_seconds(attempt)
            print(
                f"transient model-host failure on attempt {attempt}/{MAX_DOWNLOAD_ATTEMPTS}: "
                f"{error}; retrying in {delay}s",
                file=sys.stderr,
            )
            time.sleep(delay)

    if last_error is not None:  # pragma: no cover - defensive; loop either returns or raises.
        raise last_error


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
        print(f"downloading {name} from pinned revision {REVISION}")
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
