# Milestone 4 Breakdown: Archived Historical Plan

This document was the original sub-milestone breakdown for the shader and pipeline work. It is now archived and no longer the active source of truth.

The live planning has been split into the current docs:

- [2026-09-28-directexp-milestone4.6-source-of-truth.md](./2026-09-28-directexp-milestone4.6-source-of-truth.md)
- [2026-10-02-directexp-milestone4.7-main-menu-visuals.md](./2026-10-02-directexp-milestone4.7-main-menu-visuals.md)
- [2026-10-03-directexp-plan-progress.md](./2026-10-03-directexp-plan-progress.md)
- [2026-10-03-directexp-plan-known-not-prioritized.md](./2026-10-03-directexp-plan-known-not-prioritized.md)
- [2026-10-03-directexp-plan-unverified-theory.md](./2026-10-03-directexp-plan-unverified-theory.md)

## Status summary

The detailed 4.1-4.6 work is no longer tracked here because most of the original plan items have already been completed, tested, or superseded by later runtime findings.

- 4.1: Native shader toolchain bring-up — verified on Windows/MSYS2 UCRT64.
- 4.2: Source/preprocessing stage — unit-tested; live shader comparison remains outstanding.
- 4.3: IR stage / real GLSL -> SPIR-V — Java-side verified; native live exercise still outstanding.
- 4.4: Reflection and resource layout — authored and unit-tested outside Gradle; live validation remains outstanding.
- 4.5: HLSL emission and D3D compile — authored and unit-tested outside Gradle; native verification remains outstanding.
- 4.6: Draw-path follow-up — authored and partially runtime-verified; the source-of-truth doc captures the current open issues and remaining work.

## Why this file is intentionally shortened

The original breakdown was valuable while the work was still being sequenced, but the project has moved past the “single active plan” stage. The current work is no longer best tracked as a single long milestone list; it is split into:

1. verified progress,
2. known-but-not-prioritized blockers,
3. unverified theories, and
4. the one active pending target.

This file is kept only as a historical record. The detailed implementation notes below the original milestone list have been intentionally cut off because they are stale and no longer represent the active plan.

## Current source of truth

Use [2026-09-28-directexp-milestone4.6-source-of-truth.md](./2026-09-28-directexp-milestone4.6-source-of-truth.md) for the real shader/draw-state facts and open items. Use [2026-10-02-directexp-milestone4.7-main-menu-visuals.md](./2026-10-02-directexp-milestone4.7-main-menu-visuals.md) for the active prioritized target.
