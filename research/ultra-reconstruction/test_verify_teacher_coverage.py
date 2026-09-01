from __future__ import annotations

import importlib.util
import pathlib
import sys
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("verify_teacher_coverage.py")
spec = importlib.util.spec_from_file_location("verify_teacher_coverage", MODULE_PATH)
coverage = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules[spec.name] = coverage
spec.loader.exec_module(coverage)


def manifest(**overrides):
    counts = {
        "background": 340,
        "wall_face": 350,
        "door": 80,
        "window": 70,
        "stair": 40,
        "column": 20,
        "room_boundary": 60,
        "courtyard": 20,
        "shaft": 20,
    }
    value = {
        "samples": 20,
        "supervisedPixels": 1000,
        "totalPixels": 2000,
        "supervisionCoverage": 0.5,
        "supervisedPixelsByClass": counts,
    }
    value.update(overrides)
    return value


class TeacherCoverageGateTest(unittest.TestCase):
    def test_required_geometry_classes_include_room_boundary_quorum(self):
        requirements = [
            coverage.ClassRequirement("wall_face", 300, 0.30),
            coverage.ClassRequirement("door", 50, 0.05),
            coverage.ClassRequirement("window", 50, 0.05),
            coverage.ClassRequirement("room_boundary", 40, 0.04),
        ]

        report = coverage.verify_coverage(
            manifest(),
            requirements,
            min_samples=16,
            min_supervision_coverage=0.40,
            max_background_share=0.60,
        )

        self.assertTrue(report["passed"])
        self.assertFalse(report["releaseReady"])
        self.assertTrue(report["requirements"]["room_boundary"]["passed"])

    def test_missing_room_boundary_quorum_fails_closed(self):
        value = manifest()
        value["supervisedPixelsByClass"] = dict(value["supervisedPixelsByClass"])
        value["supervisedPixelsByClass"]["background"] += value["supervisedPixelsByClass"]["room_boundary"]
        value["supervisedPixelsByClass"]["room_boundary"] = 0

        with self.assertRaisesRegex(RuntimeError, "room_boundary supervised pixels"):
            coverage.verify_coverage(
                value,
                [coverage.ClassRequirement("room_boundary", 40, 0.02)],
                min_samples=1,
                min_supervision_coverage=0.0,
                max_background_share=1.0,
            )

    def test_missing_door_evidence_fails_closed(self):
        value = manifest()
        value["supervisedPixelsByClass"] = dict(value["supervisedPixelsByClass"])
        value["supervisedPixelsByClass"]["door"] = 5
        value["supervisedPixelsByClass"]["background"] += 75
        requirements = [coverage.ClassRequirement("door", 50, 0.03)]

        with self.assertRaisesRegex(RuntimeError, "door supervised pixels"):
            coverage.verify_coverage(
                value,
                requirements,
                min_samples=1,
                min_supervision_coverage=0.0,
                max_background_share=1.0,
            )

    def test_background_dominated_consensus_fails(self):
        value = manifest()
        value["supervisedPixelsByClass"] = {
            "background": 910,
            "wall_face": 40,
            "door": 10,
            "window": 10,
            "stair": 5,
            "column": 5,
            "room_boundary": 5,
            "courtyard": 5,
            "shaft": 5,
        }
        # Keep the fixture internally consistent so this test reaches the background-share gate.
        value["supervisedPixels"] = sum(value["supervisedPixelsByClass"].values())
        value["supervisionCoverage"] = value["supervisedPixels"] / value["totalPixels"]

        with self.assertRaisesRegex(RuntimeError, "background share"):
            coverage.verify_coverage(
                value,
                [],
                min_samples=1,
                min_supervision_coverage=0.0,
                max_background_share=0.80,
            )

    def test_inconsistent_manifest_counts_are_rejected(self):
        value = manifest(supervisedPixels=999, supervisionCoverage=999 / 2000)

        with self.assertRaisesRegex(RuntimeError, "do not sum"):
            coverage.verify_coverage(
                value,
                [],
                min_samples=1,
                min_supervision_coverage=0.0,
                max_background_share=1.0,
            )

    def test_requirement_parser_rejects_unknown_class(self):
        with self.assertRaises(Exception):
            coverage.parse_requirement("furniture:10:0.01")


if __name__ == "__main__":
    unittest.main()
