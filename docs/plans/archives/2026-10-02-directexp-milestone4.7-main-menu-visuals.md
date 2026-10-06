# Current Pending Change: Milestone 4.7 Visual Completion

**Created:** 2026-10-02. **Status:** active, prioritized target. **Scope:**
resolve the three visible startup gaps reported after the first DirectX draw
path: the initial clear appears blue instead of red, main-menu buttons show
their labels without their visual backgrounds, and the panorama is absent
because cube textures are not yet supported.

This is the repository's current prioritized target. It is the only active
implementation item in the plan stack that is currently expected to move
forward immediately; the other plan docs separate verified progress, known
but deferred issues, and unverified theory.

Companion docs: `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`
for the 4.1-4.6 shader/draw work, and
`docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md` for the
Vulkan comparisons and current open items. This document takes priority for
these three user-visible outcomes.

**Implementation status (2026-10-03):** the clear, button-texture, and
panorama code paths have implementation work in place, but visual acceptance
is not complete. The focused texture tests, client compilation, and Windows
native bridge build pass. The latest user-reported runtime result confirms mob
textures look correct, while all block textures and item textures appear
stretched/broken (grass is one block example), and main-menu buttons appear
filled black without their expected texture. These symptoms need diagnosis;
they do not yet identify a shared root cause.

**Runtime update (2026-10-04):** after aligning the fragment input signature
with vertex outputs, the user reports normal rendering works. Item rendering
works initially but stops after a while; the duration and matching runtime
evidence have not yet been recorded. Track this separately from the now-fixed
signature mismatch and investigate item-rendering state/resource lifetime if
it reproduces.

## Latest Runtime Observation (2026-10-03)

Reported visual results:

- **Mob textures:** appear correct.
- **Block textures:** all appear stretched; grass_block is one example.
- **Item textures:** also appear stretched/broken.
- **Main-menu buttons:** expected texture is absent and the button fill appears
  black.

The user reports that RenderDoc's Texture Viewer shows the block/item atlas
image itself stretched, not only the rendered block/item geometry. This moves
the investigation upstream of per-draw UVs: verify the atlas resource's actual
dimensions and stored pixels, then trace atlas creation, CPU-side assembly,
and uploads. It still does not prove that the reported 512x256 resource is
incorrect; compare it with the final stitched atlas dimensions, not an
individual source image. Working mob textures show that some textured
rendering works, but do not establish that block, item, or GUI texture
coordinates, formats, views, and sampling state are correct. Preserve the
RenderDoc capture and matching `latest.log` from the same run when checking
resource identity and upload diagnostics.

## Outcome And Order

Deliver a recognizable main menu in this strict order:

1. **Correct the clear color.** A known red clear must produce red, not blue.
2. **Render button backgrounds.** Main-menu buttons must have visible
   backplates beneath their existing text, including their hover/focus state.
3. **Render the panorama.** Load and sample all six cube faces through the
   normal DirectX texture/view/binding path.

Finish and verify each item before moving to the next. The first two are
independent of cubemap support; the panorama depends on ordinary sampled
texture binding working correctly. Do not expand this milestone to clouds,
texel buffers, readback, or general renderer compatibility unless one blocks
one of these three outcomes.

## Entry Gate

- Build the Java and Windows native targets from the current sources before
  interpreting another runtime result. Confirm `build/native/directexp_native.dll`
  is the artifact loaded by `runClient` and that its JNI signatures match
  `NativeDxBridge`; a stale DLL can make runtime evidence misleading or crash
  before a useful diagnostic is logged.
- Capture a baseline screenshot and `run/logs/latest.log` from the same run.
  Record whether a native render-target/view was active and whether the
  relevant GUI pipelines compiled and drew. Keep the existing 10.1 feature
  level floor.

## Priority 1: Clear-Color Channel Order

**Observed:** the initial screen clear is blue where red is expected. The
current code has two distinct color paths, so do not change channel order
based on the screenshot alone:

- Render-pass clear: Blaze3D supplies float RGBA components to
`DxRenderPassBackend.beginNativePass`, which passes them directly to
`ID3D11DeviceContext::ClearRenderTargetView`.
- CPU texture clear/fallback: `DxCommandEncoderBackend.clearColorTexture`
  and `DxTexture.fillRegion` write packed bytes directly; the byte order may
  differ from the render-pass clear convention.

**Work:**

- Trace the packed color from Blaze3D's caller through each applicable path.
  Confirm the packed-int convention against the snapshot's `ARGB` helper or
  decompiled caller, then compare it with the Vulkan clear path; do not infer
  the convention from the integer's name.
- Add or correct focused assertions in `DxDrawHelpersTest` for opaque red,
  blue, and alpha, and cover the CPU texture-fill conversion separately.
- Exercise an actual render-pass clear and, if the startup screen uses it,
  the CPU clear/upload fallback. Use pure red and pure blue test colors so a
  red/blue swap is unambiguous.

**Done when:** a fresh `runClient` screenshot shows the intended red clear;
red and blue remain distinct in both render-pass and CPU-fallback paths; the
Java tests pass; and the log has no clear-target failure. If the expected red
comes from a later textured draw rather than the pass clear, update this
finding and trace that draw instead of changing the clear helper blindly.

## Priority 2: Main-Menu Button Backgrounds

**Observed:** the latest report says the expected button texture is absent and
the button fill appears black. Earlier captures showed the button text and
relevant draw calls, but that does not prove the textured backplate rendered
correctly.

**Work:**

- Capture the main-menu frame and identify the exact draw(s) that should
  produce a button backplate. Determine whether this snapshot uses a solid
  fill, a GUI texture/nine-slice, or both for normal and hovered buttons.
- Trace those draws through pipeline compilation, render-target binding,
  vertex/index submission, texture-view/sampler binding, and alpha blending.
  Correlate missing pixels with `latest.log` diagnostics for invalid
  pipelines, missing native views, skipped draws, or unavailable resources.
- Fix the narrowest failing stage. Keep the text draw intact; do not replace
  the menu with a hard-coded rectangle. Add a focused regression test for any
  extracted color/resource/draw-selection logic and a once-per-cause
  diagnostic when a required background draw is skipped.

**Done when:** the main menu shows filled button backgrounds under the
existing labels, and normal, hovered, and keyboard-focused states remain
visible in a fresh screenshot. The test run is free of new invalid-pipeline,
missing-view, or skipped-draw warnings for those backplates.

## Additional Reported Texture Symptoms

The user reports that RenderDoc's Texture Viewer shows the block/item atlas
itself stretched, while mob textures look correct. This makes atlas pixel
assembly/upload the primary lead; per-draw UVs cannot explain an atlas image
that is already distorted in the Texture Viewer. Verify the resource ID and
descriptor for the atlas bound by one block draw and one item draw, and
compare its stored pixels with the expected final stitched atlas. If those
draws bind different resources, inspect both. Then trace the atlas write/copy
events through source row length, row pitch, destination dimensions, and
region bounds. Keep the GUI button symptom separate until evidence connects
it to the same resource or draw path, and preserve the working mob-texture
path as a control.

**RenderDoc lead (2026-10-03):** the user reports that the atlas image itself
looks stretched in RenderDoc's Texture Viewer. The initial concern that an
atlas was `512x256` instead of `256x256` was mistaken; do not treat that
dimension comparison as evidence of a size bug. A more specific visual clue
is that the same pixel appears to be reused for following pixels, perhaps
across a whole row. This points toward a possible source row-stride/pitch or
pixel-layout mismatch, but remains unverified.

For the next capture, inspect one atlas upload and compare the source buffer's
actual layout with the upload parameters and destination:

- Full-mip `UpdateSubresource`: supplied width/height and `SysMemPitch`
  (currently computed as `width * 4` for RGBA8).
- Region `UpdateSubresource`: mip dimensions, box bounds, pointer offset to
  `(x,y)`, and `SysMemPitch` (currently computed as `mipWidth * 4`).
- Java mirror before native upload: whether each row already contains the
  repeated pixels, or whether repetition first appears in the GPU resource.

If repeats are already in the source/mirror, trace the Java write/copy
source-row-length and bytes-per-pixel assumptions. If the source is correct
but the GPU texture is not, compare the native upload pitch, format, and
region bounds against that source layout. Atlas creation dimensions continue
to be passed directly from Java to `CreateTexture2D`, but there is no current
evidence that the resource dimensions themselves are wrong.

## Priority 3: Cubemap Panorama

**Current limitation:** `DxTexture.createNativeTextureHandle` rejects every
texture whose `depthOrLayers` is not 1. Native texture creation hardcodes
`ArraySize = 1`, and native SRVs use `D3D11_SRV_DIMENSION_TEXTURE2D`. Thus a
six-face panorama cannot reach a native cube view today.

**Vulkan reference:** the supplied `C:\Users\user\projects\vulkan.zip`
contains plain-text decompiled classes. `VulkanGpuTexture` creates a 2D image
with `arrayLayers(depthOrLayers)`. `VulkanGpuTextureView` treats usage bit
`16` as cubemap-compatible, selects a cube view, and exposes six layers. The
same bit is documented in `DxTextureBinding`, but the DirectX path currently
ignores it. Preserve this contract and verify it again if the Minecraft
snapshot changes.

**Work:**

- Trace the snapshot's panorama texture creation, face upload, view creation,
  and pipeline declarations against the decompiled Vulkan implementation.
  Confirm actual dimensions, usage bits, mip count, and per-face upload calls
  before choosing the Java/native API shape.
- Extend the texture resource model to retain array-layer/cubemap metadata
  and upload the six faces without corrupting the CPU mirror. Keep ordinary
  one-layer 2D textures unchanged.
- Create a D3D11 cube-compatible texture (six 2D array slices and the
  cube-resource flag) and a `TEXTURECUBE` shader-resource view. Validate
  face/mip ranges and keep SRV and sampler binding consistent with the
  existing engine-owned resource layout and SPIRV-Cross HLSL output.
- Add CPU-side tests for cube usage/layer validation and native integration
  checks for six-face creation, view creation, and face uploads. Test on the
  project's minimum supported feature level where available.

**Done when:** the real main-menu panorama pipeline binds a native cube view
and sampler, renders the six intended faces with the correct orientation,
and remains visible through startup and resize. The normal 2D texture path
and the first two priorities continue to pass.

## Verification And Remaining Work

For each priority, run `./gradlew.bat test`, build the Windows native bridge,
and launch `./gradlew.bat runClient` with a fresh DLL. Preserve one screenshot
per completed priority and the corresponding startup log. Update the 4.6
source-of-truth doc with any Vulkan facts or code contracts learned, then add
a dated result to the bootstrap plan's Progress Log.

Clip-space depth, render-target readback, and texel-buffer support remain
separate open items from Milestone 4.6. They should be reopened only if
runtime evidence shows one directly blocks these three outcomes.
