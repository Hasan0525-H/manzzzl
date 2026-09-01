from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest

import cv2
import numpy as np

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import prepare_private_real_corpus as private_corpus  # noqa: E402
import rekey_private_manifest_to_teacher_raster as rekeyer  # noqa: E402


class RekeyPrivateManifestTest(unittest.TestCase):
    SALT = "stable-private-salt"
    VALIDATION_FRACTION = 0.20
    TEST_FRACTION = 0.20

    def find_groups(self) -> dict[str, str]:
        found: dict[str, str] = {}
        for index in range(1, 10000):
            group = "private:" + f"{index:032x}"
            split = private_corpus.group_split(
                group,
                self.SALT,
                validation_fraction=self.VALIDATION_FRACTION,
                test_fraction=self.TEST_FRACTION,
            )
            found.setdefault(split, group)
            if set(found) == {"train", "validation", "test"}:
                return found
        self.fail("could not deterministically find source groups for all splits")

    def write_fixture(self, root: pathlib.Path, included_splits=("train", "validation", "test")):
        groups_by_split = self.find_groups()
        teacher_images = root / "teacher-images"
        teacher_images.mkdir()
        groups: dict[str, str] = {}
        records = []
        for ordinal, split in enumerate(included_splits, start=1):
            group = groups_by_split[split]
            relative_png = pathlib.Path(split) / f"plan-{ordinal}.png"
            path = teacher_images / relative_png
            path.parent.mkdir(parents=True, exist_ok=True)
            image = np.full((32, 48, 3), 220 + ordinal, dtype=np.uint8)
            cv2.rectangle(image, (4, 4), (43, 27), (0, 0, 0), 2)
            self.assertTrue(cv2.imwrite(str(path), image))
            groups[relative_png.with_suffix(".npz").as_posix()] = group
            records.append({
                "sampleId": "sample:" + f"{ordinal:032x}",
                "width": 48,
                "height": 32,
                "sourceGroup": group,
                "split": split,
            })

        local_groups = root / "source-groups.local.json"
        local_groups.write_text(json.dumps({
            "schema": 2,
            "privacy": {
                "localOnly": True,
                "safeToCommit": False,
                "containsRelativePaths": True,
                "rawFamilyLabelsStored": False,
            },
            "groups": groups,
        }), encoding="utf-8")

        split_policy = {
            "unit": "source_group",
            "deterministic": True,
            "validationFraction": self.VALIDATION_FRACTION,
            "testFraction": self.TEST_FRACTION,
            "trainFraction": 1.0 - self.VALIDATION_FRACTION - self.TEST_FRACTION,
            "sameFamilyCrossSplitAllowed": False,
        }
        original_manifest = root / "split-manifest.json"
        original_manifest.write_text(json.dumps({
            "schema": 2,
            "privacy": {
                "sourceImagesCopied": False,
                "rawFamilyLabelsStored": False,
                "sourcePathsStored": False,
                "rawRasterHashesStored": False,
                "safeToCommit": True,
            },
            "splitPolicy": split_policy,
            "records": records,
        }), encoding="utf-8")
        return teacher_images, local_groups, original_manifest, split_policy

    def test_rekey_preserves_policy_and_reverifies_split_assignments(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            teacher_images, local_groups, original_manifest, split_policy = self.write_fixture(root)
            manifest = rekeyer.rekey(teacher_images, local_groups, original_manifest, self.SALT)
            self.assertEqual(manifest["splitPolicy"], split_policy)
            self.assertEqual(manifest["samplesBySplit"], {"train": 1, "validation": 1, "test": 1})
            self.assertTrue(manifest["alignment"]["splitAssignmentsReverifiedFromSaltAndPolicy"])

    def test_tampered_group_assignment_is_rejected_even_when_family_does_not_cross_splits(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            teacher_images, local_groups, original_manifest, _ = self.write_fixture(root)
            payload = json.loads(original_manifest.read_text(encoding="utf-8"))
            payload["records"][0]["split"] = "validation" if payload["records"][0]["split"] != "validation" else "train"
            original_manifest.write_text(json.dumps(payload), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "does not match the private corpus salt/policy"):
                rekeyer.rekey(teacher_images, local_groups, original_manifest, self.SALT)

    def test_teacher_alignment_cannot_silently_drop_requested_holdout_partition(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            teacher_images, local_groups, original_manifest, _ = self.write_fixture(root, included_splits=("train",))
            with self.assertRaisesRegex(RuntimeError, "too small/unbalanced"):
                rekeyer.rekey(teacher_images, local_groups, original_manifest, self.SALT)


if __name__ == "__main__":
    unittest.main()
