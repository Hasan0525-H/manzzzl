from __future__ import annotations

import argparse, json, pathlib, tempfile, unittest
from unittest import mock

import run_real_student_release as orchestrator


class RealStudentReleaseOrchestratorTest(unittest.TestCase):
    def args(self, root: pathlib.Path, **extra):
        values = dict(splits=root/"splits", workspace=root/"workspace", size=512, width=32,
                      epochs=2, batch=1, workers=0, lr=2e-4, seed=439, patience=1,
                      min_improvement=1e-4, cpu=True, geometry_evidence=root/"geometry",
                      assets=root/"assets", replace=False)
        values.update(extra); return argparse.Namespace(**values)

    def test_private_inputs_inside_public_worktree_are_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "outside the public Git worktree"):
            orchestrator.outside(orchestrator.REPO_ROOT / "private-plans", "private plans")

    def test_prepare_trains_before_locking_policy_and_never_opens_test(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp); a=self.args(root); calls=[]
            with mock.patch.object(orchestrator.verify_real_training_inputs,"verify",return_value={"trainerMayRead":["train","validation"],"trainerMustNotRead":["test"]}), \
                 mock.patch.object(orchestrator,"run",side_effect=lambda *x:calls.append(tuple(map(str,x)))), \
                 mock.patch.object(orchestrator.real_semantic_policy,"load_locked_policy",return_value=({"lockedBeforeHeldOutTestArtifacts":True},"sha")):
                report=orchestrator.prepare(a)
            self.assertEqual(report["stage"],"prepared")
            self.assertIn("train_real_student.py",calls[0][0])
            self.assertIn("real_semantic_policy.py",calls[1][0])
            self.assertIn("lock",calls[1])
            self.assertFalse(any("evaluate_real_student_test.py" in token for call in calls for token in call))

    def test_prepare_refuses_ambiguous_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp); a=self.args(root); a.workspace.mkdir()
            with self.assertRaisesRegex(FileExistsError,"ambiguous resume"):
                orchestrator.prepare(a)

    def test_heldout_verifies_pre_test_policy_before_evaluator(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp); a=self.args(root); p=orchestrator.paths(a.workspace)
            p["candidate"].mkdir(parents=True)
            events=[]
            def verify_policy(*args,**kwargs): events.append("policy"); return ({},"sha")
            def execute(*args):
                events.append("test")
                p["semantic"].write_text(json.dumps({"semanticAcceptancePassed":True}),encoding="utf-8")
            with mock.patch.object(orchestrator.real_semantic_policy,"load_locked_policy",side_effect=verify_policy), mock.patch.object(orchestrator,"run",side_effect=execute):
                report=orchestrator.heldout(a)
            self.assertEqual(events,["policy","test"])
            self.assertTrue(report["semanticAcceptancePassed"])

    def test_failed_semantic_gate_never_reads_geometry_evidence(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp); a=self.args(root); p=orchestrator.paths(a.workspace)
            p["candidate"].mkdir(parents=True); p["semantic"].write_text(json.dumps({"semanticAcceptancePassed":False,"semanticAcceptancePolicyLockedBeforeTest":True}),encoding="utf-8")
            with mock.patch.object(orchestrator,"run") as execute:
                with self.assertRaisesRegex(RuntimeError,"semantic gate"):
                    orchestrator.finalize(a)
                execute.assert_not_called()

    def test_finalize_orders_geometry_then_bundle_then_package(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=pathlib.Path(tmp); a=self.args(root); p=orchestrator.paths(a.workspace)
            p["candidate"].mkdir(parents=True); a.geometry_evidence.mkdir(); a.assets.mkdir()
            p["semantic"].write_text(json.dumps({"semanticAcceptancePassed":True,"semanticAcceptancePolicyLockedBeforeTest":True}),encoding="utf-8")
            order=[]
            def execute(*args):
                script=pathlib.Path(str(args[0])).name; order.append(script)
                if script=="verify_real_geometry_release.py": p["geometry"].write_text("{}",encoding="utf-8")
                if script=="finalize_real_student_release.py": p["release"].write_text(json.dumps({"releaseReady":True,"sha256":"a"*64}),encoding="utf-8")
            with mock.patch.object(orchestrator,"run",side_effect=execute):
                report=orchestrator.finalize(a)
            self.assertEqual(order,["verify_real_geometry_release.py","finalize_real_student_release.py","package_real_student_release.py"])
            self.assertTrue(report["releaseReady"])


if __name__ == "__main__": unittest.main()
