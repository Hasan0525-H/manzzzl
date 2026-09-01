# Manzl — Offline Floor-Plan-to-Walkthrough Architecture

## Product contract

Manzl turns a residential floor-plan image into a walkable 3D house on Android. The installed application must not depend on paid APIs, expiring trials, cloud credits, remote inference, or a subscription to perform its core flow.

The runtime contract is stronger than "free tier": the `manzl-app` Android manifest intentionally does not request `android.permission.INTERNET`. Core processing therefore has to remain on-device.

## User flow

1. Pick a floor-plan image from the phone.
2. Press **تنفيذ**.
3. Show real pipeline progress from 0% to 100%.
4. Detect structural walls and openings.
5. Convert image-space geometry into a metric floor-plan graph.
6. Extrude the graph into a 3D scene.
7. Enter a first-person walkthrough.
8. Record the walkthrough and export MP4 locally.

## Current milestone: M1 geometry vertical slice

Implemented:

- Standalone Android application module: `manzl-app`.
- Arabic-first / RTL UI.
- Android Storage Access Framework image selection (no broad storage permission).
- Real progress stages rather than a cosmetic timer.
- Deterministic on-device structural-wall detector.
- Special color-aware path for blue architectural walls, with a monochrome fallback.
- Metric floor-plan domain model.
- Native wall extrusion into a triangle mesh.
- GPU walkthrough renderer using OpenGL ES 3.
- First-person look controls and movement controls.
- No runtime network permission.
- Dedicated CI workflow producing a debug APK artifact.
- Geometry unit test.

Not yet complete in M1:

- Semantic room classification.
- Robust door/window recognition for arbitrary plan styles.
- Collision and nav-mesh movement.
- PBR materials, baked/global lighting, ceilings and door geometry.
- MP4 recording/export.
- Production on-device neural model.

## Target architecture

### 1. Input normalization

Input images can be camera photos, screenshots, scans or exported drawings. The preprocessing pipeline will handle rotation, perspective correction, contrast normalization, cropping and scale calibration.

Planned implementation: OpenCV on Android or an equivalent bundled image-processing layer. It must ship inside the APK/app bundle and run locally.

### 2. Hybrid plan understanding

A single generative model is not allowed to invent geometry. The production analyzer is hybrid:

- deterministic CV for line topology and measurement consistency;
- an on-device segmentation/detection model for wall, door, window, stair and room-symbol classes;
- a geometry reconciler that snaps noisy detections into a coherent architectural graph;
- confidence scoring and a correction UI when the source is ambiguous.

The neural adapter is isolated behind `FloorPlanAnalyzer`, so model/runtime changes do not affect UI or rendering.

Planned runtime: ONNX Runtime Mobile/Android with a quantized model and hardware acceleration where available, CPU fallback everywhere else.

### 3. Architectural geometry engine

The geometry engine owns truth. AI output is treated as evidence, not as final geometry.

Responsibilities:

- wall centerlines and measured thickness;
- door/window openings;
- room polygons;
- stairs and level transitions;
- wall intersections and corner cleanup;
- metric scale calibration from printed dimensions when readable;
- collision boundaries and walkable-space graph.

### 4. Rendering

M1 uses OpenGL ES 3 directly to keep the first vertical slice small and deterministic. The production renderer will add:

- physically based materials;
- shadowing and image-based/environment lighting;
- Saudi residential defaults for wall height, door proportions, skirting and common finishes;
- level-of-detail and occlusion optimizations for mid-range Android phones;
- 60 fps target where device capability allows.

A production renderer may use Filament or an equivalent bundled open-source engine, but no remote renderer is part of the runtime contract.

### 5. Walkthrough and navigation

- Eye-height first-person camera.
- Touch look + virtual joystick.
- Continuous movement while held, not step buttons.
- Wall collision and doorway traversal.
- Optional height presets and accessibility speed controls.
- Deterministic spawn point selected from largest valid walkable region.

### 6. MP4 recording

Recording must stay local. The target is direct renderer-to-encoder output using Android `MediaCodec` and MP4 muxing, avoiding cloud transcode services and avoiding unnecessary screen-capture quality loss.

Recording session contract:

- starts when the user begins the tour;
- records the rendered scene at a device-appropriate resolution/frame rate;
- stops on **إنهاء الجولة**;
- writes an MP4 to app storage and exports it through Android's media/document APIs.

### 7. Persistence

Projects will be local-first:

- source-plan URI/copy;
- normalized raster cache;
- parsed plan graph;
- generated scene metadata;
- user corrections;
- exported video paths.

No account is required for the core product.

## Five-year cost strategy

The design avoids recurring service cost by construction:

- no mandatory backend;
- no API key in the installed app;
- no cloud inference;
- no remote 3D generation;
- no remote video encoding;
- no account requirement;
- open file formats for project/scene interchange;
- pinned build dependencies and reproducible CI.

Third-party services may be used during research or benchmarking, but a result cannot become a runtime dependency unless it also has a fully local replacement in the release build.

This can make day-to-day use free for the life of a compatible installed build. It cannot guarantee that future Android versions or future phone hardware will remain compatible for exactly five years without maintenance; compatibility updates are a software-maintenance concern, not a usage-fee dependency.

## Quality gates before v1.0

1. Build succeeds from a clean checkout.
2. Release APK has no `INTERNET` permission.
3. A representative Saudi-plan test corpus is evaluated with topology metrics, not visual impression only.
4. Door connectivity must be correct above the release threshold.
5. Metric scale error must be reported, with confidence.
6. Walkthrough must not pass through walls.
7. MP4 export must complete without server access.
8. Airplane-mode end-to-end acceptance test passes.
9. Mid-range Android thermal/performance test passes for a 10-minute walkthrough.
10. Licenses for every bundled model/library are documented and redistributable.
