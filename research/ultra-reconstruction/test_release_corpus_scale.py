from __future__ import annotations

import release_corpus_scale as gate
import unittest


class ReleaseCorpusScaleTest(unittest.TestCase):
    def preflight(self) -> dict:
        return {
            "schema": 2,
            "pipeline": "private-real-release-training-input-gate",
            "passed": True,
            "testReservedForFinalEvaluation": True,
            "trainSamples": 150,
            "validationSamples": 40,
            "testSamples": 50,
            "trainSourceGroups": 130,
            "validationSourceGroups": 35,
            "testSourceGroups": 45,
            "variantDensityBySplit": {
                "train": {
                    "maximumSamplesPerSourceGroup": 4,
                    "meanSamplesPerSourceGroup": 150 / 130,
                },
                "validation": {
                    "maximumSamplesPerSourceGroup": 2,
                    "meanSamplesPerSourceGroup": 40 / 35,
                },
                "test": {
                    "maximumSamplesPerSourceGroup": 2,
                    "meanSamplesPerSourceGroup": 50 / 45,
                },
            },
        }

    def test_release_scale_passes_with_enough_independent_homes(self):
        report = gate.require(self.preflight())
        self.assertTrue(report["releaseCorpusScalePassed"])
        self.assertEqual(report["policyVersion"], 1)

    def test_many_variants_cannot_replace_independent_test_homes(self):
        evidence = self.preflight()
        evidence["testSamples"] = 200
        evidence["testSourceGroups"] = 10
        evidence["variantDensityBySplit"]["test"] = {
            "maximumSamplesPerSourceGroup": 25,
            "meanSamplesPerSourceGroup": 20.0,
        }
        report = gate.evaluate(evidence)
        self.assertFalse(report["releaseCorpusScalePassed"])
        self.assertFalse(report["checks"]["test:sourceGroups"]["passed"])
        self.assertFalse(report["checks"]["test:maximumSamplesPerSourceGroup"]["passed"])

    def test_one_house_with_too_many_heldout_variants_fails_even_when_group_count_passes(self):
        evidence = self.preflight()
        evidence["variantDensityBySplit"]["validation"]["maximumSamplesPerSourceGroup"] = 7
        report = gate.evaluate(evidence)
        self.assertFalse(report["releaseCorpusScalePassed"])
        self.assertFalse(report["checks"]["validation:maximumSamplesPerSourceGroup"]["passed"])

    def test_small_train_corpus_fails_closed(self):
        evidence = self.preflight()
        evidence["trainSourceGroups"] = gate.MIN_SOURCE_GROUPS["train"] - 1
        with self.assertRaisesRegex(RuntimeError, "below the immutable release benchmark scale"):
            gate.require(evidence)


if __name__ == "__main__":
    unittest.main()
