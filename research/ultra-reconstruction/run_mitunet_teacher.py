#!/usr/bin/env python3
"""Run the pinned MitUNet wall teacher and emit Manzl consensus-ready NPZ samples.

This is a development-time, personal/non-commercial distillation tool. It performs local inference only:
no cloud API, account, telemetry or paid service is used. The published MitUNet checkpoint was trained
on CubiCasa5K and is CC-BY-NC-4.0, so the checkpoint itself must never be copied into the public APK.

The output deliberately declares only ``background`` and ``wall_face``. Every other Manzl semantic
class is absent from ``semantic_classes`` and therefore becomes an abstention in
``build_teacher_consensus.py``.

Recommended pairing with Raster2Seq:

  1. Run Raster2Seq on a source corpus with ``--save_pred``.
  2. Adapt its JSON vectors with ``adapt_raster2seq_predictions.py``.
  3. Run this script over the same transformed images Raster2Seq saved beside ``jsons/``.
  4. Pass both NPZ directories to ``build_teacher_consensus.py``.

Because probabilities are resized back to each source image's exact dimensions, matching stems from
both teachers remain spatially compatible for consensus.
"""

from __future__ import annotations

import argparse
import pathlib
from typing import Iterable

import cv2
import numpy as np

LOCAL_CLASSES = ["background", "wall_face"]
DEFAULT_CHECKPOINT = pathlib.Path(
    "research/ultra-reconstruction/.cache/mitunet/mitunet_wall_teacher.pth"
)
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp"}
IMAGENET_MEAN = np.asarray([0.485, 0.456, 0.406], dtype=np.float32)
IMAGENET_STD = np.asarray([0.229, 0.224, 0.225], dtype=np.float32)


def discover_images(root: pathlib.Path, recursive: bool) -> list[pathlib.Path]:
    iterator: Iterable[pathlib.Path] = root.rglob("*") if recursive else root.glob("*")
    images = [
        path
        for path in iterator
        if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS and "jsons" not in path.parts
    ]
    if not images:
        raise RuntimeError(f"No source images found under {root}")
    return sorted(images)


def read_rgb(path: pathlib.Path) -> np.ndarray:
    bgr = cv2.imread(str(path), cv2.IMREAD_COLOR)
    if bgr is None:
        raise ValueError(f"OpenCV could not decode image: {path}")
    return cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)


def encode_wall_probability(
    wall_probability: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Encode one calibrated binary wall opinion without inventing other semantic classes."""
    wall = np.asarray(wall_probability, dtype=np.float32)
    if wall.ndim != 2:
        raise ValueError(f"wall_probability must be [H,W], got {wall.shape}")
    if not np.isfinite(wall).all():
        raise ValueError("wall_probability contains non-finite values")

    wall = np.clip(wall, 0.0, 1.0)
    probabilities = np.stack([1.0 - wall, wall], axis=0).astype(np.float32)

    # Confidence measures distance from the binary decision boundary. Consensus still applies its own
    # independent probability/margin/vote gates, so this multiplier cannot bypass fail-closed voting.
    confidence = np.clip(np.abs(wall - 0.5) * 2.0, 0.0, 1.0).astype(np.float32)
    valid_mask = np.ones(wall.shape, dtype=np.uint8)
    return probabilities, confidence, valid_mask


def load_model(checkpoint: pathlib.Path, device_name: str):
    # Heavy research dependencies stay lazy so contract tooling can inspect/compile this file without
    # installing PyTorch + segmentation-models-pytorch.
    import torch
    import segmentation_models_pytorch as smp

    if not checkpoint.is_file():
        raise FileNotFoundError(
            f"MitUNet checkpoint is missing: {checkpoint}. "
            "Run fetch_mitunet_teacher.py first."
        )

    if device_name == "auto":
        device_name = "cuda" if torch.cuda.is_available() else "cpu"
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("--device cuda requested but CUDA is unavailable")
    device = torch.device(device_name)

    # Exact architecture published by the MitUNet authors for this checkpoint.
    auxiliary_segformer = smp.Segformer(encoder_name="mit_b4", encoder_weights=None)
    model = smp.Unet(
        encoder_name="mit_b4",
        encoder_weights=None,
        in_channels=3,
        classes=1,
        decoder_attention_type="scse",
    )
    model.encoder = auxiliary_segformer.encoder

    state = torch.load(checkpoint, map_location=device)
    if isinstance(state, dict) and "state_dict" in state and isinstance(state["state_dict"], dict):
        state = state["state_dict"]
    if not isinstance(state, dict):
        raise ValueError("Unexpected MitUNet checkpoint payload; expected a state_dict mapping")
    model.load_state_dict(state, strict=True)
    model.to(device)
    model.eval()
    return torch, model, device


def predict_wall_probability(
    torch_module,
    model,
    device,
    image_rgb: np.ndarray,
    input_size: int,
) -> np.ndarray:
    if input_size < 64:
        raise ValueError("input_size must be >= 64")
    original_height, original_width = image_rgb.shape[:2]
    resized = cv2.resize(image_rgb, (input_size, input_size), interpolation=cv2.INTER_AREA)
    normalized = resized.astype(np.float32) / 255.0
    normalized = (normalized - IMAGENET_MEAN) / IMAGENET_STD
    tensor = (
        torch_module.from_numpy(normalized)
        .permute(2, 0, 1)
        .unsqueeze(0)
        .to(device)
    )

    with torch_module.inference_mode():
        logits = model(tensor)
        probability = torch_module.sigmoid(logits)[0, 0].detach().cpu().numpy().astype(np.float32)

    if probability.shape != (input_size, input_size):
        raise ValueError(f"Unexpected MitUNet output shape: {probability.shape}")
    if (original_height, original_width) != probability.shape:
        probability = cv2.resize(
            probability,
            (original_width, original_height),
            interpolation=cv2.INTER_LINEAR,
        ).astype(np.float32)
    return np.clip(probability, 0.0, 1.0)


def destination_for(source: pathlib.Path, input_root: pathlib.Path, output_root: pathlib.Path) -> pathlib.Path:
    relative = source.relative_to(input_root)
    return (output_root / relative).with_suffix(".npz")


def save_prediction(
    destination: pathlib.Path,
    image_rgb: np.ndarray,
    wall_probability: np.ndarray,
) -> None:
    semantic_probs, confidence, valid_mask = encode_wall_probability(wall_probability)
    if wall_probability.shape != image_rgb.shape[:2]:
        raise ValueError("wall probability must match source image dimensions before export")

    destination.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        destination,
        image=image_rgb,
        semantic_probs=semantic_probs,
        semantic_classes=np.asarray(LOCAL_CLASSES, dtype="U32"),
        confidence=confidence,
        valid_mask=valid_mask,
        teacher_format=np.asarray(["mitunet-wall-probability-v1"], dtype="U48"),
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=pathlib.Path,
        required=True,
        help="Directory of source images; use Raster2Seq's saved transformed-image root for alignment",
    )
    parser.add_argument("--checkpoint", type=pathlib.Path, default=DEFAULT_CHECKPOINT)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument("--recursive", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.input.is_dir():
        raise FileNotFoundError(f"Input directory does not exist: {args.input}")
    images = discover_images(args.input, recursive=args.recursive)
    torch_module, model, device = load_model(args.checkpoint, args.device)

    written = 0
    for source in images:
        image_rgb = read_rgb(source)
        wall_probability = predict_wall_probability(
            torch_module=torch_module,
            model=model,
            device=device,
            image_rgb=image_rgb,
            input_size=args.size,
        )
        save_prediction(
            destination=destination_for(source, args.input, args.output),
            image_rgb=image_rgb,
            wall_probability=wall_probability,
        )
        written += 1

    print(f"MitUNet teacher samples written: {written}")
    print("output:", args.output)
    print("semantic classes:", ", ".join(LOCAL_CLASSES))
    print("all other Manzl classes: abstain")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
