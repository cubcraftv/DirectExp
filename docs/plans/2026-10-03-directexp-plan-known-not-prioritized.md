# DirectExp Planning: Verified, But Not Pending

This document holds defects and findings that are already confirmed, but are intentionally not the next target. They remain important and should not be forgotten, but they are below the current priority threshold.

## Confirmed issues with a real root cause

### 1. Shader tokenizer bug around `#line` and block comments

Confirmed by a real class-level reproduction under JDK 21: Mojang's preprocessor leaves a `/*#moj_import ...*/#line N M` sequence on one line, and `DxShaderInterfaceLayout.tokenize` only recognizes `#` directives when they start a line. The block comment prevents the line-start condition from being preserved, causing the following `in vec3 Position;` token stream to be merged into invalid output.

This is a real bug, but it is not the current priority target because the active work is the current user-visible startup menu issue.

### 2. `DxBuffer` logical-size vs capacity mismatch

The `capacity()`-padded buffer size appears to be a likely source of the once-per-run warning about a buffer update range being outside the logical buffer. This is a strong, evidence-backed hypothesis tied to actual code smell, but it is not the active investigation target unless a fresh runtime failure pinpoints it.

### 3. Shader-compile incompatibilities in the broader 4.6 path

The following remain real, known blockers in the broader shader pipeline and are already documented in the source-of-truth plan:

- `TRIANGLE_FAN` support gaps,
- `gl_PointSize` output limitations,
- shader-model vs feature-level mismatch in certain translations,
- pipeline creation paths that still require a full end-to-end check against the real runtime.

These are important, but they are not the active target while the front-end startup menu is the user-facing priority.

## Deferred investigations

- The GUI black-button root cause remains unresolved and is intentionally deferred while the project is retargeting the Minecraft build to the stable `26.3` release.
- Follow-up RenderDoc pixel-history work and shader-output inspection remain useful, but they are not the immediate next step while the branch is being retargeted.

## Rule for this file

If a defect is already reproduced and understood, but the project chooses to defer it to keep the current change small and measurable, it belongs here. This file is a holding area for valuable, real findings that are known but not prioritized.
