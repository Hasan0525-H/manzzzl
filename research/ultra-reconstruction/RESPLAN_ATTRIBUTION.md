# ResPlan attribution for Manzl reconstruction training

Manzl may use the **geometry-only** ResPlan release as one zero-cost training curriculum for the local reconstruction student.

- Dataset: **ResPlan: A Large-Scale Vector-Graph Dataset of 17,000 Residential Floor Plans**
- Authors: Mohamed Abouagour and Eleftherios Garyfallidis
- Upstream repository: `https://github.com/m-agour/ResPlan`
- Pinned repository tree/commit used by the training workflow: `e2b78fe069aee1ab1e1828a612743f308e3c32a7`
- Pinned `ResPlan.zip` Git blob SHA-1: `24d12b8739bdb55e0accc60ddd4cb460bcc9886c`
- Upstream data license: **Creative Commons Attribution 4.0 International (CC BY 4.0)**
- Upstream code license: MIT

The upstream LICENSE states that the released data contains derived geometric representations and connectivity information, not source listing images, listing text, prices, addresses, geolocation or personal information. Manzl does not attempt to recover or redistribute those source images. `render_resplan_training.py` creates new raster drawings from the released vector geometry and uses them only as supervised reconstruction inputs/targets.

ResPlan is useful for realistic residential topology and metric proportions but its README notes a South-Asian regional scope and normalized wall thickness. For that reason it is **not** treated as a Saudi-domain benchmark or as sole training evidence. It is mixed with independent Manzl procedural curricula, teacher consensus where licensing permits, and separate Saudi/Arabic real-plan regression tests before any model can be called release-ready.

This attribution file must remain with any Manzl training pipeline or model provenance record that uses ResPlan-derived supervision.
