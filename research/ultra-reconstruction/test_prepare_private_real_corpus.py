from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

import cv2
import numpy as np

MODULE_PATH = pathlib.Path(__file__).with_name("prepare_private_real_corpus.py")
SPEC = importlib.util.spec_from_file_location("prepare_private_real_corpus", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class PreparePrivateRealCorpusTest(unittest.TestCase):
    def write_image(self, root: pathlib.Path, relative: str, value: int) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        image = np.full((24, 32, 3), value, dtype=np.uint8)
        cv2.rectangle(image, (3, 3), (28, 20), (0, 0, 0), 2)
        self.assertTrue(cv2.imwrite(str(path), image))

    def plan_image(self) -> np.ndarray:
        image = np.full((240, 320, 3), 255, dtype=np.uint8)
        cv2.rectangle(image, (32, 25), (286, 211), (0, 0, 0), 5)
        cv2.line(image, (150, 25), (150, 130), (0, 0, 0), 4)
        cv2.line(image, (150, 155), (150, 211), (0, 0, 0), 4)
        cv2.line(image, (32, 120), (108, 120), (0, 0, 0), 4)
        cv2.line(image, (132, 120), (286, 120), (0, 0, 0), 4)
        cv2.rectangle(image, (220, 145), (265, 190), (0, 0, 0), 3)
        cv2.line(image, (75, 120), (75, 80), (0, 0, 0), 3)
        cv2.circle(image, (150, 142), 13, (0, 0, 0), 2)
        return image

    def write_plan(self, root: pathlib.Path, relative: str, image: np.ndarray | None = None) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        self.assertTrue(cv2.imwrite(str(path), self.plan_image() if image is None else image))

    def write_families(self, path: pathlib.Path, payload: dict[str, str]) -> None:
        path.write_text(json.dumps(payload), encoding="utf-8")

    def test_raw_family_labels_never_leave_private_mapping(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            self.write_image(root, "a.png", 240)
            self.write_image(root, "b.png", 220)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(families, {"a.png": "secret-villa-name", "b.png": "secret-villa-name"})

            source_groups, manifest = module.build_manifests(root, families, "local-salt", 0.15, 0.15)
            serialized = json.dumps({"groups": source_groups, "manifest": manifest})
            self.assertNotIn("secret-villa-name", serialized)
            groups = set(source_groups["groups"].values())
            self.assertEqual(len(groups), 1)
            self.assertTrue(next(iter(groups)).startswith("private:"))
            self.assertFalse(manifest["privacy"]["sourceImagesCopied"])
            self.assertFalse(manifest["privacy"]["rawFamilyLabelsStored"])

    def test_safe_manifest_contains_no_paths_or_raw_raster_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            self.write_image(root, "riyadh/private-villa-name.png", 240)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(families, {"riyadh/private-villa-name.png": "family-secret"})

            source_groups, manifest = module.build_manifests(root, families, "stable-salt", 0.0, 0.0)
            safe_text = json.dumps(manifest)
            local_text = json.dumps(source_groups)
            self.assertNotIn("riyadh", safe_text)
            self.assertNotIn("private-villa-name", safe_text)
            self.assertNotIn("family-secret", safe_text)
            self.assertTrue(manifest["privacy"]["safeToCommit"])
            self.assertFalse(manifest["privacy"]["sourcePathsStored"])
            self.assertFalse(manifest["privacy"]["rawRasterHashesStored"])
            self.assertTrue(manifest["integrityPolicy"]["crossFamilyNearDuplicatesRejected"])
            self.assertFalse(manifest["integrityPolicy"]["nearDuplicateFingerprintsStored"])
            self.assertIn("riyadh/private-villa-name.npz", local_text)
            self.assertFalse(source_groups["privacy"]["safeToCommit"])
            self.assertTrue(source_groups["privacy"]["localOnly"])

    def test_all_variants_of_one_family_stay_in_one_split(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            self.write_image(root, "scan.png", 240)
            self.write_image(root, "phone.png", 230)
            self.write_image(root, "cad.png", 220)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(
                families,
                {"scan.png": "house-1", "phone.png": "house-1", "cad.png": "house-1"},
            )

            _, manifest = module.build_manifests(root, families, "stable-salt", 0.2, 0.2)
            splits = {record["split"] for record in manifest["records"]}
            groups = {record["sourceGroup"] for record in manifest["records"]}
            self.assertEqual(len(splits), 1)
            self.assertEqual(len(groups), 1)

    def test_duplicate_canonical_raster_is_rejected_even_when_renamed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            self.write_image(root, "original.png", 240)
            (root / "copy.png").write_bytes((root / "original.png").read_bytes())
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(families, {"original.png": "house-a", "copy.png": "house-b"})

            with self.assertRaisesRegex(RuntimeError, "duplicate canonical real-plan raster"):
                module.build_manifests(root, families, "stable-salt", 0.15, 0.15)

    def test_resized_recompressed_same_plan_cannot_be_split_as_different_families(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            base = self.plan_image()
            self.write_plan(root, "original.png", base)
            # Change resolution, page margin and lossy codec without changing the underlying plan.
            padded = cv2.copyMakeBorder(base, 18, 27, 22, 35, cv2.BORDER_CONSTANT, value=(255, 255, 255))
            variant = cv2.resize(padded, (430, 330), interpolation=cv2.INTER_AREA)
            self.write_plan(root, "phone-export.jpg", variant)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(
                families,
                {"original.png": "house-a", "phone-export.jpg": "house-b"},
            )

            with self.assertRaisesRegex(RuntimeError, "near-duplicate real-plan rasters"):
                module.build_manifests(root, families, "stable-salt", 0.15, 0.15)

    def test_resized_recompressed_variants_are_allowed_when_family_is_shared(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            base = self.plan_image()
            self.write_plan(root, "scan.png", base)
            variant = cv2.resize(
                cv2.copyMakeBorder(base, 12, 20, 15, 28, cv2.BORDER_CONSTANT, value=(255, 255, 255)),
                (390, 300),
                interpolation=cv2.INTER_AREA,
            )
            self.write_plan(root, "scan-compressed.jpg", variant)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(
                families,
                {"scan.png": "same-house", "scan-compressed.jpg": "same-house"},
            )

            _, manifest = module.build_manifests(root, families, "stable-salt", 0.15, 0.15)
            self.assertEqual(manifest["uniqueSourceGroups"], 1)
            self.assertEqual(len({record["split"] for record in manifest["records"]}), 1)

    def test_family_map_must_cover_exact_image_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            self.write_image(root, "a.png", 240)
            self.write_image(root, "b.png", 220)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(families, {"a.png": "house-a"})

            with self.assertRaisesRegex(RuntimeError, "exact real-plan image set"):
                module.build_manifests(root, families, "stable-salt", 0.15, 0.15)

    def test_same_inputs_are_deterministic(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            self.write_image(root, "a.png", 240)
            self.write_image(root, "b.png", 220)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(families, {"a.png": "house-a", "b.png": "house-b"})

            first = module.build_manifests(root, families, "stable-salt", 0.15, 0.15)
            second = module.build_manifests(root, families, "stable-salt", 0.15, 0.15)
            self.assertEqual(first, second)

    def test_source_group_manifest_uses_npz_paths_for_consensus(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            self.write_image(root, "riyadh/villa.png", 240)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(families, {"riyadh/villa.png": "house-a"})

            source_groups, _ = module.build_manifests(root, families, "stable-salt", 0.0, 0.0)
            self.assertEqual(set(source_groups["groups"]), {"riyadh/villa.npz"})

    def test_holdout_coverage_fails_closed_when_requested_partition_is_empty(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp) / "images"
            root.mkdir()
            self.write_image(root, "only.png", 240)
            families = pathlib.Path(tmp) / "families.json"
            self.write_families(families, {"only.png": "only-family"})

            _, manifest = module.build_manifests(root, families, "stable-salt", 0.15, 0.15)
            with self.assertRaisesRegex(RuntimeError, "too small/unbalanced"):
                module.validate_holdout_coverage(manifest)


if __name__ == "__main__":
    unittest.main()
