# Manzl — Saudi Reference-Driven Design Synthesis

## Goal

The uploaded 2D floor plan remains the geometric source of truth. Manzl combines that measured geometry with local AI/CV evidence and a rights-safe Saudi residential design knowledge base to create a walkable 3D interpretation without changing the user's room topology.

The release APK must remain usable without paid APIs, expiring trials, cloud credits, or internet access.

## What may be used during development

Development and benchmarking may study:

- public architectural photography and project descriptions;
- Saudi Architecture and Design Commission guidance;
- public descriptions of contemporary Saudi villas and regional architecture;
- temporary/free AI services for comparison only;
- open-source vision/rendering research whose license is compatible with the product.

A third-party service is never accepted as a runtime dependency unless a fully local replacement exists in the installed build.

## Copyright / rights boundary

Public photographs are reference material only. The application does **not** bundle, redistribute, trace, or texture the generated house with copyrighted internet photographs unless the asset has a verified redistributable license.

What we extract is the non-image design knowledge: material families, privacy/shading principles, spatial cues, lighting logic, color ranges and regional architectural vocabulary. Those priors are encoded in `SaudiResidentialKnowledge.kt` and procedurally applied to the user's own geometry.

## Current reference signals

The initial knowledge base uses public descriptions from sources such as:

- Saudi Architecture & Design Commission: architecture should reflect Saudi culture while engaging contemporary practice and innovation.
- King Salman Charter material: authenticity, flexibility and a methodology responsive to time, place and materials.
- Riyadh examples using local sandstone, sun-oriented louvers, majlis/reception functions and desert landscaping.
- Dammam residential-villa briefs emphasizing contemporary, comfortable, efficient Saudi living.
- AlUla residential concepts describing a public-to-private spatial sequence and majlis as part of the dwelling narrative.
- Contemporary Arab courtyard houses showing privacy, controlled daylight, shading and internal courtyards as climate/spatial devices.

These references guide presentation, not dimensions.

## Runtime synthesis stack

```text
2D plan image
  -> local preprocessing
  -> deterministic wall topology
  -> local door/opening inference
  -> future bundled on-device semantic model
  -> geometry reconciler (source of truth)
  -> Saudi design profile synthesis
  -> native 3D mesh
  -> local lighting/material renderer
  -> first-person collision/navigation
  -> local MP4 encoder
```

## Geometry rule

AI is evidence, not authority.

- A wall is never moved merely because a reference villa looks better another way.
- A room is never deleted or invented to match an internet photo.
- A detected dimension from the uploaded plan outranks style priors.
- Ambiguous topology must be surfaced with confidence/correction instead of silently hallucinated.

## Saudi visual priors in the current build

The current procedural profile introduces:

- warm off-white/mineral wall palette;
- warm stone/porcelain floor palette;
- local stone and restrained wood accent families for future PBR materials;
- Saudi-contemporary wall-height heuristics that scale with the detected footprint;
- strong privacy-gradient and solar-shading priorities;
- courtyard emphasis only when the source plan already contains a compatible void/space.

Future versions will expose explicit user-selectable profiles (for example Najdi contemporary, Hijazi contemporary and Eastern/coastal contemporary) rather than pretending that a regional identity can be inferred reliably from geometry alone.

## Model licensing rule

Do not bundle a pretrained model merely because it is free to download. Dataset and weight licenses must allow the intended distribution/use. For example, CubiCasa5K assets are CC BY-NC 4.0, so they are useful for research/benchmarking but are not the default production-weight source for a generally distributable build.

The preferred production route is a permissively licensed model or a model trained on a rights-cleared Saudi floor-plan corpus, exported to a local mobile runtime such as ONNX Runtime/TFLite.
