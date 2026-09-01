from __future__ import annotations

import importlib.util
import json
import pathlib
import sys
import tempfile
import unittest

import numpy as np

ROOT = pathlib.Path(__file__).parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
MODULE_PATH = ROOT / "partition_private_real_consensus.py"
SPEC = importlib.util.spec_from_file_location("partition_private_real_consensus", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
module = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class PartitionPrivateRealConsensusTest(unittest.TestCase):
    def write_npz(self, root: pathlib.Path, relative: str, group: str, value: int) -> None:
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        image = np.full((8, 8, 3), value, dtype=np.uint8)
        np.savez_compressed(path, image=image, source_group=np.asarray(group))

    def write_manifests(self, root: pathlib.Path, groups: dict[str, str], group_splits: dict[str, str]):
        local = root / "source-groups.local.json"
        safe = root / "split-manifest.json"
        local.write_text(json.dumps({
            "schema": 2,
            "privacy": {"localOnly": True, "safeToCommit": False},
            "groups": groups,
        }), encoding="utf-8")
        records = [
            {"sampleId": f"sample:{index:032x}", "sourceGroup": group, "split": split, "width": 8, "height": 8}
            for index, (group, split) in enumerate(group_splits.items(), start=1)
        ]
        safe.write_text(json.dumps({
            "schema": 2,
            "privacy": {"safeToCommit": True},
            "records": records,
        }), encoding="utf-8")
        return local, safe

    def test_family_assignment_partitions_without_leakage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            consensus = root / "consensus"
            train_group = "private:" + "a" * 32
            val_group = "private:" + "b" * 32
            test_group = "private:" + "c" * 32
            self.write_npz(consensus, "train/a.npz", train_group, 10)
            self.write_npz(consensus, "train/b.npz", train_group, 20)
            self.write_npz(consensus, "val/c.npz", val_group, 30)
            self.write_npz(consensus, "test/d.npz", test_group, 40)
            groups = {
                "train/a.npz": train_group,
                "train/b.npz": train_group,
                "val/c.npz": val_group,
                "test/d.npz": test_group,
            }
            local, safe = self.write_manifests(root, groups, {
                train_group: "train", val_group: "validation", test_group: "test"
            })
            report = module.partition(consensus, local, safe, root / "partitioned")
            self.assertTrue(report["passed"])
            self.assertEqual(report["samplesBySplit"], {"train": 2, "validation": 1, "test": 1})
            self.assertEqual(report["sourceGroupsBySplit"], {"train": 1, "validation": 1, "test": 1})

    def test_embedded_group_mismatch_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            consensus = root / "consensus"
            expected = "private:" + "a" * 32
            actual = "private:" + "b" * 32
            self.write_npz(consensus, "a.npz", actual, 10)
            local, safe = self.write_manifests(root, {"a.npz": expected}, {expected: "train"})
            with self.assertRaisesRegex(RuntimeError, "embedded source_group"):
                module.partition(consensus, local, safe, root / "partitioned")

    def test_exact_consensus_set_is_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            consensus = root / "consensus"
            group = "private:" + "a" * 32
            self.write_npz(consensus, "a.npz", group, 10)
            local, safe = self.write_manifests(root, {"a.npz": group, "missing.npz": group}, {group: "train"})
            with self.assertRaisesRegex(RuntimeError, "exact same NPZ set"):
                module.partition(consensus, local, safe, root / "partitioned")

    def test_one_family_cannot_have_multiple_splits(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            group = "private:" + "a" * 32
            safe = root / "split-manifest.json"
            safe.write_text(json.dumps({
                "privacy": {"safeToCommit": True},
                "records": [
                    {"sourceGroup": group, "split": "train"},
                    {"sourceGroup": group, "split": "validation"},
                ],
            }), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "multiple splits"):
                module.load_group_splits(safe)


if __name__ == "__main__":
    unittest.main()
