# Manzl Ultra Reconstruction — 2D floor plan → verified real-house geometry

## Non-negotiable product rule

The uploaded plan is the geometry source of truth. A visually attractive 3D result is a failure when
its walls, openings, rooms, stairs, courtyards or proportions do not match the source drawing.
Development cost and runtime service cost must remain zero. Runtime inference is offline-only.

## Five-expert architecture

### Expert 1 — Raster2Seq teacher

Use the official Raster2Seq architecture/checkpoints during development as the strongest global
raster-to-vector teacher currently adopted by Manzl. Its output is room/corner/polygon evidence, not
final geometry authority. Teacher predictions are retained as pseudo-labels/consensus evidence and are
distilled into the mobile Manzl reconstruction student.

### Expert 2 — RoomFormer teacher

Use RoomFormer as an architecturally different polygon teacher. Agreement between Raster2Seq and
RoomFormer is much stronger evidence than confidence from either model alone. Disagreement is kept as
uncertainty and is never resolved by averaging arbitrary geometry.

### Expert 3 — MobileSAM boundary refiner

Use MobileSAM on high-resolution crops around wall faces, openings, columns, shafts and ambiguous
junctions. The purpose is pixel-accurate boundary refinement after the global teachers have already
identified what region matters. MobileSAM is not allowed to decide room topology by itself.

### Expert 4 — OpenCV + OCR metric expert

OpenCV provides independent edge/line/intersection/morphology measurements. ML Kit OCR provides
printed dimensions and scale evidence. OpenCV proposals are accepted only when independent raster
fidelity improves; long dimension/title lines are not automatically walls.

### Expert 5 — Manzl deterministic geometry solver

This is the final authority. It fuses wall faces, junctions, openings, room polygons, stairs and scale
under architectural constraints, then rasterizes the proposed geometry back over the source. A hard
gate blocks 3D when global or localized mismatch remains.

## Teacher/student split

Raster2Seq and RoomFormer use research-oriented PyTorch stacks and custom operators that are not a good
release dependency for an Android APK. Manzl therefore uses them as heavy teachers on free development
GPU resources. Their consensus, together with MobileSAM/OpenCV measurements and rights-safe synthetic
ground truth, trains a standard-operator `manzl_reconstruction_student.onnx` model.

The release APK runs:

1. Manzl reconstruction student through ONNX Runtime Android.
2. MobileSAM ONNX encoder/decoder for high-resolution boundary refinement.
3. OpenCV native measurement/refinement.
4. ML Kit OCR for metric scale.
5. Deterministic geometry fusion + fidelity/reconstruction gates.

No network permission, cloud model or paid API is required at runtime.

## No silent fallback

The app may continue using deterministic geometry while development assets are incomplete, but it must
not label that path as **Ultra**. `UltraModelAvailability.ultraRuntimeReady` becomes true only when all
required bundled ONNX assets are present and ONNX Runtime initializes. A release claiming Ultra quality
must fail its release gate if those assets are missing.

## Licensing policy for this personal project

The public repository stores source code, manifests, conversion/training scripts and rights-safe model
artifacts only. Checkpoints whose dataset/model terms restrict redistribution are downloaded only into
a local development cache and never committed to this public repository or packaged into a public APK.
Personal/non-commercial evaluation can use such checkpoints when their terms permit it, but the release
student must be trained/distilled from inputs whose resulting artifact can be lawfully retained and
used. When in doubt, keep the checkpoint teacher-only and do not redistribute it.

## Quality gates before visual work resumes

Visual materials, PBR, façade styling and lighting remain downstream. 2D→3D reconstruction is considered
ready only when a real-plan regression corpus demonstrates that wall faces, topology, openings, room
polygons and metric scale remain stable across image styles/crops and that the reconstructed top view
matches the source plan. Any regression in reconstruction blocks visual-feature work for that release.
