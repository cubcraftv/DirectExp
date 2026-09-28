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
**[V-MSDOC]** verified against Microsoft Learn; **[DECIDED]** a project
decision; **[OPEN]** needs a decision before the stated commit;
**[UNVERIFIED]** authored, not built/run.

---

## 1. Status

| Item | State |
| --- | --- |
| Milestone 4.5 (HLSL emission + D3DCompile) | Built on Windows/MinGW-w64, `runClient` clean (reported by project owner, 2026-09-28) |
| 4.6 commit 1 (GPU buffer / sampler / SRV backing) | **Authored** (this session). Java compiles against stubs, 19 tests pass under a stand-in runner, native files pass `-fsyntax-only` under `x86_64-w64-mingw32-g++`. **Not** built via Gradle, **not** run in `runClient`. |
| 4.6 commits 2-4 | Not started |

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
- **[OPEN, needed before commit 3]** How a uniform-buffer *slice*
  (`GpuBufferSlice` offset+length into a large buffer) reaches a D3D11
  constant-buffer slot. Options: (A) per-slot staging constant buffer,
  `UpdateSubresource` full copy of the slice at bind time -- works on plain
  D3D11.0, extra copy per bind; (B) D3D11.1 `*SetConstantBuffers1` ranged
  binding on the big buffer -- no copy, needs the 11.1 runtime (Win8+) and
  driver support, and offsets must be multiples of 16 constants = 256 bytes
  (which matches `DEFAULT_LIMITS`' 256 alignment in `DxDeviceBackend`).
  **Recommendation: A first** (fewest assumptions), B later as an
  optimisation. Commit 1 is neutral: uniform buffers <= 64 KiB get a mirror,
  larger ones stay CPU-only.
- **[OPEN, needed before commit 3]** Render/depth targets. `Texture` today is
  created with `D3D11_BIND_SHADER_RESOURCE` only -- no `D3D11_BIND_RENDER_TARGET`,
  no RTV, and no depth/stencil texture support at all (`createNativeTextureHandle`
  accepts only RGBA8_UNORM, single layer). `OMSetRenderTargets` needs RTVs
  (and a DSV for depth). Proposed: commit 2 widens native textures (add
  `RENDER_TARGET` for color textures whose Blaze3D usage says render
  attachment; typeless depth formats with separate DSV/SRV formats for
  `D32_FLOAT` etc.) and adds RTV/DSV creation. Needs owner sign-off on scope.

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
2. **Pipeline object assembly** -- new `DxRenderPipeline` (VS/PS,
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

### 6.5 What runClient should show (verification checklist)
- Log lines, once each: `native GPU buffer path active`, `native GPU sampler
  path active`, `native GPU texture view path active`. A `fallback` line means
  a native call threw -- read its reason.
- No `... upload failed; GPU copy may be stale` line.
- Behaviour otherwise unchanged (still a black fill; nothing binds these yet).
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
