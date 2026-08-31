#!/usr/bin/env python3
"""Train/export the rights-safe Manzl reconstruction student.

The student is deliberately built from standard ONNX-friendly operators. Heavy teachers such as
Raster2Seq and RoomFormer are not shipped in Android; their consensus can be converted into NPZ
training samples and distilled here on any free GPU notebook. The script never calls a cloud API.

Expected NPZ sample keys:
  image        uint8/float32 [H,W,3]
  semantic     int64         [H,W]
  corners      float32       [H,W]      (optional)
  orientation  float32       [2,H,W]    (optional, wall tangent cos/sin)
  wall_mask    float32       [H,W]      (optional orientation supervision mask)

Semantic class ids are fixed by SEMANTIC_CLASSES below so Android decoding stays deterministic.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import random
from dataclasses import dataclass
from typing import Iterable

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
        with np.load(self.files[index]) as sample:
            image = np.asarray(sample["image"])
            semantic = np.asarray(sample["semantic"], dtype=np.int64)
            corners = np.asarray(sample["corners"], dtype=np.float32) if "corners" in sample else np.zeros_like(semantic, dtype=np.float32)
            orientation = np.asarray(sample["orientation"], dtype=np.float32) if "orientation" in sample else np.zeros((2, *semantic.shape), dtype=np.float32)
            wall_mask = np.asarray(sample["wall_mask"], dtype=np.float32) if "wall_mask" in sample else (semantic == 1).astype(np.float32)

        if image.ndim != 3 or image.shape[-1] != 3:
            raise ValueError(f"Invalid image shape in {self.files[index]}: {image.shape}")
        image = image.astype(np.float32)
        if image.max() > 1.5:
            image /= 255.0

        image_t = torch.from_numpy(image).permute(2, 0, 1)
        semantic_t = torch.from_numpy(semantic).unsqueeze(0).float()
        corners_t = torch.from_numpy(corners).unsqueeze(0)
        orientation_t = torch.from_numpy(orientation)
        wall_mask_t = torch.from_numpy(wall_mask).unsqueeze(0)

        packed = torch.cat(
            [image_t, semantic_t, corners_t, orientation_t, wall_mask_t],
            dim=0,
        ).unsqueeze(0)
        packed = F.interpolate(packed, size=(self.size, self.size), mode="nearest").squeeze(0)

        image_t = packed[0:3]
        semantic_t = packed[3].long()
        corners_t = packed[4:5].clamp(0, 1)
        orientation_t = packed[5:7]
        wall_mask_t = packed[7:8].clamp(0, 1)

        if self.augment:
            image_t, semantic_t, corners_t, orientation_t, wall_mask_t = self._augment(
                image_t, semantic_t, corners_t, orientation_t, wall_mask_t
            )

        # Normalize natural/screenshot inputs without relying on ImageNet weights.
        image_t = (image_t - 0.5) / 0.5
        return {
            "image": image_t,
            "semantic": semantic_t,
            "corners": corners_t,
            "orientation": orientation_t,
            "wall_mask": wall_mask_t,
        }

    def _augment(self, image, semantic, corners, orientation, wall_mask):
        # 90-degree rotations preserve exact raster labels and expose every wall direction.
        k = random.randrange(4)
        if k:
            image = torch.rot90(image, k, dims=(-2, -1))
            semantic = torch.rot90(semantic, k, dims=(-2, -1))
            corners = torch.rot90(corners, k, dims=(-2, -1))
            orientation = torch.rot90(orientation, k, dims=(-2, -1))
            wall_mask = torch.rot90(wall_mask, k, dims=(-2, -1))
            for _ in range(k):
                x, y = orientation[0].clone(), orientation[1].clone()
                orientation[0] = -y
                orientation[1] = x

        if random.random() < 0.5:
            image = torch.flip(image, dims=(-1,))
            semantic = torch.flip(semantic, dims=(-1,))
            corners = torch.flip(corners, dims=(-1,))
            orientation = torch.flip(orientation, dims=(-1,))
            orientation[0] *= -1
            wall_mask = torch.flip(wall_mask, dims=(-1,))

        # Photometric changes simulate scans, screenshots and different blueprint backgrounds while
        # leaving geometry labels untouched.
        gain = random.uniform(0.78, 1.18)
        bias = random.uniform(-0.08, 0.08)
        noise = torch.randn_like(image) * random.uniform(0.0, 0.035)
        image = (image * gain + bias + noise).clamp(0, 1)
        return image, semantic, corners, orientation, wall_mask


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


def compute_loss(outputs, batch, weights: LossWeights):
    semantic_logits, corner_logits, orientation = outputs
    semantic_target = batch["semantic"]
    corner_target = batch["corners"]
    orientation_target = batch["orientation"]
    wall_mask = batch["wall_mask"]

    class_weights = torch.tensor(
        [0.45, 1.45, 2.2, 2.2, 2.0, 2.0, 1.35, 1.7, 1.8],
        device=semantic_logits.device,
    )
    semantic_loss = F.cross_entropy(semantic_logits, semantic_target, weight=class_weights)
    corner_loss = F.binary_cross_entropy_with_logits(corner_logits, corner_target)

    target_norm = F.normalize(orientation_target, dim=1, eps=1e-6)
    cosine = (orientation * target_norm).sum(dim=1, keepdim=True).abs()
    orientation_loss = ((1.0 - cosine) * wall_mask).sum() / wall_mask.sum().clamp_min(1.0)

    total = (
        semantic_loss * weights.semantic
        + corner_loss * weights.corners
        + orientation_loss * weights.orientation
    )
    return total, {
        "semantic": semantic_loss.detach().item(),
        "corners": corner_loss.detach().item(),
        "orientation": orientation_loss.detach().item(),
    }


def train(args) -> None:
    torch.manual_seed(args.seed)
    random.seed(args.seed)
    np.random.seed(args.seed)

    device = torch.device("cuda" if torch.cuda.is_available() and not args.cpu else "cpu")
    print("device:", device)
    dataset = FloorPlanDataset(args.data, args.size, augment=True)
    loader = DataLoader(
        dataset,
        batch_size=args.batch,
        shuffle=True,
        num_workers=args.workers,
        pin_memory=device.type == "cuda",
        drop_last=len(dataset) >= args.batch,
    )
    model = ManzlReconstructionStudent(width=args.width).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=1e-4)
    scaler = torch.amp.GradScaler("cuda", enabled=device.type == "cuda")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    best = math.inf
    for epoch in range(1, args.epochs + 1):
        model.train()
        running = 0.0
        for step, batch in enumerate(loader, start=1):
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

        mean_loss = running / max(1, len(loader))
        print(f"epoch={epoch} mean_loss={mean_loss:.5f}")
        torch.save({"model": model.state_dict(), "classes": SEMANTIC_CLASSES, "size": args.size}, args.output)
        if mean_loss < best:
            best = mean_loss
            torch.save({"model": model.state_dict(), "classes": SEMANTIC_CLASSES, "size": args.size}, args.output.with_suffix(".best.pt"))

    export_onnx(model.eval().cpu(), args.onnx, args.size)
    metadata = {
        "semanticClasses": SEMANTIC_CLASSES,
        "inputSize": args.size,
        "width": args.width,
        "bestTrainLoss": best,
        "source": "Manzl teacher-consensus/rights-safe training pipeline",
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
    parser.add_argument("--output", type=pathlib.Path, default=pathlib.Path("research/ultra-reconstruction/checkpoints/student.pt"))
    parser.add_argument("--onnx", type=pathlib.Path, default=pathlib.Path("research/ultra-reconstruction/checkpoints/manzl_reconstruction_student.onnx"))
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--width", type=int, default=32)
    parser.add_argument("--epochs", type=int, default=40)
    parser.add_argument("--batch", type=int, default=4)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--seed", type=int, default=439)
    parser.add_argument("--log-every", type=int, default=20)
    parser.add_argument("--cpu", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    train(parse_args())
