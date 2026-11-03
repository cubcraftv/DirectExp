# DirectExp: Depth-Only Render Pass Support

**Date:** 2026-10-31
**Target branch:** `dev`
**Status:** Implemented on 2026-10-31 (unit-tested; Windows runtime verification pending)
**Suggested location:** `docs/plans/2026-10-31-directexp-depth-only-render-passes.md`

## 1. Problem

With MaLiLib (and therefore Litematica) installed, the game crashes on the first
rendered frame after joining a world:

```
java.lang.IllegalArgumentException: DirectX render passes support 1..8 color attachments
  at DxRenderPassBackend.<init>(DxRenderPassBackend.java:120)
  at DxCommandEncoderBackend.createRenderPass(DxCommandEncoderBackend.java:46)
  at FrontendCommandEncoder.createRenderPass(FrontendCommandEncoder.java:163)
  at LevelRenderer.executeAlwaysOnTop(LevelRenderer.java:500)
```

Removing MaLiLib and Litematica removes the crash.

### Root cause chain

1. MaLiLib's `MixinGameRenderer.malilib_consistentDepthIsAlwaysRequired` wraps the
   `List.isEmpty()` call in `GameRenderer.renderLevel` and always returns `false`.
   Vanilla derives `consistentDepthRequired` from "post chain list is not empty", so the
   flag is now always `true`.
2. With the flag set, vanilla allocates `targets.alwaysOnTopDepth` and
   `LevelRenderer.executeAlwaysOnTop` runs the **"Integrate always on top depth"** pass.
   That pass has a depth attachment and **zero color attachments**.
3. Vanilla's `FrontendCommandEncoder.createRenderPass` explicitly allows this: it only
   requires `colorAttachmentCount + (depth != null ? 1 : 0) > 0`.
4. `DxRenderPassBackend`'s constructor rejects `colorAttachments().size() < 1` and throws.

The same limitation already appears in the log as three skipped pipelines:
`integrate_depth`, `blit_depth`, `oit_water_mask`, each reporting
"a render pipeline needs 1..8 color targets, not 0".

This is a DirectExp gap, not a Litematica bug. Depth-only passes are legitimate
Blaze3D usage and other mods or vanilla features can create them too.

## 2. Goal

DirectExp can create and run render passes and pipelines that have **no color
attachment**, with or without a depth attachment, within the D3D11 feature level 10.1
floor.

### Non-goals

- Changing the 10.1 feature level floor.
- Making every Litematica / MaLiLib render path correct (see section 8, follow-ups).
- Patching MaLiLib. A local workaround exists but is separate (section 9).

## 3. Findings from the code review

What already works, so it should not be touched:

| Area | Finding |
|------|---------|
| Native `setRenderTargets0` (`native/src/directexp_bridge_draw.cpp`) | Already accepts **0..8** color handles and a depth handle of `0`. It calls `OMSetRenderTargets(count, count > 0 ? rtvs : nullptr, dsv)`. |
| `DxBridge.setRenderTargets` Javadoc | Says "up to eight". Accurate, but should state that zero is valid. |
| `DxRenderPassBackend.colorTarget()` / `colorTargetMipLevel()` | Defined at lines ~566-572 but have **no callers** in `src`. Safe to make nullable or remove. |
| Native blend state (`directexp_bridge_pipeline.cpp`) | D3D11 has no PSO and no `NumRenderTargets`. `CreateBlendState` describes slots 0..7 independently, so a pipeline with a single non-writing slot is valid against a pass with zero bound RTVs. |

What needs to change (Java only, no native change expected):

| # | File | Problem |
|---|------|---------|
| A | `DxRenderPassBackend.java` ~L119-121 | Rejects `< 1` color attachments. |
| B | `DxRenderPassBackend.java` ~L127-172 | Takes size from `colorAttachments.get(0)` and the format check from the first color view. Both break with zero colors. |
| C | `DxRenderPassBackend.java` ~L566-572 | `colorTargets[0]` / `colorTargetMipLevels[0]` throw `ArrayIndexOutOfBounds` for an empty array (currently unused). |
| D | `DxPipelineStateMapping.java` ~L181 | `targetCount < 1` throws `UnsupportedOperationException`. This is what skips `integrate_depth`, `blit_depth`, `oit_water_mask`. |
| E | `DxPipelineStateMappingCreateInfoTest.java` | Existing test `depthOnlyPipelineWritesNoColor` covers a `null` target entry, but a truly empty target list has no test. |

Correction to an earlier suggestion: it is **not** necessary to set
`NumRenderTargets = 0` in pipeline state. That is a D3D12 concept. In D3D11 the pass
decides what is bound, and the pipeline only supplies a blend state.

## 4. Design decisions

1. **Pass with zero colors requires a depth attachment.** Vanilla already rejects a pass
   with no attachments at all, but DirectExp should still throw a clear
   `IllegalArgumentException` for "no color and no depth" because it would have no size.
2. **Target size source:** first color view if present, otherwise the depth view
   (`getWidth(0)`, `getHeight(0)` at the view's base mip).
3. **Format validation:** keep the existing RGBA8 / RGBA16F / RGBA32F rule for color
   attachments only. When there are no colors, skip it. Validate that the depth view is
   a DirectX depth texture instead.
4. **Zero-color pipelines map to one non-writing slot.** This reuses the behavior that
   already exists for `null` target entries (write mask `0`, blending off), keeps
   `DxPipelineStateDescriptor` at `1..8` targets, and requires **no native change**.
   The alternative (allow `colorTargetCount == 0` in the descriptor and native code)
   adds change across the JNI boundary for no benefit on D3D11.
5. **More than 8 color attachments** remains an explicit rejection.
6. **A depth-only pass whose depth view has no native DSV** must not crash. It follows
   the existing "pass-no-native-depth" report path, and `nativeTargetsBound` stays
   `false` so draws are skipped.

## 5. Implementation steps

### Step 1: `DxRenderPassBackend` constructor

1. Change the count check to:
   ```java
   int colorCount = descriptor.colorAttachments().size();
   if (colorCount > MAX_COLOR_ATTACHMENTS) { throw ...; }
   if (colorCount == 0 && descriptor.depthAttachment() == null) { throw ...; }
   ```
   Update the exception messages: "support 0..8 color attachments" and "a render pass
   needs at least one color or depth attachment".
2. Resolve the reference view once:
   `GpuTextureView sizeView = colorCount > 0 ? firstColorView : depthTextureView;`
   and use it for `targetWidth` / `targetHeight`.
3. Wrap the first-attachment, `clearColor` and `colorFormat` logic in `if (colorCount > 0)`.
   Leave `colorTargets`, `colorTargetMipLevels`, `colorViews` as zero-length arrays and
   `colorClears` empty when there are no colors.
4. Keep the per-attachment `isClosed()` / `DxTexture` / same-format checks inside the
   loop. The loop already runs zero times for an empty list.
5. Add a depth view check for the zero-color case: it must be a `DxTextureView`, not
   closed, else throw `IllegalArgumentException`.

### Step 2: `beginNativePass`

1. The color loops (`hasNativeRenderTargetView`, handle collection, clears) already
   iterate `colorViews.length`, so they work for zero. Confirm no `colorViews[0]`
   access remains.
2. Pass an empty `long[]` to `setRenderTargets`. Native already supports this.
3. If neither a color view nor a native depth view exists, report and return `false`
   (do not call `setRenderTargets` with nothing to bind).
4. Partial-rectangle depth clear path (`clearDepthRegion`) is unchanged.

### Step 3: remove or guard the `[0]` accessors

- `colorTarget()` / `colorTargetMipLevel()` have no callers. Delete them, or return
  `null` / `0` when the arrays are empty. Prefer deletion; add them back if a draw path
  needs them later.

### Step 4: pipeline state mapping

In `DxPipelineStateMapping.describe`:

1. Replace the `targetCount < 1` rejection with: if the list is empty, treat it as a
   single non-writing target (equivalent to `Arrays.asList((ColorTargetState) null)`).
2. Keep the `> MAX_COLOR_TARGETS` rejection.
3. Update the comment block above (it currently says depth-only is only a `null` entry).

No change to `DxPipelineStateDescriptor` (still `1..8`) or native pipeline code.

### Step 5: documentation touch-ups

- `DxBridge.setRenderTargets` Javadoc: state that an empty color array with a non-zero
  depth handle is valid and maps to `OMSetRenderTargets(0, NULL, dsv)`.
- Add a short entry to `docs/plans/2026-09-20-directexp-progress-log.md`. Mark it as
  *implemented, unit-tested*, and *not runtime-verified* until step 7 passes on Windows.

## 6. Tests (run with `./gradlew test`, any OS)

All of these use `RecordingDxBridge`, no GPU needed.

| Test | Location | Asserts |
|------|----------|---------|
| Depth-only pass is accepted | new `DxRenderPassBackendDepthOnlyTest` | Constructing with 0 color + 1 depth does not throw. Target size equals the depth view size. |
| Depth-only pass binds with empty color array | same | `RecordingDxBridge` records `setRenderTargets` with an empty array and a non-zero depth handle. |
| Depth clear is applied | same | `clearDepthStencil` is recorded when the depth attachment has a clear value and the area is full. |
| No attachments is rejected | same | 0 color + no depth throws `IllegalArgumentException` with the new message. |
| More than 8 colors is rejected | same | 9 color attachments throw. |
| Existing color pass unchanged | same or existing | 1 and 8 color attachments still bind as before. |
| Missing native DSV degrades | same | Depth view without a DSV gives `nativeTargetsBound == false` and a diagnostic report, not a throw. |
| Empty target list maps to one non-writing slot | extend `DxPipelineStateMappingCreateInfoTest` | `describe(info(List.of(), List.of(), List.of()))` gives `colorTargetCount == 1`, write mask `{0}`, blend `{0}`. |
| 9 targets still rejected | existing test | Unchanged. |

If `RecordingDxBridge` does not yet record `setRenderTargets`, add that recording as
part of this work.

## 7. Windows verification (manual)

`./gradlew build` and the native DLL only build on Windows with the D3D11 SDK, so the
following must be checked on a real machine and the progress log should say so
explicitly.

1. Test instance: Sandy Bridge HD 2000, feature level 10.1, DirectExp strict mode.
2. **Baseline:** MaLiLib + Litematica installed, join a world. Expect **no crash**.
3. Log check: the warnings for `integrate_depth` and `blit_depth` must be gone. Note
   what happens to `oit_water_mask` (see risks).
4. Visual check: terrain, water and the always-on-top debug overlays render normally.
5. Enable a Litematica schematic and look for depth glitches (schematic blocks
   z-fighting or drawing through terrain).
6. Optional: run with the D3D11 debug layer if the bridge exposes a switch, and look for
   "render target / blend state count mismatch" messages.

## 8. Risks and follow-ups

| Risk | Detail | Mitigation |
|------|--------|------------|
| Empty pixel shader output | `integrate_depth` and similar shaders may write only `gl_FragDepth`. SPIRV-Cross HLSL output with no `SV_Target` could produce an empty or odd output struct, or fail D3DCompile. | After step 4, check the `DirectExp HLSL DUMP` for these pipelines. Fix in the HLSL post-processing in `directexp_shader_hlsl.cpp` if needed. |
| Depth read while bound | The integrate pass samples `alwaysOnTopDepth` while writing the main depth. D3D11 silently unbinds an SRV that is also bound as DSV. | Confirm the binder does not hold a stale SRV; check for debug-layer hazards. |
| `oit_water_mask` | May hit additional unsupported state beyond the 0-target check (for example stencil or format). | Treat as a separate follow-up if it is still skipped after this work. |
| Sodium / Litematica multidraw paths | The device reports `multiDrawIndirect=false`, `drawIndirect=false`, `nonZeroFirstInstance=false`. Litematica's Sodium branch and Litematica's `executeOit` hook may rely on these. | Out of scope. Track after the crash is fixed. |
| Performance | MaLiLib forces the consistent-depth path every frame, adding a full-screen depth pass on an HD 2000. | Measure frame time before and after. If heavy, consider the MaLiLib workaround below. |

### Follow-up review results (2026-10-31, static analysis, nothing run on a device)

| Item | Finding | Action |
|------|---------|--------|
| Empty pixel shader output | The two depth-only fragment shaders (`integrate_depth.fsh`, `blit_depth.fsh`) write only `gl_FragDepth` (`blit_depth` reads with `texture()`, `integrate_depth` with `texelFetch` plus `discard`). SPIRV-Cross should emit an output struct holding only `SV_Depth`, which D3D11 allows at ps_4_1. This is expected, not observed. | None in code. Confirm in the `HLSL DUMP` log lines on Windows. |
| `oit_water_mask` | Its shader (`rendertype_water_mask.fsh`) still declares `fragColor = ColorModulator`, but the pipeline has no color target. D3D11 discards writes to an unbound SV_Target0, and the mapped blend slot has write mask 0, so this is valid. Only the 0-target check was blocking it. | None. Confirm it no longer logs "unsupported state". |
| SRV vs render target hazard | `setRenderTargets0` does not unbind shader resources first. The D3D11 runtime forces a conflicting SRV to NULL when a resource becomes a render or depth target, so the result is correct. At worst the debug layer warns. `integrate_depth` samples the always-on-top depth and writes the main depth, which are different textures. | Left native code unchanged. It cannot be built or tested off Windows, and the runtime guarantee makes the change unnecessary. |
| Litematica / MaLiLib pipeline state | All their pipelines use `QUADS`, `LINES`, `DEBUG_LINES` or `DEBUG_LINE_STRIP`, one color target, one vertex binding, depth compare / write / bias, wireframe polygon mode and custom blend functions. `DxPipelineStateMapping` expresses all of these. Litematica's batch draw uses `drawMultipleIndexed`, which `DxRenderPassBackend` already implements. | None. Pipelines may still fail at shader or HLSL compile time, which is logged separately. |
| `minecraft:end_of_frame` post effect warning | The string appears in none of the uploaded sources (Litematica, MaLiLib, Sodium, Direcium, DirectExp), so it comes from vanilla or another mod. Its relation to the crash is unknown. | Re-check the log after this patch. |
| Diagnostics | A depth-only pass can now confirm itself in the log. | Added a one-time line: `DirectExp: native depth-only render pass path active (first depth-only pass bound ...)`. |

## 9. Local workaround (independent of this plan)

If the crash must be avoided before this lands: in the MaLiLib source, remove
`MixinGameRenderer.malilib_consistentDepthIsAlwaysRequired`. The depth-only pass then
no longer runs, but MaLiLib's "world last" rendering may lose correct depth against
always-on-top gizmos. This was not tested.

## 10. Commit plan (per `AGENTS.md`)

Work on `dev`, 2-3 coherent commits:

1. `DxPipelineStateMapping`: accept an empty color target list (+ test).
2. `DxRenderPassBackend`: depth-only render pass support (+ tests, `RecordingDxBridge`
   recording if needed).
3. Docs: `DxBridge` Javadoc and progress log entry, stating what is unit-tested and
   what still needs Windows runtime verification.

## 11. Definition of done

- [ ] `./gradlew test` passes with the new tests. (Run through a JUnit shim on Linux: 39 tests passed, including neighbouring suites. Gradle itself was not run.)
- [x] A depth-only pass can be constructed and bound (unit-level).
- [x] Empty color target list maps to one non-writing slot (unit-level).
- [ ] On Windows: joining a world with MaLiLib + Litematica no longer crashes.
- [ ] On Windows: `integrate_depth` / `blit_depth` no longer logged as unsupported.
- [ ] Progress log records which items were verified at runtime and which were not.
