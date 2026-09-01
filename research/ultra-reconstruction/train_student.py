#!/usr/bin/env python3
"""Train/export the rights-safe Manzl reconstruction student.

Heavy teachers are development-time only. Their predictions are first passed through
``build_teacher_consensus.py``; disagreement becomes an explicit supervision mask instead of a fake
averaged label. This trainer consumes those masks so uncertain teacher regions do not teach the mobile
student incorrect geometry.

Expected NPZ keys:
  image                 uint8/float32 [H,W,3]
  semantic              int64         [H,W]
  semantic_confidence   float32       [H,W]      optional, defaults to 1
  supervision_mask      float32       [H,W]      optional, defaults to 1 (for true ground truth)
  corners               float32       [H,W]      optional
  corner_mask           float32       [H,W]      optional, defaults to supervision_mask
  orientation           float32       [2,H,W]    optional, wall tangent cos/sin
  wall_mask              float32       [H,W]      optional, defaults to semantic==wall_face
  orientation_mask      float32       [H,W]      optional, defaults to wall_mask*supervision_mask

Semantic class ids are fixed by SEMANTIC_CLASSES so Android decoding stays deterministic.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import random
from dataclasses import dataclass

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from torch.utils.data import DataLoader, Dataset

SEMANTIC_CLASSES = [
    "background",
    "wall_face",
    "door",
    "window",
    "stair",
    "column",
    "room_boundary",
    "courtyard",
    "shaft",
]


class FloorPlanDataset(Dataset):
    def __init__(self, root: pathlib.Path, size: int, augment: bool) -> None:
        self.files = sorted(root.rglob("*.npz"))
        if not self.files:
            raise RuntimeError(f"No .npz training samples found under {root}")
        self.size = size
        self.augment = augment

    def __len__(self) -> int:
        return len(self.files)

    def __getitem__(self, index: int) -> dict[str, torch.Tensor]:
        with np.load(self.files[index], allow_pickle=False) as sample:
            image = np.asarray(sample["image"])
            semantic = np.asarray(sample["semantic"], dtype=np.int64)
            confidence = np.asarray(sample["semantic_confidence"], dtype=np.float32) if "semantic_confidence" in sample else np.ones_like(semantic, dtype=np.float32)
            supervision = np.asarray(sample["supervision_mask"], dtype=np.float32) if "supervision_mask" in sample else np.ones_like(semantic, dtype=np.float32)
            corners = np.asarray(sample["corners"], dtype=np.float32) if "corners" in sample else np.zeros_like(semantic, dtype=np.float32)
            corner_mask = np.asarray(sample["corner_mask"], dtype=np.float32) if "corner_mask" in sample else supervision.copy()
            orientation = np.asarray(sample["orientation"], dtype=np.float32) if "orientation" in sample else np.zeros((2, *semantic.shape), dtype=np.float32)
            wall_mask = np.asarray(sample["wall_mask"], dtype=np.float32) if "wall_mask" in sample else (semantic == 1).astype(np.float32)
            orientation_mask = np.asarray(sample["orientation_mask"], dtype=np.float32) if "orientation_mask" in sample else wall_mask * supervision

        if image.ndim != 3 or image.shape[-1] != 3:
            raise ValueError(f"Invalid image shape in {self.files[index]}: {image.shape}")
        expected = semantic.shape
        for name, value in {
            "semantic_confidence": confidence,
            "supervision_mask": supervision,
            "corners": corners,
            "corner_mask": corner_mask,
            "wall_mask": wall_mask,
            "orientation_mask": orientation_mask,
        }.items():
            if value.shape != expected:
                raise ValueError(f"{name} must match semantic shape in {self.files[index]}: {value.shape} != {expected}")
        if orientation.shape != (2, *expected):
            raise ValueError(f"orientation must be [2,H,W] in {self.files[index]}, got {orientation.shape}")
        if image.shape[:2] != expected:
            raise ValueError(f"image/semantic spatial mismatch in {self.files[index]}")

        image = image.astype(np.float32)
        if image.max() > 1.5:
            image /= 255.0

        image_t = torch.from_numpy(image).permute(2, 0, 1).unsqueeze(0)
        image_t = F.interpolate(image_t, size=(self.size, self.size), mode="bilinear", align_corners=False).squeeze(0)

        def resize_scalar(value: np.ndarray, mode: str = "nearest") -> torch.Tensor:
            tensor = torch.from_numpy(value).float().unsqueeze(0).unsqueeze(0)
            if mode == "bilinear":
                return F.interpolate(tensor, size=(self.size, self.size), mode=mode, align_corners=False).squeeze(0)
            return F.interpolate(tensor, size=(self.size, self.size), mode=mode).squeeze(0)

        semantic_t = resize_scalar(semantic.astype(np.float32), "nearest")[0].long()
        confidence_t = resize_scalar(confidence, "bilinear").clamp(0, 1)
        supervision_t = resize_scalar(supervision, "nearest").clamp(0, 1)
        corners_t = resize_scalar(corners, "bilinear").clamp(0, 1)
        corner_mask_t = resize_scalar(corner_mask, "nearest").clamp(0, 1)
        wall_mask_t = resize_scalar(wall_mask, "nearest").clamp(0, 1)
        orientation_mask_t = resize_scalar(orientation_mask, "nearest").clamp(0, 1)
        orientation_t = torch.from_numpy(orientation).float().unsqueeze(0)
        orientation_t = F.interpolate(orientation_t, size=(self.size, self.size), mode="bilinear", align_corners=False).squeeze(0)
        orientation_t = F.normalize(orientation_t, dim=0, eps=1e-6)

        if self.augment:
            (
                image_t,
                semantic_t,
                confidence_t,
                supervision_t,
                corners_t,
                corner_mask_t,
                orientation_t,
                wall_mask_t,
                orientation_mask_t,
            ) = self._augment(
                image_t,
                semantic_t,
                confidence_t,
                supervision_t,
                corners_t,
                corner_mask_t,
                orientation_t,
                wall_mask_t,
                orientation_mask_t,
            )

        # No ImageNet dependency; preserve blueprint/scan colour information and normalize locally.
        image_t = (image_t - 0.5) / 0.5
        return {
            "image": image_t,
            "semantic": semantic_t,
            "semantic_confidence": confidence_t,
            "supervision_mask": supervision_t,
            "corners": corners_t,
            "corner_mask": corner_mask_t,
            "orientation": orientation_t,
            "wall_mask": wall_mask_t,
            "orientation_mask": orientation_mask_t,
        }

    def _augment(
        self,
        image,
        semantic,
        confidence,
        supervision,
        corners,
        corner_mask,
        orientation,
        wall_mask,
        orientation_mask,
    ):
        # 90-degree rotations preserve exact topology while exposing every wall direction.
        k = random.randrange(4)
        if k:
            tensors = [image, semantic, confidence, supervision, corners, corner_mask, orientation, wall_mask, orientation_mask]
            tensors = [torch.rot90(value, k, dims=(-2, -1)) for value in tensors]
            image, semantic, confidence, supervision, corners, corner_mask, orientation, wall_mask, orientation_mask = tensors
            for _ in range(k):
                x, y = orientation[0].clone(), orientation[1].clone()
                orientation[0] = -y
                orientation[1] = x

        if random.random() < 0.5:
            image = torch.flip(image, dims=(-1,))
            semantic = torch.flip(semantic, dims=(-1,))
            confidence = torch.flip(confidence, dims=(-1,))
            supervision = torch.flip(supervision, dims=(-1,))
            corners = torch.flip(corners, dims=(-1,))
            corner_mask = torch.flip(corner_mask, dims=(-1,))
            orientation = torch.flip(orientation, dims=(-1,))
            orientation[0] *= -1
            wall_mask = torch.flip(wall_mask, dims=(-1,))
            orientation_mask = torch.flip(orientation_mask, dims=(-1,))

        # Scan/screenshot robustness without changing geometry labels.
        gain = random.uniform(0.78, 1.18)
        bias = random.uniform(-0.08, 0.08)
        noise = torch.randn_like(image) * random.uniform(0.0, 0.035)
        image = (image * gain + bias + noise).clamp(0, 1)
        return image, semantic, confidence, supervision, corners, corner_mask, orientation, wall_mask, orientation_mask


class ConvBlock(nn.Module):
    def __init__(self, in_channels: int, out_channels: int) -> None:
        super().__init__()
        self.net = nn.Sequential(
            nn.Conv2d(in_channels, out_channels, 3, padding=1, bias=False),
            nn.BatchNorm2d(out_channels),
            nn.SiLU(inplace=True),
            nn.Conv2d(out_channels, out_channels, 3, padding=1, bias=False),
            nn.BatchNorm2d(out_channels),
            nn.SiLU(inplace=True),
        )
        self.skip = nn.Conv2d(in_channels, out_channels, 1, bias=False) if in_channels != out_channels else nn.Identity()

    def forward(self, x):
        return self.net(x) + self.skip(x)


class Down(nn.Module):
    def __init__(self, in_channels: int, out_channels: int) -> None:
        super().__init__()
        self.pool = nn.MaxPool2d(2)
        self.block = ConvBlock(in_channels, out_channels)

    def forward(self, x):
        return self.block(self.pool(x))


class Up(nn.Module):
    def __init__(self, in_channels: int, skip_channels: int, out_channels: int) -> None:
        super().__init__()
        self.reduce = nn.Conv2d(in_channels, out_channels, 1)
        self.block = ConvBlock(out_channels + skip_channels, out_channels)

    def forward(self, x, skip):
        x = F.interpolate(x, size=skip.shape[-2:], mode="bilinear", align_corners=False)
        x = self.reduce(x)
        return self.block(torch.cat([x, skip], dim=1))


class ManzlReconstructionStudent(nn.Module):
    """Multi-head U-Net student using only mobile/export-friendly operators."""

    def __init__(self, width: int = 32, classes: int = len(SEMANTIC_CLASSES)) -> None:
        super().__init__()
        self.stem = ConvBlock(3, width)
        self.d1 = Down(width, width * 2)
        self.d2 = Down(width * 2, width * 4)
        self.d3 = Down(width * 4, width * 8)
        self.d4 = Down(width * 8, width * 12)
        self.u3 = Up(width * 12, width * 8, width * 8)
        self.u2 = Up(width * 8, width * 4, width * 4)
        self.u1 = Up(width * 4, width * 2, width * 2)
        self.u0 = Up(width * 2, width, width)
        self.semantic_head = nn.Conv2d(width, classes, 1)
        self.corner_head = nn.Conv2d(width, 1, 1)
        self.orientation_head = nn.Conv2d(width, 2, 1)

    def forward(self, image):
        s0 = self.stem(image)
        s1 = self.d1(s0)
        s2 = self.d2(s1)
        s3 = self.d3(s2)
        x = self.d4(s3)
        x = self.u3(x, s3)
        x = self.u2(x, s2)
        x = self.u1(x, s1)
        x = self.u0(x, s0)
        semantic = self.semantic_head(x)
        corners = self.corner_head(x)
        orientation = F.normalize(self.orientation_head(x), dim=1, eps=1e-6)
        return semantic, corners, orientation


@dataclass
class LossWeights:
    semantic: float = 1.0
    corners: float = 1.8
    orientation: float = 0.35


def masked_mean(values: torch.Tensor, mask: torch.Tensor) -> torch.Tensor:
    weighted = values * mask
    return weighted.sum() / mask.sum().clamp_min(1.0)


def compute_loss(outputs, batch, weights: LossWeights):
    semantic_logits, corner_logits, orientation = outputs
    semantic_target = batch["semantic"]
    corner_target = batch["corners"]
    orientation_target = batch["orientation"]
    supervision = batch["supervision_mask"] * batch["semantic_confidence"]
    corner_mask = batch["corner_mask"]
    orientation_mask = batch["orientation_mask"] * batch["wall_mask"]

    class_weights = torch.tensor(
        [0.45, 1.45, 2.2, 2.2, 2.0, 2.0, 1.35, 1.7, 1.8],
        device=semantic_logits.device,
    )
    semantic_map = F.cross_entropy(
        semantic_logits,
        semantic_target,
        weight=class_weights,
        reduction="none",
    ).unsqueeze(1)
    semantic_loss = masked_mean(semantic_map, supervision)

    corner_map = F.binary_cross_entropy_with_logits(corner_logits, corner_target, reduction="none")
    corner_loss = masked_mean(corner_map, corner_mask)

    target_norm = F.normalize(orientation_target, dim=1, eps=1e-6)
    cosine = (orientation * target_norm).sum(dim=1, keepdim=True).abs()
    orientation_loss = masked_mean(1.0 - cosine, orientation_mask)

    total = (
        semantic_loss * weights.semantic
        + corner_loss * weights.corners
        + orientation_loss * weights.orientation
    )
    return total, {
        "semantic": semantic_loss.detach().item(),
        "corners": corner_loss.detach().item(),
        "orientation": orientation_loss.detach().item(),
        "supervised_fraction": batch["supervision_mask"].detach().mean().item(),
    }


@torch.no_grad()
def evaluate(model: nn.Module, loader: DataLoader, device: torch.device) -> dict[str, float]:
    model.eval()
    total_loss = 0.0
    batches = 0
    intersections = torch.zeros(len(SEMANTIC_CLASSES), device=device)
    unions = torch.zeros(len(SEMANTIC_CLASSES), device=device)

    for batch in loader:
        batch = {key: value.to(device, non_blocking=True) for key, value in batch.items()}
        outputs = model(batch["image"])
        loss, _ = compute_loss(outputs, batch, LossWeights())
        total_loss += float(loss.item())
        batches += 1

        predicted = outputs[0].argmax(dim=1)
        target = batch["semantic"]
        valid = batch["supervision_mask"][:, 0] > 0.5
        for class_index in range(len(SEMANTIC_CLASSES)):
            pred_class = (predicted == class_index) & valid
            target_class = (target == class_index) & valid
            intersections[class_index] += (pred_class & target_class).sum()
            unions[class_index] += (pred_class | target_class).sum()

    iou = torch.where(unions > 0, intersections / unions.clamp_min(1), torch.nan)
    mean_iou = float(torch.nanmean(iou).item()) if torch.isfinite(iou).any() else 0.0
    return {
        "loss": total_loss / max(1, batches),
        "mean_iou": mean_iou,
    }


def make_loader(dataset: FloorPlanDataset, batch: int, workers: int, shuffle: bool, device: torch.device) -> DataLoader:
    return DataLoader(
        dataset,
        batch_size=batch,
        shuffle=shuffle,
        num_workers=workers,
        pin_memory=device.type == "cuda",
        drop_last=shuffle and len(dataset) >= batch,
    )


def train(args) -> None:
    torch.manual_seed(args.seed)
    random.seed(args.seed)
    np.random.seed(args.seed)

    device = torch.device("cuda" if torch.cuda.is_available() and not args.cpu else "cpu")
    print("device:", device)
    train_dataset = FloorPlanDataset(args.data, args.size, augment=True)
    train_loader = make_loader(train_dataset, args.batch, args.workers, True, device)
    val_loader = None
    if args.val_data is not None:
        val_dataset = FloorPlanDataset(args.val_data, args.size, augment=False)
        val_loader = make_loader(val_dataset, args.batch, args.workers, False, device)

    model = ManzlReconstructionStudent(width=args.width).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    scaler = torch.amp.GradScaler("cuda", enabled=device.type == "cuda")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    best_metric = math.inf
    best_epoch = 0
    epochs_without_improvement = 0

    for epoch in range(1, args.epochs + 1):
        model.train()
        running = 0.0
        for step, batch in enumerate(train_loader, start=1):
            batch = {key: value.to(device, non_blocking=True) for key, value in batch.items()}
            optimizer.zero_grad(set_to_none=True)
            with torch.autocast(device_type=device.type, enabled=device.type == "cuda"):
                loss, parts = compute_loss(model(batch["image"]), batch, LossWeights())
            scaler.scale(loss).backward()
            scaler.unscale_(optimizer)
            torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=4.0)
            scaler.step(optimizer)
            scaler.update()
            running += loss.item()
            if step % args.log_every == 0:
                print(f"epoch={epoch} step={step} loss={loss.item():.4f} parts={parts}")

        mean_train_loss = running / max(1, len(train_loader))
        validation = evaluate(model, val_loader, device) if val_loader is not None else None
        selection_metric = validation["loss"] if validation is not None else mean_train_loss
        print(
            f"epoch={epoch} train_loss={mean_train_loss:.5f}" +
            (f" val_loss={validation['loss']:.5f} val_mIoU={validation['mean_iou']:.4f}" if validation else "")
        )

        torch.save({"model": model.state_dict(), "classes": SEMANTIC_CLASSES, "size": args.size}, args.output)
        if selection_metric < best_metric - args.min_improvement:
            best_metric = selection_metric
            best_epoch = epoch
            epochs_without_improvement = 0
            torch.save({"model": model.state_dict(), "classes": SEMANTIC_CLASSES, "size": args.size}, args.output.with_suffix(".best.pt"))
        else:
            epochs_without_improvement += 1
            if val_loader is not None and epochs_without_improvement >= args.patience:
                print(f"early-stop at epoch={epoch}; best_epoch={best_epoch}")
                break

    best_path = args.output.with_suffix(".best.pt")
    checkpoint = torch.load(best_path, map_location="cpu")
    model.load_state_dict(checkpoint["model"])
    model.eval().cpu()
    export_onnx(model, args.onnx, args.size)

    final_validation = evaluate(model.to(device), val_loader, device) if val_loader is not None else None
    metadata = {
        "semanticClasses": SEMANTIC_CLASSES,
        "inputSize": args.size,
        "width": args.width,
        "bestSelectionLoss": best_metric,
        "bestEpoch": best_epoch,
        "validation": final_validation,
        "source": "Manzl fail-closed multi-teacher consensus training pipeline",
        "uncertaintyPolicy": "teacher disagreement is masked from supervision; geometry-critical pseudo-labels require corroboration",
    }
    args.onnx.with_suffix(".json").write_text(json.dumps(metadata, indent=2), encoding="utf-8")
    print("exported:", args.onnx)


def export_onnx(model: nn.Module, destination: pathlib.Path, size: int) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    example = torch.zeros(1, 3, size, size, dtype=torch.float32)
    torch.onnx.export(
        model,
        example,
        destination,
        input_names=["image"],
        output_names=["semantic_logits", "corner_logits", "wall_orientation"],
        opset_version=18,
        do_constant_folding=True,
    )


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", type=pathlib.Path, required=True)
    parser.add_argument("--val-data", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("research/ultra-reconstruction/checkpoints/student.pt"))
    parser.add_argument("--onnx", type=pathlib.Path, default=pathlib.Path("research/ultra-reconstruction/checkpoints/manzl_reconstruction_student.onnx"))
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--width", type=int, default=32)
    parser.add_argument("--epochs", type=int, default=60)
    parser.add_argument("--batch", type=int, default=4)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--seed", type=int, default=439)
    parser.add_argument("--log-every", type=int, default=20)
    parser.add_argument("--patience", type=int, default=8)
    parser.add_argument("--min-improvement", type=float, default=1e-4)
    parser.add_argument("--cpu", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    train(parse_args())
