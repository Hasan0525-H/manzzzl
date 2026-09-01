#!/usr/bin/env python3
"""Run the official CubiCasa5K FloorTrans model as a Manzl development-time teacher.

The upstream model produces 44 channels split as 21 junction heatmaps, 12 room classes and 11 icon
classes. Manzl consumes only evidence that maps cleanly into its mobile reconstruction vocabulary:

* room class 2 (Wall) -> ``wall_face``
* high-confidence room-segmentation transitions -> ``room_boundary``
* icon class 1 (Window) -> ``window``
* icon class 2 (Door) -> ``door``
* everything confidently non-structural -> ``background``

``room_boundary`` is intentionally derived from FloorTrans's own room segmentation rather than from
Raster2Seq geometry. It therefore provides a second independent raster-domain vote for room polygon
edges. Only transitions that touch a confidently predicted room interior are eligible: background to
wall, wall to wall and other purely structural transitions do not manufacture room boundaries. A
small max-filter band aligns the segmentation edge with Raster2Seq's thin polygon-outline evidence;
consensus still requires the two teachers to agree at the same pixels.

The teacher deliberately does not claim stairs, columns, courtyards or shafts. Those classes remain
abstentions until an independent teacher actually supports them.

CubiCasa5K code/data and the published pretrained weight are CC-BY-NC-4.0 for the current checkpoint
lineage. This script is therefore for the user's personal/non-commercial development-time distillation
pipeline only. The checkpoint must never be bundled in the public APK. Runtime remains offline and
zero-cost.

Expected preparation:

  python research/ultra-reconstruction/bootstrap_teachers.py
  # Place the official model_best_val_loss_var.pkl inside the cloned CubiCasa repository.

For pixel alignment with Raster2Seq, point ``--input`` at Raster2Seq's saved transformed images. The
script preserves each source image's exact dimensions and relative stem when writing NPZ samples.
"""

from __future__ import annotations

import argparse
import pathlib
import sys
from typing import Iterable

import cv2
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[2]
DEFAULT_REPOSITORY = ROOT / ".cache" / "manzl-teachers" / "cubicasa-floortrans"
DEFAULT_CHECKPOINT_NAME = "model_best_val_loss_var.pkl"
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp"}

HEATMAP_CHANNELS = 21
ROOM_CHANNELS = 12
ICON_CHANNELS = 11
TOTAL_CHANNELS = HEATMAP_CHANNELS + ROOM_CHANNELS + ICON_CHANNELS
ROOM_BACKGROUND = 0
ROOM_WALL = 2
ROOM_RAILING = 8
ICON_WINDOW = 1
ICON_DOOR = 2
LOCAL_CLASSES = ["background", "wall_face", "door", "window", "room_boundary"]
LOCAL_CLASS = {name: index for index, name in enumerate(LOCAL_CLASSES)}
STRUCTURAL_ROOM_CLASSES = frozenset({ROOM_BACKGROUND, ROOM_WALL, ROOM_RAILING})


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


def normalize_distribution(values: np.ndarray, axis: int = 0) -> np.ndarray:
    values = np.asarray(values, dtype=np.float32)
    denominator = values.sum(axis=axis, keepdims=True)
    return np.divide(
        values,
        np.maximum(denominator, 1e-6),
        out=np.zeros_like(values),
        where=denominator > 1e-6,
    )


def derive_room_boundary_probability(room_probs: np.ndarray) -> np.ndarray:
    """Return a narrow, confidence-calibrated boundary band from FloorTrans room segmentation.

    FloorTrans predicts semantic room classes, not room instances. Room-vs-wall/background transitions
    still expose the room polygon perimeter, while different confident room labels can expose an
    interior room-to-room boundary. Same-label adjacent rooms remain separable through their wall
    strip. Requiring at least one side of every transition to be a non-structural room class prevents
    wall/background texture changes from being promoted to room geometry.
    """
    rooms = np.asarray(room_probs, dtype=np.float32)
    if rooms.ndim != 3 or rooms.shape[0] != ROOM_CHANNELS:
        raise ValueError(f"room_probs must be [{ROOM_CHANNELS},H,W], got {rooms.shape}")
    if not np.isfinite(rooms).all():
        raise ValueError("room_probs contain non-finite values")

    rooms = normalize_distribution(np.clip(rooms, 0.0, 1.0), axis=0)
    labels = np.argmax(rooms, axis=0).astype(np.int16)
    confidence = np.max(rooms, axis=0).astype(np.float32)
    interior = ~np.isin(labels, np.asarray(sorted(STRUCTURAL_ROOM_CLASSES), dtype=np.int16))
    boundary = np.zeros(labels.shape, dtype=np.float32)

    def accumulate(a_slice, b_slice) -> None:
        a_label = labels[a_slice]
        b_label = labels[b_slice]
        transition = a_label != b_label
        touches_room = interior[a_slice] | interior[b_slice]
        pair_confidence = np.minimum(confidence[a_slice], confidence[b_slice])
        evidence = np.where(transition & touches_room, pair_confidence, 0.0).astype(np.float32)
        boundary[a_slice] = np.maximum(boundary[a_slice], evidence)
        boundary[b_slice] = np.maximum(boundary[b_slice], evidence)

    if labels.shape[1] > 1:
        accumulate((slice(None), slice(None, -1)), (slice(None), slice(1, None)))
    if labels.shape[0] > 1:
        accumulate((slice(None, -1), slice(None)), (slice(1, None), slice(None)))

    # Raster2Seq defaults to a three-pixel polygon outline. A 3x3 max filter makes the segmentation
    # transition comparable without moving the inferred edge or inventing evidence away from it.
    if boundary.size:
        boundary = cv2.dilate(boundary, np.ones((3, 3), dtype=np.uint8), iterations=1)
    return np.clip(boundary, 0.0, 1.0).astype(np.float32)


def encode_floortrans_probabilities(
    room_probs: np.ndarray,
    icon_probs: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Map official FloorTrans room/icon probabilities into a fail-closed Manzl teacher opinion."""
    rooms = np.asarray(room_probs, dtype=np.float32)
    icons = np.asarray(icon_probs, dtype=np.float32)
    if rooms.ndim != 3 or rooms.shape[0] != ROOM_CHANNELS:
        raise ValueError(f"room_probs must be [{ROOM_CHANNELS},H,W], got {rooms.shape}")
    if icons.ndim != 3 or icons.shape[0] != ICON_CHANNELS:
        raise ValueError(f"icon_probs must be [{ICON_CHANNELS},H,W], got {icons.shape}")
    if rooms.shape[1:] != icons.shape[1:]:
        raise ValueError("room/icon probability maps must have matching spatial dimensions")
    if not np.isfinite(rooms).all() or not np.isfinite(icons).all():
        raise ValueError("FloorTrans probabilities contain non-finite values")

    rooms = normalize_distribution(np.clip(rooms, 0.0, 1.0), axis=0)
    icons = normalize_distribution(np.clip(icons, 0.0, 1.0), axis=0)

    wall = rooms[ROOM_WALL]
    door = icons[ICON_DOOR]
    window = icons[ICON_WINDOW]
    opening = np.maximum(door, window)
    boundary = derive_room_boundary_probability(rooms) * (1.0 - opening)

    # FloorTrans's room and icon heads are independent. Openings commonly overlap the wall region, so
    # suppress wall/background by the opening probability. Room boundaries similarly own only the
    # narrow segmentation-transition band and suppress the structural/background opinion there. This
    # keeps each local pixel opinion mutually exclusive before the independent-teacher quorum stage.
    boundary_suppression = 1.0 - boundary
    wall_score = wall * (1.0 - opening) * boundary_suppression
    background_score = (1.0 - wall) * (1.0 - opening) * boundary_suppression
    scores = np.stack(
        [
            background_score,
            wall_score,
            door,
            window,
            boundary,
        ],
        axis=0,
    ).astype(np.float32)
    semantic_probs = normalize_distribution(scores, axis=0)

    winner_probability = semantic_probs.max(axis=0)
    # The consensus layer independently enforces probability/margin/vote thresholds. Confidence here
    # simply down-weights ambiguous FloorTrans pixels; it never promotes a low-probability class.
    confidence = np.clip((winner_probability - 0.20) / 0.80, 0.0, 1.0).astype(np.float32)
    valid_mask = np.ones(winner_probability.shape, dtype=np.uint8)
    return semantic_probs, confidence, valid_mask


def load_model(repository: pathlib.Path, checkpoint: pathlib.Path, device_name: str):
    # Upstream FloorTrans is an old research stack. Import it lazily from the pinned local clone so the
    # lightweight contract suite does not need those heavyweight dependencies.
    import torch

    if not repository.is_dir():
        raise FileNotFoundError(
            f"CubiCasa repository is missing: {repository}. "
            "Run bootstrap_teachers.py first."
        )
    if not checkpoint.is_file():
        raise FileNotFoundError(
            f"CubiCasa checkpoint is missing: {checkpoint}. "
            "Place the official model_best_val_loss_var.pkl there before inference."
        )

    repository_text = str(repository.resolve())
    if repository_text not in sys.path:
        sys.path.insert(0, repository_text)
    from floortrans.models import get_model

    if device_name == "auto":
        device_name = "cuda" if torch.cuda.is_available() else "cpu"
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("--device cuda requested but CUDA is unavailable")
    device = torch.device(device_name)

    model = get_model("hg_furukawa_original", 51)
    model.conv4_ = torch.nn.Conv2d(256, TOTAL_CHANNELS, bias=True, kernel_size=1)
    model.upsample = torch.nn.ConvTranspose2d(
        TOTAL_CHANNELS,
        TOTAL_CHANNELS,
        kernel_size=4,
        stride=4,
    )

    checkpoint_payload = torch.load(checkpoint, map_location=device)
    if not isinstance(checkpoint_payload, dict):
        raise ValueError("Unexpected CubiCasa checkpoint payload")
    state = checkpoint_payload.get("model_state")
    if not isinstance(state, dict):
        raise ValueError("CubiCasa checkpoint is missing model_state")
    model.load_state_dict(state, strict=True)
    model.to(device)
    model.eval()
    return torch, model, device


def tensor_from_rgb(torch_module, image_rgb: np.ndarray, device):
    # Official FloorTrans preprocessing maps RGB uint8 [0,255] to float [-1,1].
    normalized = 2.0 * (image_rgb.astype(np.float32) / 255.0) - 1.0
    return (
        torch_module.from_numpy(normalized)
        .permute(2, 0, 1)
        .unsqueeze(0)
        .to(device)
    )


def predict_room_icon_probabilities(
    torch_module,
    model,
    device,
    image_rgb: np.ndarray,
    rotations: int,
) -> tuple[np.ndarray, np.ndarray]:
    if rotations not in (1, 4):
        raise ValueError("rotations must be 1 or 4")
    image = tensor_from_rgb(torch_module, image_rgb, device)
    room_sum = None
    icon_sum = None
    ks = (0,) if rotations == 1 else (0, 1, 2, 3)

    with torch_module.inference_mode():
        for k in ks:
            rotated = torch_module.rot90(image, k, dims=(-2, -1)) if k else image
            prediction = model(rotated)
            if prediction.ndim != 4 or prediction.shape[1] != TOTAL_CHANNELS:
                raise ValueError(f"Unexpected FloorTrans output shape: {tuple(prediction.shape)}")
            if k:
                prediction = torch_module.rot90(prediction, -k, dims=(-2, -1))
            if prediction.shape[-2:] != image.shape[-2:]:
                prediction = torch_module.nn.functional.interpolate(
                    prediction,
                    size=image.shape[-2:],
                    mode="bilinear",
                    align_corners=False,
                )

            room_logits = prediction[:, HEATMAP_CHANNELS:HEATMAP_CHANNELS + ROOM_CHANNELS]
            icon_logits = prediction[:, HEATMAP_CHANNELS + ROOM_CHANNELS:]
            room_probs = torch_module.softmax(room_logits, dim=1)[0]
            icon_probs = torch_module.softmax(icon_logits, dim=1)[0]
            room_sum = room_probs if room_sum is None else room_sum + room_probs
            icon_sum = icon_probs if icon_sum is None else icon_sum + icon_probs

    assert room_sum is not None and icon_sum is not None
    room_average = (room_sum / float(len(ks))).detach().cpu().numpy().astype(np.float32)
    icon_average = (icon_sum / float(len(ks))).detach().cpu().numpy().astype(np.float32)
    return room_average, icon_average


def destination_for(source: pathlib.Path, input_root: pathlib.Path, output_root: pathlib.Path) -> pathlib.Path:
    relative = source.relative_to(input_root)
    return (output_root / relative).with_suffix(".npz")


def save_prediction(
    destination: pathlib.Path,
    image_rgb: np.ndarray,
    room_probs: np.ndarray,
    icon_probs: np.ndarray,
) -> None:
    semantic_probs, confidence, valid_mask = encode_floortrans_probabilities(room_probs, icon_probs)
    if semantic_probs.shape[1:] != image_rgb.shape[:2]:
        raise ValueError("FloorTrans probabilities must match source image dimensions before export")

    destination.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(
        destination,
        image=image_rgb,
        semantic_probs=semantic_probs,
        semantic_classes=np.asarray(LOCAL_CLASSES, dtype="U32"),
        confidence=confidence,
        valid_mask=valid_mask,
        teacher_format=np.asarray(["cubicasa-floortrans-room-icon-v2-boundary"], dtype="U64"),
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=pathlib.Path,
        required=True,
        help="Source image directory; Raster2Seq's transformed-image root is recommended for alignment",
    )
    parser.add_argument("--repository", type=pathlib.Path, default=DEFAULT_REPOSITORY)
    parser.add_argument("--checkpoint", type=pathlib.Path, default=None)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--device", choices=("auto", "cpu", "cuda"), default="auto")
    parser.add_argument(
        "--rotations",
        type=int,
        choices=(1, 4),
        default=4,
        help="4 enables upstream-style rotation TTA for more stable room/icon probabilities",
    )
    parser.add_argument("--recursive", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.input.is_dir():
        raise FileNotFoundError(f"Input directory does not exist: {args.input}")
    checkpoint = args.checkpoint or (args.repository / DEFAULT_CHECKPOINT_NAME)
    images = discover_images(args.input, recursive=args.recursive)
    torch_module, model, device = load_model(args.repository, checkpoint, args.device)

    written = 0
    for source in images:
        image_rgb = read_rgb(source)
        room_probs, icon_probs = predict_room_icon_probabilities(
            torch_module=torch_module,
            model=model,
            device=device,
            image_rgb=image_rgb,
            rotations=args.rotations,
        )
        save_prediction(
            destination=destination_for(source, args.input, args.output),
            image_rgb=image_rgb,
            room_probs=room_probs,
            icon_probs=icon_probs,
        )
        written += 1

    print(f"CubiCasa teacher samples written: {written}")
    print("output:", args.output)
    print("semantic classes:", ", ".join(LOCAL_CLASSES))
    print("all other Manzl classes: abstain")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
