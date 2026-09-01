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
        "background": 400,
        "wall_face": 350,
        "door": 80,
        "window": 70,
        "stair": 40,
        "column": 20,
        "room_boundary": 0,
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
    def test_required_geometry_classes_can_pass_without_single_teacher_room_boundary(self):
        requirements = [
            coverage.ClassRequirement("wall_face", 300, 0.30),
            coverage.ClassRequirement("door", 50, 0.05),
            coverage.ClassRequirement("window", 50, 0.05),
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
        self.assertNotIn("room_boundary", report["requirements"])

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
            "wall_face": 50,
            "door": 10,
            "window": 10,
            "stair": 5,
            "column": 5,
            "room_boundary": 0,
            "courtyard": 5,
            "shaft": 5,
        }

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
