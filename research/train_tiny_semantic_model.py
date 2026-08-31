"""Development-only trainer for Manzl's tiny semantic patch model.

The dataset is generated procedurally in memory. No external floor-plan images or pretrained weights
are used. The Android app does not depend on Python or PyTorch at runtime.
"""

from __future__ import annotations

import base64
import math
import random
from dataclasses import dataclass

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
import torch
from torch import nn

SIZE = 16
CLASSES = ("OTHER", "DOOR", "WINDOW", "STAIR")
SEED = 20260831

random.seed(SEED)
np.random.seed(SEED)
torch.manual_seed(SEED)


def _blank() -> Image.Image:
    return Image.new("L", (SIZE, SIZE), 255)


def _ink_array(image: Image.Image, noise: float = 0.025) -> np.ndarray:
    arr = np.asarray(image, dtype=np.float32) / 255.0
    arr = np.clip(arr + np.random.normal(0.0, noise, arr.shape), 0.0, 1.0)
    if random.random() < 0.35:
        for _ in range(random.randint(1, 5)):
            arr[random.randrange(SIZE), random.randrange(SIZE)] = random.choice((0.0, 1.0))
    return (1.0 - arr).astype(np.float32)


def _rotate(image: Image.Image) -> Image.Image:
    return image.rotate(random.randrange(4) * 90, resample=Image.Resampling.NEAREST)


def door_patch() -> np.ndarray:
    image = _blank()
    draw = ImageDraw.Draw(image)
    hinge_x = 3 + random.randint(-2, 1)
    hinge_y = 12 + random.randint(-1, 2)
    radius = random.randint(7, 10)
    width = random.choice((1, 1, 1, 2))
    draw.line((hinge_x, hinge_y, min(SIZE - 2, hinge_x + radius), hinge_y), fill=0, width=width)
    draw.arc((hinge_x - radius, hinge_y - radius, hinge_x + radius, hinge_y + radius), 270, 360, fill=0, width=width)
    draw.line((hinge_x, max(0, hinge_y - 2), hinge_x, min(SIZE - 1, hinge_y + 2)), fill=0, width=width)
    image = _rotate(image)
    if random.random() < 0.25:
        image = image.filter(ImageFilter.GaussianBlur(random.uniform(0.2, 0.55)))
    return _ink_array(image)


def window_patch() -> np.ndarray:
    image = _blank()
    draw = ImageDraw.Draw(image)
    y = SIZE // 2 + random.randint(-2, 2)
    separation = random.randint(2, 4)
    x0, x1 = random.randint(1, 3), random.randint(12, 15)
    width = random.choice((1, 1, 2))
    draw.line((x0, y - separation // 2, x1, y - separation // 2), fill=0, width=width)
    draw.line((x0, y + (separation + 1) // 2, x1, y + (separation + 1) // 2), fill=0, width=width)
    if random.random() < 0.60:
        draw.line((0, y, x0 - 1, y), fill=0, width=random.choice((1, 2)))
        draw.line((x1 + 1, y, SIZE - 1, y), fill=0, width=random.choice((1, 2)))
    image = _rotate(image)
    if random.random() < 0.20:
        image = image.filter(ImageFilter.GaussianBlur(random.uniform(0.2, 0.50)))
    return _ink_array(image)


def stair_patch() -> np.ndarray:
    image = _blank()
    draw = ImageDraw.Draw(image)
    count = random.randint(5, 9)
    y0, y1 = random.randint(2, 4), random.randint(11, 14)
    x0, x1 = random.randint(2, 4), random.randint(11, 14)
    width = random.choice((1, 1, 1, 2))
    for y in np.linspace(y0, y1, count).astype(int):
        jitter = random.choice((-1, 0, 0, 0, 1))
        draw.line((x0 + jitter, int(y), x1 + jitter, int(y)), fill=0, width=width)
    if random.random() < 0.35:
        draw.line((x0, y0, x0, y1), fill=0, width=1)
    image = _rotate(image)
    if random.random() < 0.20:
        image = image.filter(ImageFilter.GaussianBlur(random.uniform(0.2, 0.50)))
    return _ink_array(image)


def other_patch() -> np.ndarray:
    image = _blank()
    draw = ImageDraw.Draw(image)
    variant = random.randrange(6)
    width = random.choice((1, 1, 2))
    if variant == 1:
        if random.random() < 0.5:
            y = random.randrange(2, 14)
            draw.line((0, y, SIZE - 1, y), fill=0, width=width)
        else:
            x = random.randrange(2, 14)
            draw.line((x, 0, x, SIZE - 1), fill=0, width=width)
    elif variant == 2:
        x, y = random.randrange(3, 13), random.randrange(3, 13)
        draw.line((0, y, SIZE - 1, y), fill=0, width=width)
        draw.line((x, 0, x, SIZE - 1), fill=0, width=width)
    elif variant == 3:
        draw.rectangle((random.randrange(1, 6), random.randrange(1, 6), random.randrange(10, 15), random.randrange(10, 15)), outline=0, width=width)
    elif variant == 4:
        for _ in range(random.randint(3, 8)):
            x, y = random.randrange(1, 14), random.randrange(1, 14)
            draw.line((x, y, min(15, x + random.randrange(1, 5)), y + random.choice((-1, 0, 1))), fill=0, width=1)
    elif variant == 5:
        draw.line((random.randrange(0, 5), random.randrange(0, 5), random.randrange(11, 16), random.randrange(11, 16)), fill=0, width=width)
    return _ink_array(_rotate(image), noise=0.035)


GENERATORS = (other_patch, door_patch, window_patch, stair_patch)


def make_dataset(size: int) -> tuple[np.ndarray, np.ndarray]:
    x, y = [], []
    for i in range(size):
        label = i % len(CLASSES)
        x.append(GENERATORS[label]().reshape(-1))
        y.append(label)
    order = np.random.permutation(size)
    return np.stack(x)[order], np.asarray(y, dtype=np.int64)[order]


class TinyModel(nn.Module):
    def __init__(self) -> None:
        super().__init__()
        self.layers = nn.Sequential(nn.Linear(256, 16), nn.ReLU(), nn.Linear(16, 4))

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.layers(x)


def quantize_symmetric(weight: np.ndarray) -> tuple[np.ndarray, float]:
    scale = float(np.max(np.abs(weight)) / 127.0)
    quantized = np.clip(np.round(weight / scale), -127, 127).astype(np.int8)
    return quantized, scale


def train() -> None:
    train_x, train_y = make_dataset(12_000)
    test_x, test_y = make_dataset(2_400)
    model = TinyModel()
    optimizer = torch.optim.Adam(model.parameters(), lr=2e-3)
    loss_fn = nn.CrossEntropyLoss()
    x_tensor, y_tensor = torch.from_numpy(train_x), torch.from_numpy(train_y)

    for _ in range(18):
        for start in range(0, len(x_tensor), 256):
            batch_x = x_tensor[start : start + 256]
            batch_y = y_tensor[start : start + 256]
            loss = loss_fn(model(batch_x), batch_y)
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()

    with torch.no_grad():
        prediction = model(torch.from_numpy(test_x)).argmax(1).numpy()
    print(f"synthetic held-out accuracy: {(prediction == test_y).mean():.4f}")

    first: nn.Linear = model.layers[0]  # type: ignore[assignment]
    second: nn.Linear = model.layers[2]  # type: ignore[assignment]
    q1, s1 = quantize_symmetric(first.weight.detach().numpy())
    q2, s2 = quantize_symmetric(second.weight.detach().numpy())
    print("layer1 scale", s1)
    print("layer2 scale", s2)
    print("layer1 base64", base64.b64encode(q1.tobytes()).decode())
    print("layer2 base64", base64.b64encode(q2.tobytes()).decode())
    print("bias1", first.bias.detach().numpy().tolist())
    print("bias2", second.bias.detach().numpy().tolist())


if __name__ == "__main__":
    train()
