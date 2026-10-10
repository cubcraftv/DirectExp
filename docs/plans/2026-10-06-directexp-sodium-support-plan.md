# DirectExp + Sodium: Support Plan and Fork Plan

**Created:** 2026-10-06. **Status:** Phases 0-2 authored; Phase 5 implementation started
2026-10-09 (runtime profiling and Windows native build pending). **Basis:** static reading of
`DirectExp.zip` (re-verified against source, not docs; `patch9.patch` is already applied in this snapshot) and `sodium-26.2-stable.zip`. Nothing here was
compiled or run; every "Sodium does X" / "DirectExp lacks Y" claim is from source, with file
names so it can be re-checked.

---

## 1. Verdict

Sodium 26.2 is a much easier target than older Sodium. It no longer talks to raw OpenGL for
terrain. It renders through Blaze3D's `RenderPass`, and it already has a backend seam
(`DrawBackend` -> `DrawContext` / `MultiDrawBatch`) that it uses to support Vulkan. A DirectX
path is "a third Vulkan-shaped branch", not a rewrite.

The hard part is **not Sodium**. It is that DirectExp's resource and shader layers are not yet
capable enough for Sodium's workload (see section 4). Realistic order: finish DirectExp 4.7,
close the capability gaps in DirectExp, then add a small Sodium patch.

## 2. Decisions needed before any work

1. **Sodium's license is PolyForm Shield 1.0.0** (`LICENSE.md`), not LGPL. Its Noncompete
   clause bars "providing any product that competes with the software". It does not forbid
   modifying Sodium. A public *fork* redistributes Sodium itself with a feature added, which is the
   riskiest shape. A separate **add-on mod** that ships none of Sodium's code, requires the user to
   install real Sodium, and patches it at run time with Mixin is a much weaker "competes" case,
   because it extends Sodium instead of substituting for it. That is still not a guarantee: the
   license says plugins can compete with libraries, and "practical substitute" marketing makes it
   competing.
   **Chosen direction: add-on, not fork (see section 5b).**
2. **`AGENTS.md` lists "broad renderer-mod compatibility as an early priority" as a non-goal.**
   Sodium support is exactly that. Milestone 4.7 is visually accepted.
3. **Add-on, not fork.** Keep the Sodium-facing surface small and backend-neutral so it could
   also be offered upstream later. Never copy Sodium source or resources into the add-on.

## 3. What the code shows

**Sodium (26.2-stable)**
- `gpu/device/backend/DrawBackend.java`: picks `VK_MULTIDRAW`, `VK_INDIRECT`, or falls through
  to `OPENGL`. A DirectX device would wrongly land on `OPENGL`.
- `DrawContext.create()` and `MultiDrawBatch.newBatch()` switch on that enum.
- Terrain draw (`DefaultChunkRenderer`): per region it calls `drawContext.updateData(...)`
  (region offset, time, region id) then `batch.draw(...)`. Uniforms/textures bound once:
  `u_Globals` (UBO), `u_SectionTimeInfo` (**texel buffer**, `R32_SINT`), `u_LightTex`, `u_BlockTex`.
- Vertex format `CompactChunkVertex`: `RG32_UINT`, `RGBA8_UNORM`, `RG16_UINT`, `RGBA8_UINT`
  (integer attributes), 20-byte stride, topology `QUADS` with a shared 32-bit index buffer.
- Shader `block_layer_opaque.vsh`: per-region values are `layout(push_constant)` under
  `#ifdef VULKAN`, otherwise loose `uniform`s (GL). Neither works as-is for D3D11.
- GL-only code is already guarded by `backendName().contains("OpenGL")`
  (`GlSurfaceMixin`, `SodiumConfigBuilder`). DirectExp's backend name is
  `"Direct3D 11 (feature level 10.1 floor)"`, so those checks are safely skipped.
- `MappedStagingBuffer.flush()` and `MojangStagingBuffer`/`UniformBufferManager` branch on
  `DrawBackend` and `features().persistentMapping()`.
- Mixins on `GlDevice` / `GlSurface` / `GlStateManager` / `VulkanRenderPipeline` target classes
  that still exist on a DirectX run, so they load but their code paths never execute.

**DirectExp (current dev)**
- The initial audit found `DxRenderPassBackend.multiDrawIndexed(IntBuffer, ...)` implemented as a
  loop over `drawIndexed`, reading `(firstIndex, indexCount, baseVertex)` per draw (the same layout
  as Vulkan's `VkMultiDrawIndexedInfoEXT`). Phase 5 now sends that interleaved form through one
  JNI entry; the separate-parameter `PointerBuffer` variant and `drawIndexedIndirect` remain
  unsupported.
- `DeviceFeatures` enables only `multiDrawDirectInterleaved` (`DxDeviceBackend`); Sodium receives
  no shader-draw-parameters, indirect-draw, or persistent-mapping flags and keeps
  `MojangStagingBuffer` for uploads.
- `DxBuffer` now has a GPU-only policy for eligible large vertex/index buffers; smaller and
  special-purpose buffers remain mirrored. `copyToBuffer` uses native buffer copies when possible
  and preserves a CPU fallback for unsupported combinations.
- Typed texel-buffer SRVs now have format mapping, native creation/lifetime management, caching,
  and render-pass resource resolution.
- (Pre-Phase-2 state; the missing `VULKAN` define was never needed, see Option B) No push-constant or loose-uniform handling, and no `VULKAN` define in the shader stage
  (`DxShaderInterfaceLayout` only injects the `gl_VertexID`/`gl_InstanceID` defines). Re-checked:
  zero hits for push constants / root constants in `src` and `native/src`.
- The G1-G3 paths are implemented in source; their behavior on a Windows D3D11 device is not yet
  verified. See the 2026-10-10 progress entry for test/build evidence and remaining checks.
- `firstInstance != 0` and instanced non-indexed draws are rejected (Sodium does not need them).

## 4. Capability gaps in DirectExp (must close first)

| # | Gap | Why Sodium needs it | Fix |
|---|-----|---------------------|-----|
| G1 | CPU-mirrored, eagerly-synced buffers | Sodium arenas are very large vertex/index buffers updated by GPU-side copies. Mirroring doubles RAM and makes every upload a second copy. | **Source and Windows build verified 2026-10-10:** GPU-only storage for eligible large vertex/index buffers; D3D11 runtime validation pending. |
| G2 | `copyToBuffer` via CPU | Arena defragmentation and staging uploads are GPU->GPU copies. | **Source and Windows build verified 2026-10-10:** native D3D11 buffer copies with overlap handling; runtime validation pending. |
| G3 | Texel buffers | `u_SectionTimeInfo` (only used when fog/fade is enabled, but the binding is declared always). | **Source and Windows build verified 2026-10-10:** typed-buffer SRV formats, native creation/lifetime, and render-pass resolution; runtime validation pending. |
| G4 | Per-region values (push constants) | `u_RegionOffset`, `u_CurrentTime`, `u_RegionID` change per region. | See section 5, decision A vs B. **Status 2026-10-10: Java translation tested; `-Ddirectexp.debug.pushconstants=true` trace added, runtime check pending.** |
| G5 | Integer vertex attributes | `RG32_UINT`, `RG16_UINT`, `RGBA8_UINT` inputs. | **Source/test implemented 2026-10-10:** `DxPipelineStateMapping` maps them to DXGI 17, 36, 30 (R32G32/R16G16/R8G8B8A8_UINT); the Sodium vertex-format test pins the mapping. |
| G6 | `QUADS` + 32-bit shared index buffer | Sodium supplies its own index buffer; `DxPipelineStateMapping` already treats `QUADS` as pre-expanded triangle list. | Verify with a real Sodium draw; no new code expected. **Status 2026-10-10: `-Ddirectexp.debug.indexdraws=true` validator added, real Sodium draw pending.** |
| G7 | Throughput of the multi-draw loop | Hundreds of draws per region, thousands per frame, each crossing JNI. | **Implemented 2026-10-09:** interleaved native `multiDrawIndexed(IntBuffer, ...)` issues the batch through one JNI entry; runtime profiling pending. |
| G8 | Timestamp/fence stubs | Sodium's staging path uses fences. | `DxFence` currently stubbed; fine for the Mojang staging path, revisit if we later enable the mapped staging path. |

## 5. Design decisions

### A vs B for per-region values (G4)

- **Option A: change Sodium's shader and bind a small UBO.** Add a third branch to
  `block_layer_opaque.vsh` (e.g. `#elif defined(DIRECTX)` with `layout(std140) uniform u_RegionData {...}`),
  declare it in `ShaderChunkRenderer.BIND_GROUP`, and have `DXDrawContext.updateData` write 32
  bytes and `pass.setUniform("u_RegionData", slice)`. No changes to DirectExp's shader stage.
  Larger Sodium diff, but all of it is explicit Java/GLSL.
- **Option B: make DirectExp understand push constants.** Sodium's shader already has the
  `#ifdef VULKAN` push-constant block, and **`VULKAN` needs no DirectExp define: shaderc/glslang
  predefines it for Vulkan semantics** (verified in Phase 2 with glslang 15.1.0; an earlier
  revision of this plan wrongly said DirectExp would have to define it). As implemented in Phase 2,
  DirectExp rewrites the block at the GLSL level into a numbered `std140` uniform block named
  `DxPushConstants` (`DxShaderInterfaceLayout`), so it reflects and binds as an ordinary constant
  buffer with no native root-constant code, and fills it per draw from bytes supplied through the
  public `DxPushConstants` interface. Smallest Sodium diff (shader unchanged).

**Decision selected: Option B.** DirectExp owns the push-constant path and exposes it to Sodium's
existing Vulkan-style GLSL, while the Sodium add-on remains a small compatibility shim instead of a
shader override or fork. This keeps the Sodium patch surface minimal and matches the `VULKAN`
fast-path already used elsewhere in DirectExp.

**Recommendation: Option B, now firm for the add-on route.** Option A requires overriding Sodium's
shader file and `BIND_GROUP`, which means copying Sodium's shader text into the add-on and
mixing into a static field. Option B touches neither.

### Detecting DirectExp from Sodium

Sodium compiles only against Minecraft, so it cannot reference `DxDeviceBackend`. Detect by
`GpuDeviceAccessor.sodium$getBackend().getClass().getName()` or by `backendName()` starting with
`"Direct3D 11"`, and add `DrawBackend.D3D11`. Do not add a compile-time dependency on DirectExp.

## 6. Phases

### Phase 0: Gate (no code)
- Resolve decisions 2.1 to 2.3.
- Finish Milestone 4.7 (clear color, button backgrounds, panorama) and the open
  "items stop rendering after a while" report. Sodium issues far more draws and buffer traffic than
  vanilla UI, so any latent lifetime bug will surface harder.
- **Exit:** vanilla world entry on DirectX is stable for a sustained session.

### Phase 1: DirectExp resource core (G1, G2, G3, G5)
- GPU-only buffers, native buffer copy, texel-buffer SRVs, integer input layout tests.
- This phase is now selected under Option B, with the DirectExp resource layer carrying the
  buffer/SRV pieces that Sodium's per-region draw path requires before the add-on itself lands.
- Unit tests alongside the existing `DxBuffer*Test` / `DxPipelineStateMappingTest` style.
- **Exit:** a synthetic test pipeline that reads a texel buffer and draws from a GPU-only vertex
  buffer renders correctly under RenderDoc.

### Phase 2: Per-region values (G4) and Sodium shader compile

**Status (2026-10-07): implemented; Java layer unit-tested, shader chain checked offline,
Windows build and in-game run pending.** See the progress log entry of the same date for evidence
and for what is explicitly *not* verified.

- Implemented under Option B: `push_constant` blocks are rewritten to a numbered `std140` block
  named `DxPushConstants` (`DxShaderInterfaceLayout.PUSH_CONSTANT_BLOCK_NAME`); members whose
  std430/std140 layouts could differ (arrays, `matCx2`, user structs) are refused.
  `DxPushConstants.setPushConstants(ByteBuffer)` (client API, max 128 bytes) is implemented by
  `DxRenderPassBackend` and re-staged with every draw by the existing binder. Texel buffers no
  longer reflect a bogus `Sampler` entry.
- **Phase 1 follow-up:** G1-G3 have source implementations (see the capability table), but have
  not yet been validated on a Windows D3D11 device. Until that runtime check succeeds,
  `u_SectionTimeInfo` and arena/resource behavior remain unverified.
- The add-on's per-region `updateData` should build the 20-byte block (`float x, y, z` at 0/4/8,
  `int time` at 12, `uint regionId` at 16, native byte order) and call `setPushConstants` once per
  region before that region's multi-draw.
- **Exit:** Sodium's `block_layer_opaque` vertex/fragment shaders compile through GLSL -> SPIR-V ->
  HLSL -> D3D, with `ALPHA_CUTOUT`, `USE_FOG`, and `USE_VERTEX_COMPRESSION` define variants.
  Watch for: `textureGrad`, `dFdx/dFdy`, `textureLod`, `bool` inside a std140 block, array
  constants (`vec2[]`) in the fragment shader.

## 5b. Add-on architecture (replaces the fork)

New Fabric mod (suggested id `direcium`), depends on `sodium` and `directexp`,
compiles against Sodium as `compileOnly`, ships no Sodium code. Mixin targets:

- `DrawContext.create()`: `@Inject(HEAD, cancellable)`; if the device is DirectExp, return `new DxSodiumDrawContext()`.
- `MultiDrawBatch.newBatch(int)`: same pattern, return `new DxSodiumMultiDrawBatch(capacity)`.
- `DrawBackend` is an enum and cannot gain a constant via Mixin. It will resolve to `OPENGL` on
  DirectX (harmless as a stand-in), so any other `BACKEND == OPENGL` branch must be audited.
  Today only `MappedStagingBuffer.flush()` has one, and it is reached only when
  `features().persistentMapping()` is true. DirectExp reports false, so keep it false (or mixin that
  method before ever enabling it).
- Write `DxSodiumMultiDrawBatch` and `DxSodiumDrawContext` from scratch. The 12-byte
  `firstIndex, indexCount, baseVertex` entry is trivial; do not copy `VKMultiDrawBatch`.
- Per-region values: nothing in the add-on if Option B lands in DirectExp; the context only
  forwards `region offset / time / id` to DirectExp's push-constant hook.
- Pin the add-on to a Sodium version range. Mixin `require`d injections fail loudly on
  mismatches, which is the right behavior.

### Phase 3: Sodium add-on (replaces "the fork")
Scope: the two factory mixins and two new classes from section 5b. Nothing else. Leave all
`Gl*`/`Vulkan*` Sodium mixins untouched.

- **Exit:** Sodium + DirectExp reach the main menu, enter a world, terrain draws opaque, cutout,
  and translucent passes with correct textures; no GL calls on the DirectX path (verify via log).

### Phase 4: Correctness and stability
- Resize, fullscreen toggle, resource reload, dimension change, 30+ minute session, shutdown.
- Fog/fade path (the only user of the texel buffer), RGSS on/off, all translucency sorting modes.
- Compare against vanilla DirectX render and against Sodium-on-Vulkan on the same world.

### Phase 5: Performance
- **Implemented in source and Windows-build verified (2026-10-09; runtime profile pending):** native
  `multiDrawIndexed(IntBuffer, ...)` accepts the interleaved `firstIndex, indexCount, baseVertex`
  format and issues the batch in one JNI entry. Non-direct source buffers are packed into a direct
  native-order buffer; direct native-order input is passed through. Triangle fans retain the
  existing per-draw expansion path. The generic `DxBridge` default remains a per-draw fallback for
  non-native/test bridges.
- `persistentMapping` remains **disabled**. D3D11 has no persistently mapped resource with the
  semantics Sodium expects, and DirectExp's fence is still a stub. `MAP_WRITE_NO_OVERWRITE` on a
  dynamic resource is not equivalent to Sodium's OpenGL mapped staging path; do not advertise it.
- Opt-in timing aggregation is available with `-Ddirectexp.profile.performance=true`. Every
  10-second report includes indexed draw bridge-call time, batched multi-draw bridge-call time
  (with draw count), constant-buffer upload time, and `copyToBuffer` arena-copy time (with bytes).
  These are Java wall-clock timings around bridge/copy operations, not isolated JNI-only CPU time;
  runtime profiles still need to be collected and interpreted on Windows.

## 7. Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| PolyForm Shield noncompete | Add-on is lower risk than a fork but not proven safe | Ask CaffeineMC; never bundle Sodium code or resources; do not market it as a Sodium replacement |
| DirectExp still has open visual bugs | Hard to tell Sodium bugs from backend bugs | Phase 0 gate |
| Feature level 10.1 floor | No compute, limited UAV; `Buffer<int>` SRV is fine, but anything Sodium adds later that needs compute/SSBO won't | Keep patch to draw-path only |
| 64 KiB constant-buffer limit on the D3D11.0 runtime (`MAX_CONSTANT_BUFFER_BYTES`) | `u_Globals` is small; fine. Large UBOs elsewhere stay CPU-only | None needed for Sodium |
| JNI cost of per-draw calls | Frame-time loss versus Sodium-on-GL/VK | G7 |
| Sodium updates break the Mixin targets | Maintenance burden | Only two injection points; pin version range |
| Other mods that hook GL directly | Out of scope | Document as unsupported |

## 8. Suggested first concrete steps

1. Validate G1-G3 on Windows with a D3D11 device at feature level 10.1 or higher.
2. Exercise the G4/G6 traces in a real Sodium render, then verify visuals and stability through
   the Phase 4 scenarios.
3. Capture Phase 5 profiles with `-Ddirectexp.profile.performance=true` and compare costs against
   Sodium on Vulkan on the same system.

## 9. Unknowns I could not resolve from source

- ~~Whether defining `VULKAN` globally changes vanilla shaders~~ moot: shaderc already defines it
  for every Vulkan-semantics compile, so nothing changes.
- Real draw-call counts and timings per frame on a typical render distance, to be collected with
  the Phase 5 profiler on Windows.
