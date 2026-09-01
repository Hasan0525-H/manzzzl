# Tiny local semantic neural model

## Purpose

`TinySemanticPatchModel` is a small, fully local classifier used as **additional evidence** for architectural symbols. It predicts one of four patch classes:

- `OTHER`
- `DOOR`
- `WINDOW`
- `STAIR`

It is not a generative model and it is not allowed to create topology on its own.

## Geometry safety contract

The model only runs on regions of interest that already come from deterministic geometry/CV:

- door/window candidates must be measured collinear wall gaps;
- stair candidates must come from the repeated-tread detector;
- accepted predictions become `LOCAL_AI` evidence;
- `SemanticEvidenceConsensus` may strengthen them only when independent evidence agrees;
- `GeometryEvidenceFusion` remains the final authority before anything enters `FloorPlan`.

A high neural score therefore cannot move a wall, resize a room, invent a staircase location, or override explicit user correction.

## Runtime

Architecture: `256 -> 16 ReLU -> 4 softmax`.

Input is a 16×16 local architectural-ink patch. The two dense layers are stored as symmetric int8 weights with per-layer scales. Inference is implemented directly in Kotlin and uses no TensorFlow, ONNX, cloud API, downloadable model, account, or network connection.

The quantized model is only a few kilobytes of weights, which avoids adding a large inference runtime to the APK.

## Training data rights

The model was trained only on procedurally generated symbol patches created for this project. The generator draws synthetic quarter-circle door swings, double-line windows, repeated stair treads, generic lines/boxes/noise and 90-degree rotations. No photographs, internet floor plans, CubiCasa images, proprietary CAD files, or third-party model weights are part of the training set.

This removes the non-commercial dataset-license risk that would come from shipping weights trained on CC BY-NC floor-plan corpora.

## Validation scope

A held-out synthetic validation set from the same procedural family reached approximately 96% classification accuracy after int8 weight quantization. This number is **not** a real-world floor-plan accuracy claim. Release decisions must be based on the planned rights-cleared residential-plan corpus and topology/opening metrics, not this synthetic benchmark.

## Fail-closed thresholds

Runtime evidence requires both a high class probability and a clear margin over the runner-up. Stair confirmation uses stricter thresholds than door/window confirmation. Weak or ambiguous predictions emit no semantic evidence.

The model is intentionally narrow. Future larger local models may replace or augment it, but must preserve the same geometry-authority and offline/licensing rules.
