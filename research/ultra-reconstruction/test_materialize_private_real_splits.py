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

import materialize_private_real_splits as materializer  # noqa: E402
import prepare_private_real_corpus as private_corpus  # noqa: E402


class MaterializePrivateRealSplitsTest(unittest.TestCase):
    def make_fixture(self, tmp: str):
        base = pathlib.Path(tmp)
        images = base / "images"
        images.mkdir()
        private_relative = pathlib.Path("riyadh/client-secret/villa-alpha.png")
        source_path = images / private_relative
        source_path.parent.mkdir(parents=True)
        image = np.full((32, 40, 3), 245, dtype=np.uint8)
        cv2.rectangle(image, (4, 4), (35, 27), (0, 0, 0), 3)
        self.assertTrue(cv2.imwrite(str(source_path), image))

        families = base / "families.json"
        families.write_text(
            json.dumps({private_relative.as_posix(): "family-secret-name"}),
            encoding="utf-8",
        )
        source_groups, safe_manifest = private_corpus.build_manifests(
            images, families, "stable-private-salt", 0.0, 0.0
        )
        source_groups_path = base / "source-groups.local.json"
        safe_manifest_path = base / "split-manifest.json"
        source_groups_path.write_text(json.dumps(source_groups), encoding="utf-8")
        safe_manifest_path.write_text(json.dumps(safe_manifest), encoding="utf-8")

        consensus = base / "consensus"
        npz_relative = private_relative.with_suffix(".npz")
        consensus_path = consensus / npz_relative
        consensus_path.parent.mkdir(parents=True)
        source_group = source_groups["groups"][npz_relative.as_posix()]
        rgb = cv2.cvtColor(cv2.imread(str(source_path), cv2.IMREAD_COLOR), cv2.COLOR_BGR2RGB)
        np.savez_compressed(
            consensus_path,
            image=rgb,
            semantic=np.zeros(rgb.shape[:2], dtype=np.int64),
            supervision_mask=np.ones(rgb.shape[:2], dtype=np.float32),
            source_group=np.asarray(source_group),
        )
        return base, consensus, consensus_path, source_groups_path, safe_manifest_path, safe_manifest

    def test_materialization_replaces_private_paths_with_opaque_filename(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, consensus, _, source_groups, manifest, safe_manifest = self.make_fixture(tmp)
            output = base / "splits"
            report = materializer.materialize(
                consensus,
                source_groups,
                manifest,
                "stable-private-salt",
                output,
            )

            train_files = list((output / "train").glob("*.npz"))
            self.assertEqual(len(train_files), 1)
            self.assertTrue(train_files[0].name.startswith("sample-"))
            self.assertNotIn("riyadh", train_files[0].as_posix())
            self.assertNotIn("client-secret", train_files[0].as_posix())
            self.assertEqual(report["samples"], 1)
            self.assertEqual(report["samplesBySplit"], safe_manifest["samplesBySplit"])
            self.assertTrue(report["opaqueOutputFilenames"])
            self.assertTrue(report["transactionalMaterialization"])
            self.assertFalse(report["existingOutputOverwritten"])
            self.assertTrue(report["splitAssignmentsReverifiedFromSaltAndPolicy"])
            self.assertTrue(report["testReservedForFinalEvaluation"])
            report_text = (output / "materialization_report.json").read_text(encoding="utf-8")
            self.assertNotIn("riyadh", report_text)
            self.assertNotIn("client-secret", report_text)
            self.assertNotIn("family-secret-name", report_text)

    def test_embedded_source_group_mismatch_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, consensus, consensus_path, source_groups, manifest, _ = self.make_fixture(tmp)
            with np.load(consensus_path, allow_pickle=False) as existing:
                payload = {name: np.asarray(existing[name]) for name in existing.files}
            payload["source_group"] = np.asarray("private:wrong-family")
            np.savez_compressed(consensus_path, **payload)

            output = base / "splits"
            with self.assertRaisesRegex(RuntimeError, "disagrees with local provenance"):
                materializer.materialize(
                    consensus,
                    source_groups,
                    manifest,
                    "stable-private-salt",
                    output,
                )
            self.assertFalse(output.exists())

    def test_wrong_salt_cannot_silently_reassign_samples(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, consensus, _, source_groups, manifest, _ = self.make_fixture(tmp)
            with self.assertRaisesRegex(RuntimeError, "salt/policy"):
                materializer.materialize(
                    consensus,
                    source_groups,
                    manifest,
                    "different-private-salt",
                    base / "splits",
                )

    def test_existing_output_is_never_deleted_or_overwritten(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, consensus, _, source_groups, manifest, _ = self.make_fixture(tmp)
            output = base / "splits"
            output.mkdir()
            marker = output / "do-not-delete.txt"
            marker.write_text("existing private result", encoding="utf-8")

            with self.assertRaisesRegex(FileExistsError, "refusing to delete or overwrite"):
                materializer.materialize(
                    consensus,
                    source_groups,
                    manifest,
                    "stable-private-salt",
                    output,
                )
            self.assertEqual(marker.read_text(encoding="utf-8"), "existing private result")

    def test_manifest_split_cannot_disagree_with_salt_policy(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, consensus, _, source_groups, manifest_path, manifest = self.make_fixture(tmp)
            manifest["records"][0]["split"] = "validation"
            manifest["samplesBySplit"] = {"train": 0, "validation": 1, "test": 0}
            manifest["sourceGroupsBySplit"] = {"train": 0, "validation": 1, "test": 0}
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

            with self.assertRaisesRegex(RuntimeError, "salt/policy"):
                materializer.materialize(
                    consensus,
                    source_groups,
                    manifest_path,
                    "stable-private-salt",
                    base / "splits",
                )

    def test_manifest_marked_unsafe_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, consensus, _, source_groups, manifest_path, manifest = self.make_fixture(tmp)
            manifest["privacy"]["safeToCommit"] = False
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "privacy contract"):
                materializer.materialize(
                    consensus,
                    source_groups,
                    manifest_path,
                    "stable-private-salt",
                    base / "splits",
                )

    def test_manifest_record_cannot_smuggle_a_private_path_field(self):
        with tempfile.TemporaryDirectory() as tmp:
            base, consensus, _, source_groups, manifest_path, manifest = self.make_fixture(tmp)
            manifest["records"][0]["relativePath"] = "riyadh/client-secret/villa-alpha.png"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "unexpected fields"):
                materializer.materialize(
                    consensus,
                    source_groups,
                    manifest_path,
                    "stable-private-salt",
                    base / "splits",
                )


if __name__ == "__main__":
    unittest.main()
