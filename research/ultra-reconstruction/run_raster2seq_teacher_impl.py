#!/usr/bin/env python3
"""Run the pinned Raster2Seq Raster2Graph-512 teacher without weakening Manzl's evidence contract.

This adapter intentionally separates three immutable inputs:

* Raster2Seq source revision: ``a6c4e27a...``
* Hugging Face model revision: ``6be66814...``
* Raster2Graph-512 checkpoint SHA-256/size from ``fetch_raster2seq_teacher.py``

The upstream prediction CLI unconditionally creates CUDA timing events even when ``--device cpu``.
For reproducible CPU smoke tests this runner therefore calls the upstream model directly. It still uses
the pinned upstream parser defaults, tokenizer, ResizeAndPad preprocessing, model builder and sequence
semantics. The only CPU-specific substitution is Multi-Scale Deformable Attention: when
``--cpu-reference-attention`` is requested, Manzl replaces the compiled CUDA extension with the pure
PyTorch reference implementation shipped in the same upstream source tree. This is a development-time
compatibility path, not an Android runtime component.

The selected Raster2Graph-512 checkpoint has no door/window semantic classes. Output JSON contains only
R2G room/space polygons; ``adapt_raster2seq_predictions.py`` converts those to ``room_boundary``
evidence and abstains from openings.
"""

from __future__ import annotations

import argparse
import ast
import copy
import hashlib
import json
import pathlib
import subprocess
import sys
import types
from typing import Any, Iterable

import cv2
import numpy as np

import fetch_raster2seq_teacher as pinned

SOURCE_REVISION = "a6c4e27a68d11d7a459f6e4a2601fd887227dd1a"
EXPECTED_CONFIG_ARGS = {
    "dataset_name": "r2g",
    "semantic_classes": 13,
    "input_channels": 3,
    "poly2seq": True,
    "image_size": 512,
    "seq_len": 512,
    "num_bins": 32,
    "disable_poly_refine": True,
    "dec_attn_concat_src": True,
    "ema4eval": True,
    "use_anchor": True,
    "per_token_sem_loss": True,
    "save_pred": True,
}
R2G_ROOM_CATEGORY_IDS = frozenset(range(12))
IMAGE_EXTENSIONS = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp"}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(4 * 1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def verify_source_revision(repository: pathlib.Path) -> None:
    if not repository.is_dir() or not (repository / ".git").exists():
        raise FileNotFoundError(f"Pinned Raster2Seq git clone is missing: {repository}")
    completed = subprocess.run(
        ["git", "-C", str(repository), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    actual = completed.stdout.strip()
    if actual != SOURCE_REVISION:
        raise RuntimeError(f"Raster2Seq source revision mismatch: {actual} != {SOURCE_REVISION}")


def verify_checkpoint(checkpoint: pathlib.Path) -> None:
    if not checkpoint.is_file():
        raise FileNotFoundError(f"Raster2Seq checkpoint is missing: {checkpoint}")
    if checkpoint.stat().st_size != pinned.CHECKPOINT_SIZE:
        raise RuntimeError(
            f"Raster2Seq checkpoint size mismatch: {checkpoint.stat().st_size} != {pinned.CHECKPOINT_SIZE}"
        )
    digest = sha256(checkpoint)
    if digest != pinned.CHECKPOINT_SHA256:
        raise RuntimeError(f"Raster2Seq checkpoint SHA-256 mismatch: {digest}")


def load_and_validate_config(config_path: pathlib.Path) -> dict[str, Any]:
    if not config_path.is_file():
        raise FileNotFoundError(f"Raster2Seq config is missing: {config_path}")
    config = pinned.verify_config(config_path)
    inference = config.get("inference_args")
    if not isinstance(inference, dict):
        raise ValueError("Raster2Seq config is missing inference_args")
    for key, expected in EXPECTED_CONFIG_ARGS.items():
        actual = inference.get(key)
        if actual != expected:
            raise RuntimeError(f"Unexpected Raster2Seq inference arg {key}: {actual!r} != {expected!r}")
    return config


def extract_upstream_predict_defaults(repository: pathlib.Path) -> argparse.Namespace:
    """Execute only upstream get_args_parser() without importing its CUDA-timing prediction module."""
    path = repository / "predict.py"
    source = path.read_text(encoding="utf-8")
    tree = ast.parse(source, filename=str(path))
    function = next(
        (node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == "get_args_parser"),
        None,
    )
    if function is None:
        raise RuntimeError("Pinned Raster2Seq predict.py no longer defines get_args_parser")
    module = ast.Module(body=[function], type_ignores=[])
    ast.fix_missing_locations(module)
    namespace: dict[str, Any] = {"argparse": argparse, "np": np}
    exec(compile(module, str(path), "exec"), namespace)
    parser = namespace["get_args_parser"]()
    return parser.parse_args([])


def build_upstream_args(repository: pathlib.Path, config: dict[str, Any], device: str) -> argparse.Namespace:
    args = extract_upstream_predict_defaults(repository)
    inference = config["inference_args"]
    for key, value in inference.items():
        setattr(args, key, value)
    args.device = device
    args.batch_size = 1
    args.num_workers = 0
    if args.disable_poly_refine:
        args.with_poly_refine = False
    return args


def install_extension_import_stub() -> None:
    """Allow importing upstream CPU reference code without loading its compiled CUDA extension."""
    if "MultiScaleDeformableAttention" in sys.modules:
        return
    stub = types.ModuleType("MultiScaleDeformableAttention")

    def unavailable(*_args, **_kwargs):
        raise RuntimeError(
            "Compiled MultiScaleDeformableAttention was called on the CPU-reference path; "
            "the Manzl reference-forward patch was not installed"
        )

    stub.ms_deform_attn_forward = unavailable  # type: ignore[attr-defined]
    stub.ms_deform_attn_backward = unavailable  # type: ignore[attr-defined]
    sys.modules["MultiScaleDeformableAttention"] = stub


def patch_cpu_reference_attention() -> None:
    """Use upstream's own pure-PyTorch deformable-attention core for CPU smoke inference."""
    import torch
    import torch.nn.functional as F
    from models.ops.functions.ms_deform_attn_func import ms_deform_attn_core_pytorch
    from models.ops.modules.ms_deform_attn import MSDeformAttn

    def reference_forward(
        self,
        query,
        reference_points,
        input_flatten,
        input_spatial_shapes,
        input_level_start_index,
        input_padding_mask=None,
        use_cache=False,
    ):
        del input_level_start_index  # The upstream PyTorch reference core derives splits from shapes.
        batch, query_length, _ = query.shape
        batch_in, input_length, _ = input_flatten.shape
        if batch != batch_in:
            raise ValueError("Raster2Seq deformable-attention batch mismatch")
        if int((input_spatial_shapes[:, 0] * input_spatial_shapes[:, 1]).sum().item()) != input_length:
            raise ValueError("Raster2Seq deformable-attention spatial-shape mismatch")

        if use_cache and self.cache is not None:
            value = self.cache.get()
        else:
            value = self.value_proj(input_flatten)
            if input_padding_mask is not None:
                value = value.masked_fill(input_padding_mask[..., None], float(0))
            value = value.view(batch, input_length, self.n_heads, self.d_model // self.n_heads)
            if self.cache is not None:
                self.cache.update(value)

        sampling_offsets = self.sampling_offsets(query).view(
            batch, query_length, self.n_heads, self.n_levels, self.n_points, 2
        )
        attention_weights = self.attention_weights(query).view(
            batch, query_length, self.n_heads, self.n_levels * self.n_points
        )
        attention_weights = F.softmax(attention_weights, -1).view(
            batch, query_length, self.n_heads, self.n_levels, self.n_points
        )

        if reference_points is not None:
            if reference_points.shape[-1] == 2:
                offset_normalizer = torch.stack(
                    [input_spatial_shapes[..., 1], input_spatial_shapes[..., 0]], -1
                )
                sampling_locations = (
                    reference_points[:, :, None, :, None, :]
                    + sampling_offsets / offset_normalizer[None, None, None, :, None, :]
                )
            elif reference_points.shape[-1] == 4:
                sampling_locations = (
                    reference_points[:, :, None, :, None, :2]
                    + sampling_offsets
                    / self.n_points
                    * reference_points[:, :, None, :, None, 2:]
                    * 0.5
                )
            else:
                raise ValueError("Raster2Seq reference_points last dimension must be 2 or 4")
        else:
            offset_normalizer = torch.stack(
                [input_spatial_shapes[..., 1], input_spatial_shapes[..., 0]], -1
            )
            sampling_locations = sampling_offsets / offset_normalizer[None, None, None, :, None, :]

        output = ms_deform_attn_core_pytorch(
            value,
            input_spatial_shapes,
            sampling_locations,
            attention_weights,
        )
        return self.output_proj(output)

    MSDeformAttn.forward = reference_forward


def patch_backbone_initialization_no_download() -> None:
    """Checkpoint supplies the real backbone weights; avoid a redundant ImageNet network download."""
    import torchvision

    original = torchvision.models.resnet50

    def resnet50_no_download(*args, **kwargs):
        kwargs.pop("pretrained", None)
        kwargs["weights"] = None
        return original(*args, **kwargs)

    torchvision.models.resnet50 = resnet50_no_download


def import_upstream_runtime(repository: pathlib.Path, cpu_reference_attention: bool):
    repository_text = str(repository.resolve())
    if repository_text not in sys.path:
        sys.path.insert(0, repository_text)
    install_extension_import_stub()

    import torch
    from datasets.discrete_tokenizer import DiscreteTokenizer
    from datasets.poly_data import TokenType
    from datasets.transforms import ResizeAndPad
    from detectron2.data import transforms as T
    from models import build_model

    if cpu_reference_attention:
        patch_cpu_reference_attention()
    patch_backbone_initialization_no_download()
    return torch, DiscreteTokenizer, TokenType, ResizeAndPad, T, build_model


def normalize_state_dict(state: Any) -> dict[str, Any]:
    if not isinstance(state, dict):
        raise ValueError("Raster2Seq checkpoint model state is not a mapping")
    normalized: dict[str, Any] = {}
    for key, value in state.items():
        name = str(key)
        if name.startswith("module."):
            name = name[7:]
        normalized[name] = value
    return normalized


def is_runtime_cache_state_key(key: str) -> bool:
    """Accept only upstream inference caches that are regenerated from the current sample.

    Raster2Seq checkpoints can persist autoregressive KV/V cache buffers. They are not learned model
    parameters: the pinned upstream KVCache/VCache implementation initializes these buffers to zeros and
    updates them from the current inference sequence. No other unexpected checkpoint key is allowed.
    """
    parts = key.split(".")
    if len(parts) >= 2 and parts[-2] == "kv_cache" and parts[-1] in {"k_cache", "v_cache"}:
        return True
    if len(parts) >= 3 and parts[-3:] == ["cross_attn", "cache", "v_cache"]:
        return True
    return False


def load_model(
    repository: pathlib.Path,
    checkpoint: pathlib.Path,
    config: dict[str, Any],
    device_name: str,
    cpu_reference_attention: bool,
):
    torch, DiscreteTokenizer, TokenType, ResizeAndPad, T, build_model = import_upstream_runtime(
        repository,
        cpu_reference_attention=cpu_reference_attention,
    )
    if device_name == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("--device cuda requested but CUDA is unavailable")
    if device_name == "cpu" and not cpu_reference_attention:
        raise RuntimeError("CPU Raster2Seq inference requires --cpu-reference-attention")

    args = build_upstream_args(repository, config, device_name)
    tokenizer = DiscreteTokenizer(args.num_bins, args.seq_len, add_cls=args.add_cls_token)
    args.vocab_size = len(tokenizer)
    model = build_model(args, train=False, tokenizer=tokenizer)

    # Full pickle is necessary because the trusted official checkpoint contains argparse.Namespace.
    # This occurs only after exact SHA-256/size verification above.
    payload = torch.load(checkpoint, map_location="cpu", weights_only=False)
    if not isinstance(payload, dict):
        raise ValueError("Unexpected Raster2Seq checkpoint payload")
    state_key = "ema" if args.ema4eval else "model"
    if state_key not in payload:
        raise ValueError(f"Raster2Seq checkpoint is missing {state_key!r} state")
    state = normalize_state_dict(copy.deepcopy(payload[state_key]))
    missing, unexpected = model.load_state_dict(state, strict=False)
    meaningful_unexpected = [
        key
        for key in unexpected
        if not (
            key.endswith("total_params")
            or key.endswith("total_ops")
            or is_runtime_cache_state_key(key)
        )
    ]
    if missing or meaningful_unexpected:
        raise RuntimeError(
            f"Raster2Seq checkpoint/model mismatch: missing={missing[:20]} "
            f"unexpected={meaningful_unexpected[:20]}"
        )

    device = torch.device(device_name)
    model.to(device)
    model.eval()
    for parameter in model.parameters():
        parameter.requires_grad_(False)
    return torch, model, device, args, TokenType, ResizeAndPad, T


def discover_images(root: pathlib.Path, recursive: bool) -> list[pathlib.Path]:
    iterator: Iterable[pathlib.Path] = root.rglob("*") if recursive else root.glob("*")
    images = sorted(path for path in iterator if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS)
    if not images:
        raise RuntimeError(f"No source images found under {root}")
    stems = [path.stem for path in images]
    if len(stems) != len(set(stems)):
        raise RuntimeError("Raster2Seq teacher requires unique image stems to preserve exact JSON/image pairing")
    return images


def preprocess_image(path: pathlib.Path, image_size: int, ResizeAndPad, T, torch_module):
    from PIL import Image

    image = np.asarray(Image.open(path).convert("RGB"))
    transform = T.AugmentationList([ResizeAndPad((image_size, image_size), pad_value=255)])
    augmented = T.AugInput(image)
    transform(augmented)
    transformed = np.asarray(augmented.image, dtype=np.uint8)
    if transformed.shape != (image_size, image_size, 3):
        raise RuntimeError(f"Unexpected Raster2Seq transformed image shape: {transformed.shape}")
    tensor = torch_module.as_tensor(transformed.transpose(2, 0, 1)).float() / 255.0
    return transformed, tensor


def polygon_area(points: np.ndarray) -> float:
    if len(points) < 3:
        return 0.0
    x = points[:, 0].astype(np.float64)
    y = points[:, 1].astype(np.float64)
    return abs(float(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1)))) * 0.5


def decode_r2g_polygons(outputs: dict[str, Any], image_size: int, token_type, per_token_sem_loss: bool) -> list[dict[str, Any]]:
    if "gen_out" not in outputs or "pred_room_logits" not in outputs:
        raise ValueError("Raster2Seq semantic inference output is missing gen_out/pred_room_logits")
    generated = outputs["gen_out"]
    if not isinstance(generated, (list, tuple)) or len(generated) != 1:
        raise ValueError("Raster2Seq runner expects a one-image inference batch")
    tokens = generated[0]

    logits = outputs["pred_room_logits"]
    probabilities = logits.softmax(-1)
    labels = probabilities[..., :-1].argmax(-1)[0].detach().cpu().numpy()

    polygons: list[list[Any]] = []
    current: list[Any] = []
    lengths = [0]
    for token in tokens:
        if isinstance(token, (int, np.integer)):
            value = int(token)
            if value in {token_type.sep.value, token_type.eos.value, token_type.cls.value, -1}:
                if current:
                    polygons.append(current)
                    lengths.append(len(current) + 1)
                    current = []
                if value in {-1, token_type.eos.value}:
                    break
                continue
        else:
            current.append(token)
    if current:
        polygons.append(current)
        lengths.append(len(current) + 1)

    starts = np.cumsum(lengths)
    records: list[dict[str, Any]] = []
    for index, polygon in enumerate(polygons):
        if len(polygon) < 3:
            continue
        coords = []
        for point in polygon:
            if hasattr(point, "detach"):
                point = point.detach().cpu().numpy()
            array = np.asarray(point, dtype=np.float32).reshape(-1)
            if array.size != 2 or not np.isfinite(array).all():
                coords = []
                break
            coords.append(array)
        if len(coords) < 3:
            continue
        corners = np.asarray(coords, dtype=np.float32) * float(image_size - 1)
        corners = np.rint(corners).astype(np.int32)
        if polygon_area(corners) < 100.0:
            continue

        start = int(starts[index])
        end = int(starts[index + 1])
        if per_token_sem_loss:
            votes = labels[start:end][:-1]
            if votes.size == 0:
                continue
            classes, counts = np.unique(votes, return_counts=True)
            category = int(classes[np.argmax(counts)])
        else:
            category = int(labels[end - 1])
        if category not in R2G_ROOM_CATEGORY_IDS:
            continue

        records.append(
            {
                "image_id": "",
                "segmentation": corners.astype(float).tolist(),
                "category_id": category,
                "id": len(records),
            }
        )
    return records


def write_prediction(output_root: pathlib.Path, stem: str, transformed_rgb: np.ndarray, records: list[dict[str, Any]]) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    json_root = output_root / "jsons"
    json_root.mkdir(parents=True, exist_ok=True)
    for record in records:
        record["image_id"] = stem
    (json_root / f"{stem}.json").write_text(json.dumps(records), encoding="utf-8")
    bgr = cv2.cvtColor(transformed_rgb, cv2.COLOR_RGB2BGR)
    if not cv2.imwrite(str(output_root / f"{stem}.png"), bgr):
        raise RuntimeError(f"Failed to save transformed Raster2Seq source image for {stem}")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=pathlib.Path, required=True)
    parser.add_argument("--repository", type=pathlib.Path, required=True)
    parser.add_argument("--checkpoint-dir", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--device", choices=("cpu", "cuda"), default="cpu")
    parser.add_argument("--cpu-reference-attention", action="store_true")
    parser.add_argument("--recursive", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.input.is_dir():
        raise FileNotFoundError(f"Input directory does not exist: {args.input}")
    verify_source_revision(args.repository)
    checkpoint = args.checkpoint_dir / "checkpoint.pth"
    config_path = args.checkpoint_dir / "config.json"
    verify_checkpoint(checkpoint)
    config = load_and_validate_config(config_path)

    torch_module, model, device, upstream_args, token_type, ResizeAndPad, T = load_model(
        repository=args.repository,
        checkpoint=checkpoint,
        config=config,
        device_name=args.device,
        cpu_reference_attention=args.cpu_reference_attention,
    )

    images = discover_images(args.input, recursive=args.recursive)
    total_polygons = 0
    for source in images:
        transformed, tensor = preprocess_image(
            source,
            upstream_args.image_size,
            ResizeAndPad,
            T,
            torch_module,
        )
        with torch_module.inference_mode():
            outputs = model.forward_inference([tensor.to(device)], use_cache=True)
        records = decode_r2g_polygons(
            outputs,
            image_size=upstream_args.image_size,
            token_type=token_type,
            per_token_sem_loss=upstream_args.per_token_sem_loss,
        )
        write_prediction(args.output, source.stem, transformed, records)
        total_polygons += len(records)

    print(
        json.dumps(
            {
                "teacher": "Raster2Seq/Raster2Graph-512",
                "sourceRevision": SOURCE_REVISION,
                "modelRevision": pinned.REVISION,
                "checkpointSha256": pinned.CHECKPOINT_SHA256,
                "checkpointBytes": pinned.CHECKPOINT_SIZE,
                "device": str(device),
                "cpuReferenceAttention": bool(args.cpu_reference_attention),
                "samples": len(images),
                "roomPolygons": total_polygons,
                "semanticContract": "room_boundary-only-after-adaptation",
                "passed": True,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
