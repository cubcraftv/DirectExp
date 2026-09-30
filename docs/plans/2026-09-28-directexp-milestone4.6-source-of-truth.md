# Milestone 4.6 -- Source of Truth

**Created:** 2026-09-28. **Scope:** everything a session needs to implement
Milestone 4.6 (pipeline object assembly and first draw) plus its prerequisite
resource work, without re-deriving it. If this file and code disagree, the
code is right about *what exists* and this file is right about *what was
decided and why* -- fix whichever is stale in the same commit.

Companion docs: `docs/specs/2026-09-20-directexp-design.md` (overall design),
`docs/plans/2026-09-25-directexp-milestone4-breakdown.md` (4.1-4.6 checklist),
`docs/plans/2026-09-20-directexp-bootstrap-plan.md` (Progress Log).

Legend: **[V-DECOMPILE]** verified by reading the decompiled Vulkan backend
(26.2 snapshot 1, supplied as `vulkan.zip`, files named `Vulkan*.java`);
**[V-MSDOC]** verified against Microsoft Learn; **[V-WEB]** verified against
multiple independent, cited external sources (Khronos/community docs,
Microsoft Learn, a real cross-backend engine's own documentation) because no
single vendor doc states it plainly; **[DECIDED]** a project decision;
**[OPEN]** needs a decision before the stated commit; **[UNVERIFIED]**
authored, not built/run.

---

## 1. Status

| Item | State |
| --- | --- |
| Milestone 4.5 (HLSL emission + D3DCompile) | Built on Windows/MinGW-w64, `runClient` clean (reported by project owner, 2026-09-28) |
| 4.6 commit 1 (GPU buffer / sampler / SRV backing) | **Built and run on Windows** (owner report, 2026-09-28): build OK, `runClient` OK, log showed `native GPU buffer path active (... 512 bytes, bind mask 4)` and `native GPU sampler path active (... NEAREST/NEAREST, aniso 1)`. **No** `native GPU texture view path active` line appeared -- see 6.5. |
| 4.6 commit 2 (render/depth targets + pipeline objects) | **Authored** (this session). Java compiles against stubs (including the real 4.2-4.5 stage classes), 32 unit tests pass under a stand-in runner, native files pass `-fsyntax-only` under `x86_64-w64-mingw32-g++`. **Not** built via Gradle, **not** run in `runClient`. |
| 4.6 commit 3 (shader interface layout -- fixes the all-pipelines-invalid bug) | **Authored** (this session, using the now-supplied `com.mojang.blaze3d.vulkan.glsl` package). See section 9. `DxShaderInterfaceLayout` compiles under a real `javac` 21 and passes 16 unit tests under a stand-in runner; its output was additionally validated against a real `glslangValidator 15.1.0 --target-env vulkan1.0` and inspected with `spirv-dis`/`spirv-cross` (see 9.5) -- stronger verification than commits 1-2 got, still **not** run through the project's own `shaderc`/Gradle/`runClient`. |
| 4.6 commit 3 follow-up (feature-level/shader-model fix + vertexStride fix) | **Authored** (this session), from the owner's `runClient` log *after* commit 3 was applied. See section 10. The SM5-vs-FL10.1 branch logic was compiled and run for real under Wine against real mingw-w64 `d3d11.h` enum values (all four feature-level cases assert correctly); `DxPipelineStateDescriptor`'s changed validation passes 5 new unit tests under `javac` 21. The native `directexp_shader_hlsl.cpp`/`directexp_bridge_surface.cpp`/`directexp_common.*` edits themselves are **not** `-fsyntax-only`-checked (no real `jni.h`/`shaderc`/SPIRV-Cross headers in this sandbox, only a hand-written partial `jni.h` stub good enough for a JNI-free isolated logic test, not the whole files) -- reviewed by hand instead. **Not** run through the project's own build/`runClient`. |
| 4.6 commit 4 | Not started (see updated section 8) |

Verification available in the authoring sandbox (Linux): `javac` 21, a
hand-written JUnit-compatible runner over Minecraft stubs, and
`x86_64-w64-mingw32-g++ -fsyntax-only` with the real mingw-w64 Windows
headers (jni headers faked for win32). It cannot link, run, or load
Minecraft. Anything needing those is **[UNVERIFIED]** until a Windows
`./gradlew.bat build` + `runClient`.

## 2. Decisions

- **[DECIDED]** 4.6 absorbs "real GPU handle for `DxBuffer`, SRVs for
  textures, real `ID3D11SamplerState` for `DxSampler`" as prerequisite scope
  (project owner, 2026-09-28). MetalExp has none of this; DirectExp is ahead.
- **[DECIDED]** CPU-side storage stays authoritative; the native object is a
  GPU mirror that may be absent (`hasNative*Handle()` is the switch). Same
  contract as `DxTexture`. Never make a native handle mandatory.
- **[DECIDED]** Buffer sync is eager (every write/unmap pushes its range).
  Dirty-range coalescing until bind time is a later optimisation.
- **[DECIDED]** Pipeline cache key = the `RenderPipeline` object itself in an
  `IdentityHashMap` (what Vulkan does; see 3.3). This satisfies the design
  spec's "key on render state, not shader identity" because a `RenderPipeline`
  instance already encodes the full state.
- **[DECIDED]** `QUADS` -> triangle list, no quad handling in the pipeline
  layer (see 3.2).
- **[DECIDED 2026-09-28]** Uniform slices: **staging buffer + copy**. A
  `GpuBufferSlice` (offset+length into a large uniform buffer) reaches a D3D11
  constant-buffer slot by copying the slice into a per-slot constant buffer
  (`UpdateSubresource`, full copy) at bind time -- plain D3D11.0, no 11.1
  ranged binding. Consequence for commit 3: the draw path reads the slice from
  `DxBuffer`'s **CPU mirror** (always authoritative), so uniform buffers
  larger than 64 KiB never need a native mirror; commit 1's "constant buffers
  <= 64 KiB get a mirror, larger stay CPU-only" rule stays as is, and the
  mirror of a small uniform buffer becomes redundant (candidate to drop later).
- **[DECIDED 2026-09-28]** Render/depth targets: implemented in commit 2
  (section 7): RTV for color render attachments, DSV for depth attachments,
  typeless depth resources so a depth texture can also be sampled.

## 3. Facts extracted from the decompiled Vulkan backend [V-DECOMPILE]

### 3.1 `RenderPipeline` accessors actually used (`VulkanRenderPipeline`)
`getVertexFormat()` -> `VertexFormat` with `getElements()`,
`getVertexSize()` (stride), `getOffset(element)`; `element.format()` is a
`GpuFormat`. `getVertexFormatMode()` (topology), `getPolygonMode()`
(`FILL`/`WIREFRAME`), `isCull()`, `getDepthStencilState()` (nullable; null =
no depth test) with `writeDepth()`, `depthTest()` (`CompareOp`),
`depthBiasConstant()`, `depthBiasScaleFactor()`; `getColorTargetState()` with
`blendFunction()` (`Optional<BlendFunction>`; `.color()`/`.alpha()` each have
`op()`, `sourceFactor()`, `destFactor()`) and `writeRed/Green/Blue/Alpha()`;
`getLocation()` (debug name). One vertex binding (binding 0), per-vertex
input rate. Depth-bias is enabled only when **both** constant and scale
factor are non-zero. Front face = clockwise (Vulkan `1`), cull = back when
`isCull()`. Depth attachment format is D32_SFLOAT. Vulkan builds a
with-depth and a without-depth pipeline variant; D3D11 does not need that
(OM state is dynamic).

### 3.2 Enum mapping tables (Vulkan values -> meaning, for the D3D11 equivalents in commit 2)
- **Topology** (`Mode`): `POINTS`->point list, `DEBUG_LINES`->line list,
  `DEBUG_LINE_STRIP`->line strip, `TRIANGLES`/`QUADS`/**`LINES`**->triangle
  list, `TRIANGLE_STRIP`->strip, **`TRIANGLE_FAN`->fan**.
  Consequences: `QUADS` and `LINES` arrive as already-expanded triangle
  indices, so D3D11 uses `TRIANGLELIST` for them. **D3D11 has no triangle
  fan** -- `TRIANGLE_FAN` needs an index-conversion path in commit 3
  (check if anything in 26.2 actually issues it before building one).
- **Blend factor**: ZERO, ONE, SRC_COLOR, ONE_MINUS_SRC_COLOR, DST_COLOR,
  ONE_MINUS_DST_COLOR, SRC_ALPHA, ONE_MINUS_SRC_ALPHA, DST_ALPHA,
  ONE_MINUS_DST_ALPHA, CONSTANT_COLOR, ONE_MINUS_CONSTANT_COLOR,
  CONSTANT_ALPHA, ONE_MINUS_CONSTANT_ALPHA, SRC_ALPHA_SATURATE. All have D3D11
  equivalents; constant-color/alpha factors use the blend factor passed to
  `OMSetBlendState` (Vulkan sets it via a blend-constants state -- check
  where 26.2 sets it before hard-coding zeros).
- **Blend op**: ADD, SUBTRACT, REVERSE_SUBTRACT, MIN, MAX (all in D3D11).
- **CompareOp**: ALWAYS_PASS, LESS_THAN, LESS_THAN_OR_EQUAL, EQUAL,
  NOT_EQUAL, GREATER_THAN_OR_EQUAL, GREATER_THAN, NEVER_PASS.
- **Color write mask**: red/green/blue/alpha booleans -> `RenderTargetWriteMask`.
- **Vertex element formats**: `GpuFormat` (full list in `VulkanConst.toVk`).
  DXGI has no 3-component 8- or 16-bit formats (`RGB8_*`, `RGB16_*`); those
  need widening or a shader-side unpack. 3-component 32-bit (`R32G32B32_*`)
  exists. Check which formats vanilla vertex formats actually use.

### 3.3 Pipeline cache and compile entry (`VulkanDevice`)
`pipelineCache` is keyed by the `RenderPipeline` instance
(`IdentityHashMap`, `computeIfAbsent`). `precompilePipeline(pipeline,
shaderSource)` compiles vertex + fragment stages and builds the pipeline;
`getVertexFormat().getElementAttributeNames()` feeds shader-compile inputs.
`clearPipelineCache()` must destroy cached pipelines.

### 3.4 Draw semantics (`VulkanRenderPass`) -- match the existing stubs 1:1
- `draw(firstVertex, vertexCount)` -> D3D11 `Draw(vertexCount, firstVertex)`.
- `drawIndexed(baseVertex, firstIndex, indexCount, instanceCount)` ->
  `DrawIndexedInstanced(indexCount, instanceCount, firstIndex, baseVertex, 0)`.
- `IndexType.SHORT`/`INT` -> `DXGI_FORMAT_R16_UINT`/`R32_UINT`.
- `setVertexBuffer(slot, buffer)` / `setIndexBuffer(buffer, type)` /
  `setUniform(name, buffer|slice)` / `bindTexture(name, view, sampler)` /
  `enableScissor`/`disableScissor` (disable = full output rect) /
  viewport set to `(0,0,outputWidth,outputHeight)`, depth 0..1.
- Uniform binding asserts bit 128 (`USAGE_UNIFORM`) on the buffer.
- Quirk (not our problem, do not copy): the decompiled `vkCmdDraw` call passes
  `vertexCount` where `firstInstance` would go. D3D11 `Draw` has no such
  parameter.
- **Y axis:** Vulkan clip space is Y-down, D3D11 is Y-up. The Vulkan path sets
  a positive-height viewport, so the flip (if any) lives in shader
  compilation/translation, not the viewport. Determine where before commit 3;
  wrong Y = upside-down output.

### 3.5 `GpuBuffer.Usage` bits (`VulkanConst.bufferUsageToVk`, `VulkanRenderPass`)
8 = copy-dst, 16 = copy-src, **32 = vertex, 64 = index, 128 = uniform**,
256 = uniform-texel-buffer. Implemented in `DxBufferBinding` as raw ints.
Re-verify on every snapshot bump.

### 3.6 Sampler (`VulkanGpuSampler`)
`mipmapMode` linear iff `maxLod.orElse(1000) > 0.25`; `maxLod =
max(0.25, maxLod.orElse(1000))`; `mipLodBias = 0`; anisotropy on iff
`maxAnisotropy > 1`; address: `REPEAT`, `CLAMP_TO_EDGE` only. Textures and
views are separate objects (`VulkanGpuTexture`, `VulkanGpuTextureView`
with `baseMipLevel`/`mipLevels`).

## 4. Microsoft-documented D3D11 facts relied on [V-MSDOC]

- `CreateBuffer(const D3D11_BUFFER_DESC*, const D3D11_SUBRESOURCE_DATA*,
  ID3D11Buffer**)`. Constant buffer `ByteWidth` must be a multiple of 16 and
  <= `D3D11_REQ_CONSTANT_BUFFER_ELEMENT_COUNT` (4096 constants = 64 KiB) on a
  pre-11.1 runtime; the 11.1 runtime (Win8+) allows larger and range-binding.
- `UpdateSubresource(dst, subresource, pDstBox, pSrc, rowPitch, depthPitch)`:
  for a constant buffer `pDstBox` **must be NULL** (no partial update);
  destination may not be immutable/dynamic usage; buffer box coordinates are
  bytes; an empty box is a no-op (top<bottom and front<back needed).
- `D3D11_SAMPLER_DESC`: Filter, AddressU/V/W, MipLODBias, MaxAnisotropy
  (1..16, used only with ANISOTROPIC filters), ComparisonFunc, BorderColor[4],
  MinLOD, MaxLOD (>= MinLOD).
- `D3D11_FILTER` values: `MIN_MAG_MIP_POINT=0`, `..._MIP_LINEAR=0x1`,
  `MIN_POINT_MAG_LINEAR_MIP_POINT=0x4`, `MIN_LINEAR_MAG_MIP_POINT=0x10`,
  `MIN_MAG_LINEAR_MIP_POINT=0x14`, `MIN_MAG_MIP_LINEAR=0x15`,
  `ANISOTROPIC=0x55` -- i.e. bit 0x10 = min linear, 0x04 = mag linear,
  0x01 = mip linear (the native code builds the enum from those bits).
- `D3D11_TEX2D_SRV { MostDetailedMip, MipLevels }`; the view can cover a mip
  sub-range of the resource.
- From the earlier session (unchanged): `CreateInputLayout`,
  `D3D11_INPUT_ELEMENT_DESC`, `D3D11_BLEND_DESC`/`_RENDER_TARGET_BLEND_DESC`,
  `D3D11_DEPTH_STENCIL_DESC`, `D3D11_RASTERIZER_DESC`, `CreateVertexShader`/
  `CreatePixelShader`, `IASetVertexBuffers`/`IASetIndexBuffer`,
  `IASetPrimitiveTopology`, `OMSetBlendState`/`OMSetDepthStencilState`/
  `RSSetState`, `Draw`, `DrawIndexedInstanced` signatures.

## 5. Commit plan for 4.6

1. **GPU buffer / sampler / SRV backing** -- *this commit, authored.*
2. **Render/depth targets + pipeline object assembly** -- *authored; see section 7.* New `DxRenderPipeline` (VS/PS,
   input layout from `VertexFormat` + the D3DCompile VS bytecode, blend,
   depth-stencil, rasterizer state); `DxDeviceBackend.precompilePipeline`
   becomes the orchestrator chaining 4.2 source -> 4.3 SPIR-V -> 4.4 reflect
   + layout -> 4.5 HLSL/bytecode -> pipeline objects, cached per 3.3;
   `clearPipelineCache` releases them. Includes the render/depth-target
   widening if signed off (section 2).
3. **Draw path** -- `DxRenderPassBackend` binds (RTV/DSV, viewport/scissor,
   pipeline, VB/IB, constant buffers per the section 2 decision, SRVs +
   samplers per the 4.4 layout) and issues `Draw`/`DrawIndexedInstanced`;
   `drawMultipleIndexed` loops.
4. **Docs/progress log + first-triangle verification notes.**

Each commit must be coherent alone (AGENTS.md commit policy).

## 6. Commit 1 -- what was built

### 6.1 Java bridge contract (`DxBridge`, implemented by `NativeDxBridge`)
All new methods are `default` (throw `UnsupportedOperationException`, release
methods no-op) per the interface's existing convention.

| Method | Contract |
| --- | --- |
| `long createBuffer(int bindMask, int byteWidth)` | `ID3D11Buffer`, `D3D11_USAGE_DEFAULT`, contents undefined until first update. `bindMask` = OR of `BUFFER_BIND_VERTEX=1`, `_INDEX=2`, `_CONSTANT=4`. Native rounds a constant buffer up to 16; rejects constant > 64 KiB. Throws `IllegalStateException` on any failure. |
| `void updateBuffer(long h, ByteBuffer mirror, int offset, int length)` | `mirror` = direct buffer of the **whole** CPU copy, same layout as the GPU buffer, capacity >= GPU ByteWidth; position/limit ignored, offsets absolute. Non-constant: box update of `[offset, offset+length)`. Constant: full update (D3D11 cannot partially update). |
| `void releaseBuffer(long h)` | `Release()` + delete wrapper; 0 is a no-op. |
| `long createSampler(addrU, addrV, minLinear, magLinear, mipLinear, maxAniso, maxLod)` | `SAMPLER_ADDRESS_WRAP=0`/`_CLAMP=1`. `maxAniso > 1` selects `ANISOTROPIC` (ignores the three linear flags), clamped 1..16. `MaxLOD = max(0, maxLod)`, `MinLOD = 0`, comparison NEVER, AddressW clamp. |
| `void releaseSampler(long h)` | as above |
| `long createTextureView(long textureHandle, int baseMip, int mipLevels)` | `ID3D11ShaderResourceView`, Texture2D, format taken from the wrapper (`Texture::format`); validates the mip range; the SRV holds its own COM reference so it may outlive `releaseTexture`. |
| `void releaseTextureView(long h)` | as above |

### 6.2 Client classes (`client.backend`)
- `DxBufferBinding` -- Minecraft-free: usage bits -> bind mask, 16-byte
  alignment, "should we even mirror" rule (bind purpose present, size > 0,
  constant <= 64 KiB).
- `DxBuffer(DxBridge, usage, size)` -- mirror storage padded to a multiple of
  16 bytes; native handle created if allowed; initial full sync; `write()` and
  `syncRange()` push ranges; `close()` idempotent, releases the handle.
  `hasNativeBufferHandle()` / `nativeBufferHandle()` for commit 3.
- `DxMappedBufferView(data, onClose)` -- close is the "unmap"; a write-mapped
  view (created in `DxCommandEncoderBackend.mapBuffer` when `write == true`)
  syncs its slice on close. Idempotent.
- `DxSampler(DxBridge, ...)` -- Vulkan-matching LOD rules (3.6). **Deviation
  [DECIDED]:** anisotropy is forwarded only when both min and mag are
  `LINEAR`, because D3D11's `ANISOTROPIC` filter is linear everywhere and
  would silently smooth a `NEAREST` sampler.
- `DxTextureView(DxBridge, texture, base, mips)` -- SRV only when the texture
  has a native handle (RGBA8_UNORM single-layer today).
- `DxNativeResourceLog` -- log-once per kind (`active` / `fallback` / `sync`).
- `DxDeviceBackend` passes `surfaceLease.dxBridge()` into all of the above.

### 6.3 Native (`native/src/directexp_bridge_resource.cpp`, added to CMake)
JNI names: `createBufferNative`, `updateBufferNative`, `releaseBuffer0`,
`createSamplerNative`, `releaseSampler0`, `createTextureViewNative`,
`releaseTextureView0`. New structs in `directexp_common.h`: `Buffer`,
`Sampler`, `TextureView`; `Texture` gained `format`; `BUFFER_BIND_*` and
`SAMPLER_ADDRESS_*` constants (keep in sync with `DxBridge`). Operates on
`g_activeDevice`/`g_activeContext` like the texture bridge; non-Windows stubs
return 0.

### 6.4 Tests (`src/test/java/.../client/backend`)
`DxBufferBindingTest`, `DxBufferTest`, `DxSamplerAndViewTest` with
`RecordingDxBridge` (no native code). 19 tests, all passing under the
stand-in runner; **not yet run under real Gradle/JUnit**.

### 6.5 What runClient showed / should show (commit 1)
- Log lines, once each: `native GPU buffer path active`, `native GPU sampler
  path active`, `native GPU texture view path active`. A `fallback` line means
  a native call threw -- read its reason.
- No `... upload failed; GPU copy may be stale` line.
- Behaviour otherwise unchanged (still a black fill; nothing binds these yet).
- **Observed (owner):** only the buffer and sampler lines. The texture-view
  line is emitted only when a `DxTextureView` is created over a `DxTexture`
  that already has a native handle. Not being able to tell from the log alone
  whether (a) no native RGBA8 texture existed yet (then the pre-existing
  `native GPU texture path active` line would also be absent) or (b) textures
  existed but views never got a native handle (a real bug), commit 2 adds more
  log lines to discriminate -- report whether `native GPU texture path active`
  appeared, and see 7.6.
- With the D3D debug layer on, no `CreateBuffer`/`UpdateSubresource`
  validation errors (constant buffer box, ByteWidth alignment are the likely ones).

## 7. Known risks / gaps (carry into later commits)

1. **Threading.** `UpdateSubresource` uses the immediate context, which is not
   thread-safe. Buffer writes now hit it from whichever thread writes.
   Believed to be the render thread (same assumption as texture upload) but
   unproven; if `runClient` shows corruption or debug-layer threading errors,
   route native updates through a render-thread queue.
2. **Eager sync cost.** A constant buffer write re-sends the whole buffer.
   Fine for now; coalesce at bind time later.
3. **Constant buffers > 64 KiB** have no native mirror (section 2 OPEN item).
4. **Native textures are RGBA8-only** and have no RTV/DSV (section 2 OPEN item).
5. **`TRIANGLE_FAN`, 3-component 8/16-bit vertex formats, Y-flip location,
   blend-constant source** -- see 3.2/3.4; each needs a check against the
   decompile before commit 2/3, not assumed.
6. **Stale-GPU-copy on failed sync** is logged once and tolerated; the CPU
   mirror remains correct, but a draw using a stale GPU buffer would render
   wrong data. Commit 3 should re-sync (or fall back) if a buffer's last sync
   failed.

## 7. Commit 2 -- what was built (render/depth targets + pipeline objects)

### 7.1 Facts newly verified [V-DECOMPILE]
- Texture usage bits (`VulkanConst.textureUsageToVk`): 1 = copy-dst, 2 =
  copy-src, **4 = texture binding (sampled), 8 = render attachment** (color or
  depth by format), 16 = cubemap. Implemented in `DxTextureBinding`.
- Main color target format is RGBA8_UNORM, depth is D32_FLOAT (pipeline
  creation hard-codes both). Vertex attribute location = element index, one
  vertex binding (slot 0), per-vertex. Cull = back; Vulkan front face = CW.
  No dynamic blend constants (they stay zero). Pipeline dynamic state is only
  viewport + scissor.
- `GpuFormat` has 56 formats in `VulkanConst.toVk` (+`S8_UINT`); vertex-usable
  ones map in `DxPipelineStateMapping.vertexAttributeFormat`.

### 7.2 D3D11 / DXGI facts used
- All enum numbers Java hard-codes (`D3D11_BLEND_*`, `_BLEND_OP_*`,
  `_COMPARISON_*`, `_CULL_*`, `_FILL_*`, `D3D_PRIMITIVE_TOPOLOGY_*`, the DXGI
  vertex formats) were read from the real mingw-w64 `d3d11.h`/`d3dcommon.h`/
  `dxgiformat.h` and are pinned by `static_assert`s in
  `directexp_bridge_pipeline.cpp` -- a header mismatch breaks the native build.
- Depth textures sampled as SRV need a typeless resource format with a DSV
  format and an SRV format that differ: D32 -> `R32_TYPELESS` / DSV
  `D32_FLOAT` / SRV `R32_FLOAT`; D24S8 -> `R24G8_TYPELESS` / `D24_UNORM_S8_UINT` /
  `R24_UNORM_X8_TYPELESS` [V-MSDOC + community-confirmed]. D16 and D32S8 follow
  the same convention (`R16_TYPELESS`; `R32G8X24_TYPELESS` with
  `D32_FLOAT_S8X24_UINT` / `R32_FLOAT_X8X24_TYPELESS`) but are not confirmed
  against a doc page and vanilla probably doesn't use them.
- `D3D11_RENDER_TARGET_BLEND_DESC`: `SrcBlendAlpha`/`DestBlendAlpha` may not be
  `*_COLOR` options [V-MSDOC].
- SPIRV-Cross HLSL names vertex-stage inputs `TEXCOORD<location>`
  (`CompilerHLSL::to_semantic`, read from upstream `spirv_hlsl.cpp`); the
  project sets no vertex-attribute remap, so the input layout uses
  `TEXCOORD<elementIndex>`.

### 7.3 Bridge contract additions (`DxBridge` / `NativeDxBridge`)
| Method | Contract |
| --- | --- |
| `long createTexture(format, w, h, mips, bindMask)` | `TEXTURE_FORMAT_*` (RGBA8_UNORM, D32_FLOAT, D24_UNORM_S8_UINT, D32_FLOAT_S8_UINT, D16_UNORM) x `TEXTURE_BIND_*` (SHADER_RESOURCE=1, RENDER_TARGET=2, DEPTH_STENCIL=4). RENDER_TARGET needs a color format, DEPTH_STENCIL a depth format. Throws `IllegalStateException` otherwise. `uploadTextureRgba8` still works on any RGBA8 texture. Old `createTexture2D` kept (unused by the client now). |
| `long createTextureView(tex, baseMip, mips)` (extended) | Creates the SRV (if the texture has SHADER_RESOURCE) over the mip range, and an RTV *or* DSV (if the texture has one) on `baseMip` only. A view object may hold any subset; native handles the pointers, Java only knows the texture's bind purposes. |
| `long createPipeline(byte[] vs, byte[] ps, DxPipelineStateDescriptor)` | VS, PS, input layout (`TEXCOORD<i>`, slot 0, per-vertex; none if zero attributes), blend, depth-stencil (stencil off), rasterizer (`FrontCounterClockwise = TRUE`, scissor always enabled, depth clip on), topology, vertex stride. Nothing bound. `IllegalStateException` on any failure. |
| `void releasePipeline(long)` | releases all six COM objects + wrapper |

`DxPipelineStateDescriptor` (bridge package, Minecraft-free) carries D3D11 ABI
numbers; `stateArray()`/`floatStateArray()` order must match `StateIndex` /
`FloatStateIndex` in `directexp_bridge_pipeline.cpp` (natively length-checked).

### 7.4 Client classes
- `DxTextureBinding` (usage -> bind mask; color always SRV-capable, depth
  exactly what usage asks), `DxTexture` (now `createTexture` with a native
  format code; only RGBA8 is ever CPU-uploaded; `hasNativeRenderTarget()`/
  `hasNativeDepthTarget()`), `DxTextureView` (`hasNativeRenderTargetView()`/
  `hasNativeDepthStencilView()`).
- `DxPipelineStateMapping` -- Blaze3D state -> descriptor, mirroring
  `VulkanRenderPipeline`/`VulkanConst` (depth test iff depth state present;
  bias only if both factors non-zero; blend iff `blendFunction()` present;
  QUADS/LINES/TRIANGLES -> triangle list; **TRIANGLE_FAN rejected**).
- `DxRenderPipeline` (`CompiledRenderPipeline`; valid = native handle, invalid =
  reason, cached either way), `DxPipelineCompiler` (never throws; chains 4.2
  source -> 4.3 SPIR-V -> 4.4 reflect + layout -> 4.5 HLSL -> native objects;
  the failure reason names the stage), `DxDeviceBackend.precompilePipeline`
  (`IdentityHashMap` keyed on the `RenderPipeline`, synchronized; logs one
  info line for the first valid pipeline and a warn per invalid one),
  `clearPipelineCache()`/`close()` release the native objects.

### 7.5 Approximations (deliberate, revisit with first draw)
- RGB8/RGB16 vertex formats widen to 4 components (the shader reads three;
  the extra component reads the next attribute's bytes; the very last vertex of
  a buffer may read past its end -- watch the debug layer).
- Constant-color blend factors collapse to D3D11's single blend factor (left at
  zero = Vulkan's default constants); alpha-slot `*_COLOR` -> `*_ALPHA`.
- Depth bias: Vulkan's float constant is `Math.round`ed into D3D11's integer
  `DepthBias` (float-depth-buffer semantics differ); `DepthBiasClamp` 0.
- Front face: **CCW** (GL convention) on the assumption our unflipped SPIR-V
  keeps GL's Y-up clip space, which D3D11 shares. Vulkan uses CW, which implies
  the Vulkan path flips Y somewhere (its `GlslCompiler`, not in the supplied
  decompile). **Commit 3 update: confirmed correct, kept as is -- see section
  9.2.** The `GlslCompiler`/`IntermediaryShaderModule` classes (now supplied)
  do not flip Y; Vulkan's CW is the paired front-face flip for a Y-negation
  that must live elsewhere (outside the supplied decompile), which D3D11 does
  not need because its NDC is Y-up like GL's, not like Vulkan's.
- Clip-space depth: D3D11 clips z to [0,w]. If Blaze3D's projection produces
  GL-style [-w,w], geometry in the near half is clipped; SPIRV-Cross has a
  `fixup_clipspace` HLSL option. **Commit 3 update: still open -- see section
  9.3.** The `GlslCompiler`/`IntermediaryShaderModule` classes do not remap
  depth either, so this still depends on code outside the supplied decompile;
  unlike the Y question, D3D11 and Vulkan *agree* here ([0,1] depth, unlike
  GL's [-1,1]), so if Blaze3D's shared projection math already targets [0,1]
  for every non-GL backend (plausible, unconfirmed) DirectExp needs nothing
  extra; recommend an empirical check at first draw (see 9.6) over guessing.

### 7.6 What runClient should show (commit 2)
- Existing lines unchanged, plus new once-only lines: `native GPU render/depth
  texture path active`, `... render target view path active`, `... depth-stencil
  view path active`, and `DirectExp: first native render pipeline assembled (<id>)`.
- **Expect many `DirectExp: pipeline <id> is invalid: ...` warnings.** This is
  the first time 4.2-4.5 run on vanilla shaders. The reason strings are the
  point of this commit's runtime check; collect them. Likely first failure:
  **GLSL->SPIR-V**, because vertex inputs need explicit locations = element
  index (Vulkan's `GlslCompiler.compile(..., getElementAttributeNames())` does
  this; ours does not -- `directexp_shader_toolchain.cpp` uses default shaderc
  options). See section 8.
- No crash, no debug-layer errors on `CreateTexture2D`/`Create*View`/
  `CreateInputLayout`.

## 8. Open items before commit 4 / needs owner input

1. ~~**Vertex-input locations (blocking real pipelines).**~~ **RESOLVED in
   commit 3** -- the decompiled `com.mojang.blaze3d.vulkan.glsl` package was
   supplied this session. See section 9 for the fix and the four questions
   it was asked to answer (attribute-name-to-location, Y-flip, clip-space
   depth, shaderc options). Two of the four are fully resolved from the
   decompile ([V-DECOMPILE]); the Y-flip question is resolved with high
   confidence via [V-WEB] facts plus what the decompile does *not* contain;
   clip-space depth remains genuinely open (9.3) pending either Blaze3D's
   shared projection-matrix source or an empirical check at first draw.
2. `TRIANGLE_FAN`: **confirmed 26.2 does issue it** -- 6 occurrences in the
   post-commit-3 `runClient` log (`sunrise_sunset` and similar sky
   pipelines), all `pipeline ... is invalid: ... TRIANGLE_FAN ...` from
   `DxPipelineStateMapping`'s rejection. Still unresolved; needs either a
   `TRIANGLE_FAN`->`TRIANGLE_LIST` index-buffer expansion (the usual D3D11
   fix, since D3D11 has no native fan topology) or confirmation from the
   owner that these specific pipelines are skippable for now.
3. Render-target readback: native color/depth textures become GPU-authoritative
   once drawn to; `DxTexture`'s CPU mirror goes stale (affects
   `copyTextureToBuffer`, screenshots). Needs a staging-texture readback path.
4. Commit 4 must also handle clears (`createRenderPass` clear color/depth ->
   `ClearRenderTargetView`/`ClearDepthStencilView`), viewport/scissor, and
   binding SRVs/samplers/constant-buffer staging per the 4.4 layout.
5. Clip-space depth (9.3) should be settled -- by finding Blaze3D's shared
   projection-matrix source, or by the empirical check in 9.6 -- before or
   during commit 4's first real draw, since a wrong answer here clips
   geometry rather than merely misplacing it.
6. **RESOLVED in the commit-3 follow-up (section 10):** every pipeline
   compiled to HLSL/bytecode was still failing `ID3D11Device::
   CreateVertexShader`, because `directexp_shader_hlsl.cpp` hardcoded Shader
   Model 5.0 regardless of the actually-negotiated feature level, and a
   10.1-floor device (this project's own advertised minimum) only
   guarantees Shader Model 4.1. Also resolved in the same follow-up: 14
   zero-vertex-format pipelines (blit-style, full-screen-quad shaders) were
   rejected by an over-strict `vertexStride must be positive` check that
   didn't account for a legitimately empty vertex format.
7. **New, from the post-commit-3 log, not yet fixed:** 2 occurrences of
   `HLSL emission/compilation failed for VERTEX shader ...: Unsupported
   builtin in HLSL: 1` for the `debug_points` pipeline (`GL_POINTS`
   topology, uses `gl_PointSize`). SPIR-V `BuiltIn PointSize` (decoration
   value 1, matching the "`: 1`" in SPIRV-Cross's own error text) has no
   direct HLSL equivalent SPIRV-Cross's HLSL backend will emit by default;
   likely needs either dropping/ignoring the point-size write for the HLSL
   path (D3D11 has no per-vertex point-sprite size without a geometry
   shader expanding points to quads) or a geometry-shader-based point-sprite
   expansion. Out of scope for the commit-3 follow-up; needs an owner
   decision on which approach fits DirectExp's point-rendering needs.
8. **Unrelated, noticed in the same `runClient` log commit 3 was analyzed
   against:** one `native GPU buffer upload failed; ... updateBufferNative:
   range is outside the buffer or the mirror.` warning (single occurrence,
   startup). Not investigated -- out of scope for both the interface-layout
   fix and the commit-3 follow-up -- but worth a look before or during
   commit 4 since it means one buffer's GPU copy is stale from startup.

## 9. Commit 3 -- shader interface layout (fixes every pipeline being invalid)

### 9.1 The bug and its fix

The `runClient` log commit 2 predicted (7.6) is exactly what the owner's
`debug.log`/`latest.log` showed: every one of the 86 distinct pipelines Blaze3D
tried to precompile came back `DirectExp: pipeline <id> is invalid: GLSL->SPIR-V:
VERTEX_COMPILE_FAILED` (174 total `compilation failed for` lines -- some
pipelines are compiled more than once). `shaderc`'s own messages, read from
the log, are exactly the three kinds Vulkan-semantics GLSL demands and plain
`#version 330` GLSL (Mojang's shaders) never supplies:

- `'location' : SPIR-V requires location for user input/output` (2244 lines)
- `'binding' : uniform/buffer blocks require layout(binding=X)` (968 lines)
- `'binding' : sampler/texture/image requires layout(binding=X)` (180 lines)

plus, on the full-screen-quad shaders, `'gl_VertexID' : undeclared identifier`
(Vulkan-semantics GLSL exposes `gl_VertexIndex`, not GL's `gl_VertexID`).

`native/src/directexp_shader_toolchain.cpp`'s `CompileGlslToSpirv` calls
`compiler.CompileGlslToSpv` with a bare, default-constructed
`shaderc::CompileOptions` -- no target env, no auto-map/auto-bind, nothing.
Every vertex/fragment interface variable and every uniform block/sampler
Mojang's GLSL declares is therefore missing the location/binding shaderc's
Vulkan-semantics front end (correctly) insists on.

The fix is a new stage, `DxShaderInterfaceLayout`
(`src/client/java/.../client/shader/DxShaderInterfaceLayout.java`), spliced
into `DxPipelineCompiler.compile` between the existing 4.2 source stage and
4.3 IR stage: it rewrites the already-preprocessed GLSL text, inserting an
explicit `layout(location = N)` or `layout(binding = N)` on every vertex
input, stage output/input (varying), uniform block, and sampler, following
the exact numbering rules the decompiled `com.mojang.blaze3d.vulkan.glsl`
package uses (9.2 below), plus the two `#extension` lines Vulkan-semantics
GLSL needs before it will accept those layouts under `#version 330` and the
same `gl_VertexID`/`gl_InstanceID` defines Vulkan's `GlslCompiler` injects.
It is a small hand-written tokenizer (comment/preprocessor-aware, brace-depth
tracking), not a full GLSL parser -- see its class javadoc for the exact
scope and the shapes it deliberately refuses (arrays, comma-declarator lists,
an already-explicit `location`/`binding`) rather than guess at. It does not
patch SPIR-V the way Vulkan's `IntermediaryShaderModule.rebind` does (that
requires already-compiled SPIR-V and a native call this stage runs before);
inserting the layout into the GLSL text first, so `shaderc` assigns the
number itself, produces the same outcome without needing a second native
round trip. `DxPipelineCompiler` now returns an invalid pipeline naming
`"shader interface: ..."` if a shader's shape isn't one this stage can
number, the same "reason names the stage" contract every other stage in the
chain already follows.

`directexp_shader_toolchain.cpp`'s bare `shaderc::CompileOptions` also
picked up one real change: `options.SetGenerateDebugInfo()`, matching
`GlslCompiler`'s own `shaderc_compile_options_set_generate_debug_info` (9.4).
Without it, a SPIR-V module with debug info stripped loses `OpName`s, and
both the 4.4 reflection stage and 4.5's `add_hlsl_resource_binding` lookup
resources by name (`SpirvResourceName`, falling back to a synthesized
`spirv_resource_<id>` only when a name is genuinely absent) -- so stripped
debug info would have (re-)broken 4.4/4.5 the moment 4.3 started succeeding.

### 9.2 Question (a): how attribute names become locations [V-DECOMPILE]

Fully answered by `GlslCompiler`/`IntermediaryShaderModule`
(`com.mojang.blaze3d.vulkan.glsl`, 26.2 snapshot 1). Two passes, both after
an initial unlocated `shaderc` compile (shaderc's own `auto_map_locations`/
`auto_bind_uniforms` assign *some* number to everything so the compile
succeeds; then Vulkan overwrites those numbers by patching the SPIR-V words
directly, at the exact byte offset `spvc_compiler_get_binary_offset_for_decoration`
reports for each resource's `Location`/`Binding` decoration):

- **At SPIR-V creation** (`IntermediaryShaderModule.createFromSpirv`): every
  reflected *output* variable is renumbered `0, 1, 2, ...` in SPIRV-Cross's
  own resource-list order (a fixed loop over `outputs`, `put(location, i)`).
  Inputs are not touched at this point.
- **At link time** (`GlslCompiler.compile`, then `IntermediaryShaderModule.rebind`):
  - **Vertex inputs**: `rebind(vertexInputs, ...)` where `vertexInputs` is
    `pipeline.getVertexFormat().getElementAttributeNames()`
    (`VulkanDevice.precompilePipeline`'s call site, confirmed at
    `VulkanDevice.class:283`) -- location = the attribute's index in that
    list. An attribute the shader declares but the vertex format never
    supplies throws `ShaderCompileException` (`remainingInputs` check); this
    project's `DxShaderInterfaceLayout` is more lenient here (assigns an
    unused location and logs a note) since being wrong about an unused
    corner case is recoverable and a hard failure on every such pipeline is
    not, per this project's existing "log and continue" stance on
    declaration/reflection mismatches (`DxResourceLayoutBuilder`'s own
    javadoc makes the identical tradeoff for uniforms/samplers).
  - **Varyings**: the fragment stage's `rebind` call passes
    `vertexOutputNames` (the vertex stage's `outputs()`, in the same
    resource-list order used to number them `0..N-1` above) as the "input
    variables" list for the *fragment* stage -- so a fragment input's
    location becomes the position of its name in that list, i.e. it is
    matched to the vertex output of the same name, not by declaration order.
    Fragment *outputs* are never touched by `rebind` at all; they keep
    whatever `shaderc`'s own `auto_map_locations` assigned.
  - **Uniform blocks and samplers** (including UTBs, texel buffers) all
    share one binding space (Vulkan descriptor set 0): `GlslCompiler.compile`
    builds one `List<Entry>` via `addToBindGroup(vertex)` then
    `addToBindGroup(fragment)`, appending each newly-seen `(kind, name)`
    once (`entries.stream().noneMatch(...)` guards against duplicates from a
    resource used in both stages); `rebind` then sets each resource's
    binding to its index in that list, per stage.

  `DxShaderInterfaceLayout.assign` reproduces every one of these rules
  (see its tests, `DxShaderInterfaceLayoutTest`), with one acknowledged,
  harmless difference: it numbers varyings and merges the binding space by
  *textual* declaration order (source order) rather than SPIR-V
  reflection order, because the assignment has to happen before compiling,
  not after. This cannot produce a wrong result: every number is looked up
  by resource *name* on both sides of the vertex/fragment boundary (this
  project's own reflection/HLSL-binding stages are name-keyed too, per
  `directexp_shader_resource_naming.h`'s own rationale for existing), so two
  different numbering schemes that both number consistently are
  observationally identical to everything downstream.

### 9.3 Question (b) and (c): Y-flip and clip-space depth remap

**Y-flip: not in the decompiled `glsl` package [V-DECOMPILE], and not needed
for D3D11 either [V-WEB].** `GlslCompiler`/`IntermediaryShaderModule`'s only
SPIR-V patching is the `Location`/`Binding` word overwrites in 9.2 above --
neither class reads or writes anything related to `gl_Position` or a
`BuiltIn Position` decoration. `VulkanRenderPass`'s viewport is also a plain,
unflipped one (`viewport.y(0.0F)`, `viewport.height(this.outputHeight)` --
positive, not the negative-height flip trick `VK_KHR_maintenance1`/core 1.1
allows). So if Vulkan's on-screen image is right-side up (it presumably is,
being shipped), whatever compensates for Vulkan's own Y convention is not in
any of the 20 files `vulkan.zip` supplied (the full `com.mojang.blaze3d.vulkan`
and `.glsl` packages) -- most likely a backend-conditional term in Blaze3D's
shared (non-Vulkan-specific) projection-matrix construction, which is outside
both supplied decompile packages.

That compensation, wherever it lives, is a **Vulkan-specific** fix, not a
GLSL/SPIR-V-compilation one -- and D3D11 does not need it. Confirmed against
multiple independent, real-world sources (this is genuinely easy to get
backwards, so it was checked rather than assumed):
- D3D11's own transform-pipeline docs and community references agree D3D's
  NDC is **+Y up**, matching OpenGL/GLSL's own convention, with the
  top-left-origin viewport transform (which every rasterization API needs
  regardless of NDC convention) applying the flip from NDC to *screen*
  coordinates automatically and identically to how GL's does.
- Khronos's own community documentation states Vulkan's NDC is **+Y down**,
  explicitly *unlike* OpenGL -- a deliberate Vulkan-specific design choice,
  not something D3D shares.
- Veldrid (a real, shipping multi-backend D3D11/Vulkan/Metal/OpenGL graphics
  library) documents this exact pairing directly, recommending exactly one
  line at the end of *only the Vulkan* vertex shader: `gl_Position.y =
  -gl_Position.y; // Correct for Vulkan clip coordinates` -- i.e. the
  flip belongs on Vulkan's side of a D3D11/Vulkan pair, not D3D11's.

This also explains `VulkanRenderPipeline`'s `frontFace(1)`
(`VK_FRONT_FACE_CLOCKWISE`, vs. GL's default CCW): negating Y mirrors every
triangle's apparent winding, so whatever applies Vulkan's Y compensation
must also flip the front-face declaration to keep culling the same triangles
-- Vulkan's CW is that paired flip, not an independent, arbitrary choice.
Since D3D11 needs no Y compensation, it needs no paired front-face flip
either: `directexp_bridge_pipeline.cpp`'s `raster.FrontCounterClockwise =
TRUE` (GL's CCW convention) was already correct and is left unchanged this
commit, now with a comment recording *why* instead of an open question.

**Clip-space depth: still open.** The same two classes never touch a depth
decoration either, so this is symmetric evidence to the Y question -- but
the *conclusion* does not carry over, because D3D11 and Vulkan *agree* on
depth range ([0,1]) where GL is the outlier ([-1,1]), the reverse of the Y
situation. This means, unlike Y: **if** Blaze3D's shared projection-matrix
code already emits [0,1] depth uniformly for every non-GL backend (a common
technique -- e.g. via `glClipControl(GL_ZERO_TO_ONE)` on the GL side so every
backend's matrix math agrees, avoiding a per-backend matrix or a shader-side
remap entirely), DirectExp needs nothing extra, matching Vulkan. But nothing
in either supplied decompile package proves this either way; genuinely
requires either Blaze3D's shared (non-backend-specific) projection-matrix
source, or the empirical check in 9.6. Left as `fixup_clipspace`
*not* set in `EmitHlsl` (`directexp_shader_hlsl.cpp`) for now -- the
currently-shipped default -- rather than guessing in either direction.

### 9.4 Question (d): which shaderc options `GlslCompiler` sets [V-DECOMPILE]

Read directly from `GlslCompiler`'s constructor:

| Option | Value |
| --- | --- |
| `shaderc_compile_options_set_target_env` | `(shaderc_target_env_vulkan, 4202496)` -- `4202496` decodes to `shaderc_env_version_vulkan_1_2` (`(1<<22) + (2<<12)`) |
| `shaderc_compile_options_set_auto_bind_uniforms` | `true` |
| `shaderc_compile_options_set_auto_map_locations` | `true` |
| `shaderc_compile_options_set_generate_debug_info` | set (no argument in this shaderc version; just called) |
| `shaderc_compile_options_set_optimization_level` | `0` (`shaderc_optimization_level_zero`) |

Plus, not a `CompileOptions` setting but applied to every shader's source
text before compilation either way: `ShaderDefines.builder().define("gl_VertexID",
"gl_VertexIndex").define("gl_InstanceID", "gl_InstanceIndex").build()`,
injected via the same `GlslPreprocessor.injectDefines` call this project's
own 4.2 `DxShaderSourceStage` already uses.

DirectExp does not need to replicate `target_env`/`auto_map_locations`/
`auto_bind_uniforms`: those exist so *unmodified* GL-style GLSL compiles at
all under Vulkan semantics, which this project's new interface-layout stage
(9.1) achieves a different way (explicit layouts inserted into the text
itself, so the numbers are known and controlled up front rather than
auto-assigned and then patched after the fact in SPIR-V). What *is* now
replicated: `SetGenerateDebugInfo()` (9.1's native change -- required for
name-based reflection/HLSL-binding lookups to keep working) and the
`gl_VertexID`/`gl_InstanceID` defines (in `DxShaderInterfaceLayout`'s
inserted header, verbatim). Optimization level and target-env version are
not relevant to a GLSL-in-HLSL-out project with no direct SPIR-V execution.

### 9.5 Verification actually done (Linux sandbox, no `shaderc`/Gradle available)

- `DxShaderInterfaceLayout`/`DxShaderInterfaceException` compile under a real
  `javac` 21 against the real `DxPreprocessedShaderSource`/
  `DxPreprocessedShaderPipelineSource` records and small Minecraft stubs;
  `DxShaderInterfaceLayoutTest`'s 16 cases pass under the same hand-written
  runner earlier commits used (vertex-input/varying/binding numbering rules,
  the vanilla-shaped GUI/textured/particle/full-screen-quad/flat-int-varying
  samples below, comment/`#ifdef`/function-body/struct handling, all three
  refusal cases).
- Independently, five samples shaped like real vanilla pipelines (GUI-style
  two-UBO/no-sampler, textured-with-`#ifdef`, particle-style with a fog UBO
  + `ivec2` texel-fetch sampler + declaration order reversed between stages,
  a full-screen-quad blit shader using `gl_VertexID` with an empty vertex
  format, and a `flat`-qualified `int`/`samplerBuffer` case) were run through
  `DxShaderInterfaceLayout.assign`, then the *rewritten* GLSL was fed to a
  real `glslangValidator 15.1.0 --target-env vulkan1.0`: all ten stages
  (5 pipelines x 2 stages) compiled to valid SPIR-V with **zero** errors,
  where the *original* (un-rewritten) text failed with exactly the errors
  the owner's log showed. `spirv-dis`/`spirv-cross --hlsl --shader-model 50`
  on the resulting modules confirmed (a) `Location`/`Binding` decorations by
  name match the rules in 9.2 exactly (e.g. the particle sample's vertex
  stage: `Position`=0, `UV0`=1, `Color`=2, `UV2`=3 -- its vertex format
  order; its `Fog`/`DynamicTransforms`/`Projection` UBOs and `Sampler2` get
  bindings 0-3 in vertex-then-fragment declaration order, and the fragment
  stage's own `Sampler0` correctly continues from 4), and (b) SPIRV-Cross's
  emitted HLSL gives a vertex-output/fragment-input pair the *same*
  `TEXCOORD<n>` semantic whenever `DxShaderInterfaceLayout` gave them the
  same location, including when the two stages declared the varying in a
  different order or with a `flat`/`nointerpolation` qualifier.
- What this does **not** verify: a real `shaderc` compile of the rewritten
  text (this sandbox has no `shaderc`, only `glslangValidator`, a different
  but Khronos-conformant GLSL/Vulkan front end -- both are expected to
  accept the same explicit-layout GLSL, but only an actual `shaderc` run on
  Windows confirms it), and nothing past IR (reflection, HLSL emission,
  `D3DCompile`, an actual draw) was exercised against real vanilla shader
  source, since none was supplied.

### 9.6 Recommended next check (commit 4 or sooner)

Once `runClient` reaches a real draw: render one asymmetric, distinctly-colored
triangle and check it is (a) not mirrored top-to-bottom (confirms 9.3's Y
conclusion empirically, not just by citation) and (b) not culled (confirms
`FrontCounterClockwise = TRUE` is still right once real vertex data is
flowing, not just plausible from decompiled Vulkan state). If geometry in the
near depth range disappears or z-fighting looks inverted, that is the signal
to revisit 9.3's still-open clip-space-depth question -- try SPIRV-Cross's
`fixup_clipspace` HLSL option in `EmitHlsl` before suspecting anything else.

## 10. Commit 3 follow-up -- feature-level/shader-model mismatch and the vertexStride check

The owner ran commit 3 through `runClient` and reported "looks like
preprocessing is not working," attaching a fresh `debug.log`/`latest.log`.
It wasn't -- the log itself proves commit 3 worked: none of the three
`shaderc` location/binding error kinds from section 9.1 appear anywhere, and
`DxShaderInterfaceLayout`'s own debug line appears exactly where expected,
e.g. `Shader interface note for pipeline minecraft:pipeline/gui_text_grayscale:
vertex input 'UV2' is not in the vertex format; assigned unused location 3`.
Every one of the 174 `pipeline ... is invalid` lines in this new log is a
*different* failure than before, all past the GLSL->SPIR-V stage commit 3
fixed:

| Failure | Count | Cause |
| --- | --- | --- |
| `ID3D11Device::CreateVertexShader failed.` | 148 | 10.1 feature-level floor vs. hardcoded Shader Model 5.0 (10.1) below |
| `vertexStride must be positive.` | 14 | zero-vertex-format pipelines rejected (10.2 below) |
| `TRIANGLE_FAN` rejected | 6 | pre-existing, still-open gap (section 8 item 2) |
| `Unsupported builtin in HLSL: 1` | 2 | `gl_PointSize`/HLSL, still-open gap (section 8 item 7) |

This is expected, healthy progress, not a regression: commit 3 was never
exercised end-to-end before (per its own status-table entry, `shaderc` was
never actually run against its output), so this is the *first* log to reach
`D3DCompile`/`CreateVertexShader`/`DxPipelineStateDescriptor` at all. 150 of
the 174 (86%) are now fixed by this follow-up.

### 10.1 The main bug: Shader Model 5.0 hardcoded regardless of feature level

`directexp_shader_hlsl.cpp` had two hardcoded Shader-Model-5.0 choices:
`ToD3DCompileTarget` always returning `vs_5_0`/`ps_5_0`, and `EmitHlsl`
always setting `spirv_cross::CompilerHLSL::Options::shader_model = 50`. The
4.1-era comment on `ToD3DCompileTarget` claimed this "match[ed] the
feature-level-10.1 floor" -- it does not. Confirmed against Microsoft Learn's
`D3D_FEATURE_LEVEL` enumeration page [V-MSDOC]: `D3D_FEATURE_LEVEL_10_1`'s
entry states its target is "shader model 4"; `D3D_FEATURE_LEVEL_11_0`'s
entry states "shader model 5". `directexp_common.cpp`'s
`kRequestedFeatureLevels` (`{11.1, 11.0, 10.1}`) and `kMinimumFeatureLevel`
(10.1) mean a machine whose GPU/driver tops out at 10.1 -- common for older
GPUs, some VMs, and RDP/remote sessions -- is accepted as a valid device by
DirectExp's own design, but then handed Shader-Model-5.0 bytecode it cannot
create shader objects from. `D3DCompile` itself doesn't consult the runtime
device at all (it's a pure offline compiler), so it produced valid SM5
bytecode without complaint; only `CreateVertexShader`/`CreatePixelShader`
-- which do check the device's capability -- rejected it, and did so for
every pipeline uniformly, matching the log's pattern exactly (failure
unrelated to any individual shader's content, since a capability gate check
doesn't care what's inside the bytecode).

**Fix:** the real device's negotiated feature level, previously computed
locally in `BootstrapSurface` and then discarded, is now kept in a new
global, `g_activeFeatureLevel` (`directexp_common.h`/`.cpp`), set alongside
`g_activeDevice`/`g_activeContext` at the one site that assigns them
(`directexp_bridge_surface.cpp`'s `BootstrapSurface`) and cleared alongside
them at the one site that releases them. `ToD3DCompileTarget` and
`EmitHlsl` both now branch on `g_activeFeatureLevel >= D3D_FEATURE_LEVEL_11_0`
to choose `vs_5_0`/`ps_5_0`/`shader_model=50` or `vs_4_1`/`ps_4_1`/
`shader_model=41` -- the two must move together, since a target-profile/
shader-model-number mismatch would be its own bug. `kRequestedFeatureLevels`
never requests anything below 10.1, so this is the only branch needed (SM4.1
covers everything DirectExp's D3D11 floor promises to support; SM4.0-only
`D3D_FEATURE_LEVEL_10_0` is never negotiated).

**Verification:** the branch logic (not the whole file, which needs
`shaderc`/SPIRV-Cross headers this sandbox doesn't have) was extracted into
a standalone program, compiled with the real `x86_64-w64-mingw32-g++` and
real mingw-w64 `d3d11.h` (so the actual `D3D_FEATURE_LEVEL_10_1`/`_11_0`/
`_11_1` enum values were exercised, not guessed at), statically linked, and
run under Wine: all four cases (10.1 -> SM4.1, 11.0 -> SM5.0, 11.1 -> SM5.0,
no active device -> SM4.1, i.e. the safe default that doesn't over-claim
SM5) asserted correctly.

**Not verified:** the actual edited files
(`directexp_shader_hlsl.cpp`/`directexp_bridge_surface.cpp`/
`directexp_common.*`) were reviewed by hand, not run through a compiler --
this sandbox has no real `jni.h`/`shaderc`/SPIRV-Cross headers to build them
against (a hand-written partial `jni.h` stub was enough for the isolated
logic test above, not for `directexp_bridge_surface.cpp`'s actual JNI
exports). A real Windows build/`runClient` is needed to confirm the diff
compiles and that a 10.1-floor machine's pipelines now reach
`CreateVertexShader` successfully.

**Suggested follow-up, not done here:** the log line at
`DxDeviceBackend.java:61` (`"Direct3D 11 (feature level 10.1 floor)"`) is a
hardcoded string, not a report of the *actual* negotiated level -- it would
have said exactly this even on an 11.1 device, and it's what made this bug
harder to diagnose from the log alone. `DxBridgeProbe.featureLevel()` (from
`dxBridge.probe()`, already called during `DxBackendBootstrapContext.
bootstrap` before the real surface bootstrap, against the same adapter and
the same `kRequestedFeatureLevels` list) already carries this information on
the Java side and is currently unused after the `meetsFeatureLevelFloor()`
check -- logging it there would have made this bug visible from the log in
one line instead of needing this whole investigation.

### 10.2 The smaller bug: vertexStride rejected for zero-attribute pipelines

`DxPipelineStateDescriptor`'s compact constructor threw
`IllegalArgumentException("vertexStride must be positive.")` whenever
`vertexStride <= 0`, unconditionally. A full-screen-quad/blit-style pipeline
-- vanilla has several: `blit`, `flat_clouds`, `animate_sprite_blit`,
`lightmap`, `entity_outline_blit`, `tracy_blit` -- generates its vertex
positions from `gl_VertexIndex`/`gl_VertexID` (see section 9.4's note on
Vulkan's injected defines) and binds no vertex buffer at all, so its
`VertexFormat` is legitimately empty: `getVertexSize()` is 0, and
`DxPipelineStateMapping`'s `formats`/`offsets` arrays are both empty too.
Zero attributes with a zero stride is the *correct* representation of "no
vertex buffer," not a malformed pipeline -- the existing native pipeline
path already handles zero input elements correctly (no input layout
created, per section 7.3), so this was purely an overly strict Java-side
guard rejecting a case the rest of the pipeline correctly supports.

**Fix:** the check now only requires `vertexStride > 0` when
`attributeFormats.length > 0` (i.e. only when there's an actual attribute to
stride through); a negative stride is still rejected unconditionally (that's
never valid, attributes or not). 5 new unit tests
(`DxPipelineStateDescriptorTest`) cover all four combinations plus the
existing mismatched-array-length check; all pass under `javac` 21.

**Forward note for whoever implements commit 4's draw path:** a pipeline
with `vertexStride == 0`/no attributes must not have `IASetVertexBuffers`
called for it at draw time (there's no buffer to bind) -- not yet relevant
since `draw()` is still a no-op, but worth remembering when it stops being
one.
