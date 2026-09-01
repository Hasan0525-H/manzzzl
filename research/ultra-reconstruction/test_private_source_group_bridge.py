from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import build_real_teacher_consensus as real_consensus  # noqa: E402


class PrivateSourceGroupBridgeTest(unittest.TestCase):
    def test_schema_two_local_only_manifest_is_accepted(self) -> None:
        with tempfile.TemporaryDirectory() as raw_tmp:
            root = pathlib.Path(raw_tmp)
            manifest = root / "source-groups.local.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "privacy": {
                            "localOnly": True,
                            "safeToCommit": False,
                            "containsRelativePaths": True,
                            "rawFamilyLabelsStored": False,
                        },
                        "groups": {
                            "scan/a.npz": "private:" + "a" * 32,
                            "cad/a.npz": "private:" + "a" * 32,
                        },
                    }
                ),
                encoding="utf-8",
            )
            samples = [pathlib.Path("cad/a.npz"), pathlib.Path("scan/a.npz")]
            groups = real_consensus.load_source_groups(manifest, samples)
            self.assertEqual(groups[pathlib.Path("scan/a.npz")], "private:" + "a" * 32)
            self.assertEqual(groups[pathlib.Path("cad/a.npz")], "private:" + "a" * 32)

    def test_schema_two_without_local_only_contract_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw_tmp:
            root = pathlib.Path(raw_tmp)
            manifest = root / "bad.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "privacy": {
                            "localOnly": False,
                            "safeToCommit": True,
                            "rawFamilyLabelsStored": False,
                        },
                        "groups": {"a.npz": "private:" + "a" * 32},
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "localOnly=true"):
                real_consensus.load_source_groups(manifest, [pathlib.Path("a.npz")])

    def test_schema_two_that_stores_raw_family_labels_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as raw_tmp:
            root = pathlib.Path(raw_tmp)
            manifest = root / "bad.json"
            manifest.write_text(
                json.dumps(
                    {
                        "schema": 2,
                        "privacy": {
                            "localOnly": True,
                            "safeToCommit": False,
                            "rawFamilyLabelsStored": True,
                        },
                        "groups": {"a.npz": "private:" + "a" * 32},
                    }
                ),
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ValueError, "raw family labels"):
                real_consensus.load_source_groups(manifest, [pathlib.Path("a.npz")])


if __name__ == "__main__":
    unittest.main()
