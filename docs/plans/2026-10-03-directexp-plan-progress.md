# DirectExp Planning: Progress Ledger

This document is the current progress record for the project. It is intentionally the shortest, clearest summary of what is already verified and what remains active work.

## Verified status

- Milestone 1 (bootstrap/config/planning) is complete and works end to end.
- Milestone 2 (native surface bring-up) is complete enough to stand up a real D3D11 device/context and flip-model swap chain.
- Milestone 3 (real texture upload and GPU texture path) is confirmed active at runtime. The `native GPU texture path active` diagnostic line is the key runtime proof.
- Milestone 4.1 (shader toolchain bring-up) is runtime-verified on Windows/MSYS2 UCRT64.
- Milestone 4.2 (source/preprocessing stage) is unit-tested; live shader comparison is still outstanding.
- Milestone 4.3 (IR stage / real GLSL -> SPIR-V) is Java-side verified, with native live exercise still outstanding.
- Milestone 4.4 (reflection/resource layout) is authored and unit-tested outside Gradle; live decompile verification is still outstanding.
- Milestone 4.5 (HLSL emission / D3D compile) is authored and unit-tested outside Gradle; live integration remains unverified.
- Milestone 4.6 commit 1 is confirmed on Windows. The later commits are authored and partially validated, but the larger draw path remains subject to ongoing follow-up.
- Latest user-reported runtime visuals: mob textures look correct; block textures are stretched (grass is an example); item textures are broken; main-menu buttons have black fill and lack their expected texture. These are observations, not root-cause diagnoses, and no matching screenshot/log was included.

## Current working conclusions

- The project is beyond raw bootstrap: the DirectX path is creating a real device, swap chain, and native GPU texture objects.
- Textured rendering is visibly active for mobs, but the reported block, item, and GUI-button texture defects remain open. Correct mob textures do not establish correctness of other vertex UV, atlas, view, or sampling paths.
- The strongest current evidence is not a broad “everything works” claim; it is a sequence of verified stepwise milestones: config to backend plan to native device create to native texture upload and blit.
- The active human-visible gap is in the main-menu visual path, not in the native device/surface plumbing.

## Current active references

- Historical bootstrap archive: [2026-09-20-directexp-bootstrap-plan.md](./2026-09-20-directexp-bootstrap-plan.md)
- Shader/draw source-of-truth: [2026-09-28-directexp-milestone4.6-source-of-truth.md](./2026-09-28-directexp-milestone4.6-source-of-truth.md)
- Current prioritized target: [2026-10-02-directexp-milestone4.7-main-menu-visuals.md](./2026-10-02-directexp-milestone4.7-main-menu-visuals.md)

## Keep in mind

This file should only carry evidence-backed status. If a claim is untested, or a theory is still being tested, it belongs in one of the dedicated planning docs instead of here.
