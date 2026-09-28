# Milestone 4 Breakdown: Shader and Pipeline Core

This replaces the single "Milestone 4: Shader and Pipeline Core (planned,
not started)" section in
`docs/plans/2026-09-20-directexp-bootstrap-plan.md` with sub-milestones.
Rationale: Milestone 4 is explicitly called out in that plan as
"a substantially bigger milestone than 1-3 combined," and unlike
Milestones 1-3 it currently has **zero** infrastructure in place --
`native/CMakeLists.txt` doesn't link `shaderc`, `SPIRV-Cross`, or
`d3dcompiler` yet, and `DxRenderPassBackend.draw()`/`drawIndexed()` are
still literal no-ops. Splitting it gives each session a checkpoint smaller
than "the whole shader pipeline," consistent with `AGENTS.md`'s commit
policy of 2-4 review-sized commits per substantial milestone rather than
one giant one.

The sub-milestones below are sequential and each one's "Depends on"
line names the prior stage it needs -- this mirrors the design spec's own
four-stage pipeline breakdown (Source -> IR -> Reflection/Binding -> HLSL
Emission) in `docs/specs/2026-09-20-directexp-design.md`, plus a toolchain
bring-up step in front of it and a pipeline-assembly/first-draw step after
it, since those two are real, separately-verifiable chunks of work that the
spec's four stages don't by themselves cover.

Use `2026-09-25-directexp-milestone4-shader-toolchain-research.md` for the
library-level specifics (MSYS2 packages, API shape, the `d3dcompiler_47.dll`
runtime caveat) referenced below.

---

## Milestone 4.1: Native Shader Toolchain Bring-Up

**Depends on:** Milestone 3 (complete). **Blocks:** everything else in
Milestone 4.

Goal: prove `shaderc` and `SPIRV-Cross` actually link and run inside
`directexp_native`, and that `d3dcompiler` links, before any real shader
content touches them. No Mojang shader source, no JNI surface, no engine
layout model yet -- this stage is purely "does the toolchain work at all
on this machine."

- [x] Install `mingw-w64-ucrt-x86_64-shaderc` and
  `mingw-w64-ucrt-x86_64-spirv-cross` via pacman; document the packages in
  `native/README.md` alongside the existing `gcc`/`g++`/`cmake`/`ninja`
  entries. **Documented; the actual `pacman -S` install still needs to run
  on a real MSYS2 UCRT64 machine.**
- [x] Add both libraries to `native/CMakeLists.txt` (`target_link_libraries`
  against the packaged static/shared libs -- see the research notes for
  which target names to try first) and confirm `directexp_native` still
  configures and builds with them linked but unused. **Linked
  (`shaderc_combined`, `spirv-cross-hlsl`/`spirv-cross-glsl`/`spirv-cross-core`)
  and build-verified on Windows/MSYS2 UCRT64.**
- [x] Link `d3dcompiler` in `native/CMakeLists.txt` the same bare-name way
  `d3d11`/`dxgi` are already linked (removing the "deliberately not linked
  yet" comment that currently marks this). **Done and build-verified on
  Windows/MSYS2 UCRT64.**
- [x] Smoke test only, not real integration: compile one small hardcoded
  GLSL string (e.g. a trivial passthrough fragment shader literal in the
  `.cpp` file, not loaded from Minecraft) through `shaderc` to SPIR-V, then
  through `SPIRV-Cross`'s `CompilerHLSL` back to HLSL text, and log the
  result. No `D3DCompile` call yet, no JNI exposure yet -- just proving the
  two libraries interoperate. **Implemented in
  `native/src/directexp_shader_toolchain.cpp`, triggered automatically via
  a new `JNI_OnLoad` hook rather than a dedicated entrypoint; not yet
  runtime-smoke-verified. The containing native target now builds on
  Windows/MSYS2 UCRT64.**
- [x] Add a distinct diagnostic path (not the generic native-load failure)
  for `d3dcompiler_47.dll` failing to load at runtime, per the research
  notes' runtime-caveat section, following the same "why did this
  specifically fail" pattern the feature-level floor check already uses.
  **Implemented Java-side (`NativeDxBridgeLoader.checkD3DCompilerAvailability`,
  new `DxBridgeLoadStatus.DEPENDENCY_MISSING`/
  `DxBridgeProbeStatus.RUNTIME_DEPENDENCY_MISSING`), since 4.1 has no new
  native JNI surface to hang this off yet; covered by
  `NativeDxBridgeLoaderTest`, which passed with `./gradlew test` on
  2026-09-26.**

**Success criterion:** a build-verified log line showing GLSL source going
  in and HLSL text coming out, entirely inside `directexp_native`, with
  nothing Minecraft-specific involved yet. **Met on 2026-09-26:** Windows
  `runClient` logged the GLSL input, 106 SPIR-V words, emitted HLSL, and the
  successful GLSL -> SPIR-V -> HLSL round trip from `directexp_native`. See
`docs/plans/2026-09-20-directexp-bootstrap-plan.md`'s 2026-09-25 Progress
Log entry for exactly what a Windows session should check first.

---

## Milestone 4.2: Source and Preprocessing Stage

**Depends on:** 4.1 (for confidence the toolchain works; this stage itself
is Java-side and native-independent).

Goal: get real Mojang GLSL shader source loaded and preprocessed the way
the existing Vulkan path already does it, so later stages have real input
instead of a hardcoded smoke-test string.

- [x] Locate and load shader source from the game's shader source path
  (design spec's "Source Stage"). **`DxShaderSourceStage.loadStage` calls
  `ShaderSource.get(id, type)` directly -- the same real Blaze3D lookup
  the Vulkan path uses (`VulkanDevice.compileShader`) -- rather than
  reinventing a source-lookup path.**
- [x] Apply the same preprocessing / `#define` injection the modern
  Vulkan path already uses -- this needs to be confirmed against Mojang's
  actual source, not assumed; see the toolchain research notes' "still
  genuinely unknown" section. **Confirmed, not assumed: decompiled the
  `com.mojang.blaze3d.vulkan` package for this project's own Minecraft
  version (`26.2-snapshot-1`) and found `VulkanDevice.compileShader` calls
  `GlslPreprocessor.injectDefines(source, key.defines)` directly, keyed on
  `(Identifier id, ShaderType type, ShaderDefines defines)`. DirectExp's
  stage calls the identical real Mojang method via
  `DxGlslDefinesInjector.mojangDefault()`, not a reimplementation. The
  "still genuinely unknown" note above predates this decompile and can be
  considered resolved.**
- [x] Normalize stage-specific input (vertex vs. fragment) before it's
  handed to compilation. **`DxShaderSourceStage.loadStage` is the single
  code path both `ShaderType.VERTEX` and `ShaderType.FRAGMENT` go
  through; the result is tagged with which stage it came from
  (`DxPreprocessedShaderSource.type`) rather than the two stages having
  separate, potentially-diverging handling.**
- [x] Unit-testable in pure Java/JVM terms (no native call needed yet) --
  keep it that way so this stage doesn't require a Windows machine to
  iterate on. **`DxShaderSourceStageTest` covers the ready path, both
  missing-source paths, and stage normalization using a fake
  `ShaderSource` and a fake `DxGlslDefinesInjector` -- no native call, no
  real `ShaderDefines`/`GlslPreprocessor` instance, and no fully
  registry-backed `RenderPipeline` needed (`DxShaderPipelineDescriptor`
  carries only the three fields the stage actually needs). `build.gradle`
  was extended so the `test` source set can compile against `client`
  (needed since `ShaderSource`/`RenderPipeline` are client-only Blaze3D
  types); this project's default `test` source set previously only saw
  `main`.**

**Success criterion:** for at least one representative Mojang shader
(e.g. `position` or another simple current-line shader), DirectExp
produces preprocessed GLSL source text that matches what the Vulkan path
would compile, verified by comparison/log rather than by rendering
anything yet. **The unit-test gate passed with `./gradlew test` on
2026-09-26, but the criterion still requires separately exercising
`DxShaderSourceStage.load(RenderPipeline, ShaderSource)` against a real
pipeline's `defaultShaderSource` on a live client to log/compare real
preprocessed output for a representative shader like `core/position`.
That live comparison remains outstanding; see the bootstrap plan's
2026-09-26 Progress Log entry.**

---

## Milestone 4.3: IR Stage (Real GLSL -> SPIR-V)

**Depends on:** 4.1 (toolchain) and 4.2 (real source input).

Goal: replace 4.1's hardcoded smoke-test string with real preprocessed
shader source from 4.2, and make the GLSL -> SPIR-V step a real,
JNI-exposed native call with proper error handling -- not just a proof
that the library links.

- [x] Expose a native bridge call that takes preprocessed GLSL text (from
  4.2) and returns SPIR-V bytes (or a clear compilation error).
  **`directexp_bridge_shader.cpp`'s `compileGlslToSpirv0` JNI entrypoint,
  reached from Java via `NativeDxBridge.compileGlslToSpirv` /
  `DxBridge.compileGlslToSpirv`. The actual `shaderc` call was factored
  out of Milestone 4.1's smoke test into a shared, reusable
  `CompileGlslToSpirv` helper in `directexp_shader_toolchain.h/.cpp` (the
  smoke test now calls it too, instead of duplicating the `shaderc` call
  inline) rather than duplicated in the new file.**
- [x] Surface `shaderc` compile errors (syntax errors, unsupported
  constructs) as structured diagnostics rather than a raw native
  exception -- this is the first stage where "the shader failed to
  compile" becomes a real, expected runtime case rather than a
  can't-happen. **`GlslToSpirvResult` never throws on a `shaderc` compile
  failure; `BuildIrCompilationResult` turns it into a
  `NativeDxIrCompilationResult` (`OUTCOME_COMPILE_ERROR` plus
  `detail`/`numErrors`/`numWarnings` from `shaderc`'s own diagnostics),
  and `DxBridge.compileGlslToSpirv`'s Java-side result
  (`DxIrCompilationResult`) keeps `isCompileError()` distinct from a
  bridge-level failure (library not loaded, entrypoint missing) via
  `DxIrCompilationStatus`. `DxIrCompilationStage` (the actual pipeline-level
  IR stage, in `client/shader`) keeps that same distinction one level up
  as `VERTEX_COMPILE_FAILED`/`FRAGMENT_COMPILE_FAILED` vs.
  `BRIDGE_UNAVAILABLE`.**
- [x] Cache/store SPIR-V per shader identity as a stepping stone toward
  the pipeline cache keys 4.6 needs -- doesn't need to be the final cache
  design yet, just needs to exist so 4.4/4.5 aren't recompiling from
  scratch on every call. **`DxSpirvCache`, keyed on `(Identifier, ShaderType,
  preprocessedSource)` rather than just `(Identifier, ShaderType)` --
  needed since the same shader identity can be preprocessed differently
  under different `ShaderDefines` for different pipelines, matching how
  the existing Vulkan path's own `VulkanDevice.ShaderCompilationKey`
  includes defines, not just id/type. Deliberately unbounded and
  never-invalidated; see that class's javadoc for why this is explicitly
  a stepping stone and not meant to be extended toward 4.6's design.**

**Success criterion:** the representative shader(s) from 4.2 compile to
valid SPIR-V through the real native bridge call, with at least one
deliberately-broken shader input exercised to confirm errors surface
usefully instead of crashing. **Not yet verified against the real
toolchain -- this session was authored in a Linux sandbox with no
Windows/MSVC toolchain, `shaderc`, or `SPIRV-Cross` available (same
constraint noted in 4.1's and 4.2's entries), so `./gradlew test` could
run only the Java-side unit tests, not a real native compile.
`DxIrCompilationStageTest` covers the shape of this checklist's second
bullet with a fake `DxBridge` (a successful compile, a vertex compile
error, a fragment compile error, a bridge-unavailable case, and two
cache-hit/cache-miss cases) but that's necessarily a stand-in for real
`shaderc` behavior, not a substitute for it. Exercising a real
representative shader (and a real deliberately-broken one) through the
actual native call on a live Windows client remains outstanding --
see the bootstrap plan's Progress Log.**

---

## Milestone 4.4: Reflection and Resource Layout Model

**Depends on:** 4.3 (needs real SPIR-V, not the 4.1 smoke test).

**This is the stage most likely to eat the most time** -- called out as
the actual hard part (not the translation step) by both the design spec
and MetalExp's own precedent. Treat schedule estimates for this
sub-milestone with more suspicion than the others.

- [x] Reflect SPIR-V resources (constant buffers, sampled textures,
  samplers, structured buffers) via SPIRV-Cross. **New
  `directexp_shader_reflection.h/.cpp` (kept separate from
  `directexp_shader_toolchain.h/.cpp`: reflection is SPIRV-Cross-only work
  with no `shaderc` call in it), exposed via a new `reflectSpirv0` JNI
  entrypoint added to the existing `directexp_bridge_shader.cpp` (a
  pure-CPU-side "shader toolchain" entrypoint, same as
  `compileGlslToSpirv0`, so it did not need its own bridge file). Uses
  SPIRV-Cross's base `spirv_cross::Compiler::get_shader_resources()` --
  not `CompilerReflection` (a different, JSON-dump tool) and not
  `CompilerHLSL` (needed only once 4.5 starts) -- per the toolchain
  research notes. GLSL's combined `sampler2D` is reflected as *two*
  entries under the same engine name (one `SAMPLED_TEXTURE`, one
  `SAMPLER`), anticipating the split `CompilerHLSL` will need to perform
  in 4.5 anyway. Java side: `DxBridge.reflectSpirv`/`NativeDxBridge`
  (bridge package) plus `DxReflectionStage` (`client/shader`), mirroring
  `DxIrCompilationStage`'s shape (structured `REFLECTION_ERROR`, distinct
  from bridge-unavailable statuses; per-stage `VERTEX_REFLECTION_FAILED`/
  `FRAGMENT_REFLECTION_FAILED`).**
- [x] Combine reflection output with Blaze3D's pipeline declarations into
  an **engine-owned** resource layout model -- explicitly not raw SPIR-V
  descriptor-set/binding semantics passed through untouched (design
  spec's Shader and Resource Binding Design section is authoritative
  here; re-read it before improvising). **`DxResourceLayoutBuilder`
  (`client/shader`), pure Java with no native call -- takes a
  `DxPipelineReflection` (both stages' reflected resources, kept separate
  per stage until merged here) and a `DxPipelineResourceDeclarations`
  (Blaze3D's own `pipeline.getSamplers()`/`pipeline.getUniforms()`) and
  produces a `DxResourceLayout`. The declared-vs-reflected cross-check is
  diagnostic-only (logged, not a hard failure) -- see that class's javadoc
  for why an automated hard failure would be premature before a live
  shader has actually been run through this path. `DxResourceLayoutEntry`
  never carries SPIR-V's own descriptor-set/binding decorations forward;
  its `slot` is entirely this project's own assignment (see next bullet).
  **Caveat:** `RenderPipeline.getSamplers()`/`getUniforms()` and
  `UniformDescription`'s shape are used on the same confidence basis as
  4.2's existing `getVertexShader()`/`getFragmentShader()`/
  `getShaderDefines()`/`getLocation()` calls, but -- unlike 4.2's
  `GlslPreprocessor.injectDefines` call, which was confirmed against a
  real decompile -- this specific pair of accessors has not been checked
  against a decompile of `26.2-snapshot-1` in this session (no
  Minecraft/Yarn sources or network access to fetch them were available).
  Confirm in a live session before leaning on it beyond the diagnostic
  cross-check `DxResourceLayoutBuilder` uses it for today.**
- [x] Layout model must distinguish at minimum: constant buffers (`b#`),
  sampled-texture SRVs (`t#`), samplers (`s#`, separate from textures --
  D3D11 doesn't have GL's combined sampler model), and structured/typed
  buffers where needed. **`DxResourceKind` (bridge package, shared with
  the client layer): `CONSTANT_BUFFER`, `SAMPLED_TEXTURE`, `SAMPLER`,
  `STRUCTURED_BUFFER`. `DxResourceLayout` exposes a filtered view per kind
  (`constantBuffers()`/`sampledTextures()`/`samplers()`/
  `structuredBuffers()`) plus `find(name)`.**
- [x] Decide and document how the flat engine-managed slot map (the
  binding model choice shared with MetalExp) actually assigns slot
  numbers -- this is a concrete algorithm, not just a philosophy, and
  needs to be written down since 4.5 depends on it being deterministic.
  **Documented on `DxResourceLayoutBuilder`: (1) merge the vertex and
  fragment stage's reflected resources by `(kind, name)` identity, marking
  a resource used in both stages as one entry with both
  `usedInVertexStage`/`usedInFragmentStage` set; (2) within each
  `DxResourceKind`, sort the merged resources by name via ordinary
  `String.compareTo` (SPIR-V's own resource enumeration order is not a
  stable contract, so this -- not that -- is what makes slot assignment
  deterministic across recompiles); (3) assign slot numbers as each
  resource's `0`-based index within that sorted, per-kind list --
  constant buffers, sampled textures, samplers, and structured buffers
  each get independent `0`-based numbering, matching D3D11's own separate
  `b#`/`t#`/`s#`/`u#` register spaces. A same-named resource reflected
  with two different sizes across stages (should not happen for a shared
  `cbuffer` declaration, but not structurally prevented) is logged as a
  warning, with the vertex stage's size winning, rather than failing the
  build.**

**Success criterion:** for the representative shader(s), the layout model
produced matches what a human reading the shader source would expect
(manually cross-checked, not yet exercised through an actual draw call).
**Not yet exercised against a real shader or a real draw call -- same
Linux-sandbox constraint as 4.1/4.2/4.3 (no Windows/MSVC toolchain,
`shaderc`, or `SPIRV-Cross` available to this session). `DxReflectionStageTest`
and `DxResourceLayoutBuilderTest` (both pure JVM, no native call) cover
the stage's shape with fake reflected resources -- a successful
two-stage reflection, a vertex/fragment reflection error each stopping
the pipeline, a bridge-unavailable case, deterministic per-kind slot
ordering, cross-stage merging (both a shared resource and a
single-stage-only one), the combined-sampler split producing matching
`SAMPLED_TEXTURE`/`SAMPLER` entries, all four resource kinds being
distinguished, and declared/reflected mismatches not blocking the build
-- but that's necessarily a stand-in for real SPIRV-Cross output on a
real shader, not a substitute for it. Running an actual representative
shader's real SPIR-V through `reflectSpirv0` on a live Windows client, and
manually cross-checking the resulting `DxResourceLayout` against the
shader source by eye, remains outstanding -- see the bootstrap plan's
Progress Log.**

---

## Milestone 4.5: HLSL Emission and D3D Compilation

**Depends on:** 4.4 (needs the layout model to map SPIR-V bindings to
concrete D3D11 register slots).

- [x] Translate SPIR-V to HLSL via SPIRV-Cross's `CompilerHLSL`, targeting
  Shader Model 5.0 (`vs_5_0`/`ps_5_0` -- confirm the exact `HLSLOptions`
  shader-model value against the feature-level-10.1 floor from the
  existing D3D11 research notes). **New `directexp_shader_hlsl.h/.cpp`
  (a third toolchain-concern file, alongside `directexp_shader_toolchain.h/.cpp`
  for `shaderc` and `directexp_shader_reflection.h/.cpp` for SPIRV-Cross
  reflection). `CompilerHLSL::Options::shader_model = 50` -- same value
  4.1's smoke test already picked for the same feature-level-10.1
  reasoning, now actually fed into a real `D3DCompile` call for the first
  time. Verification note below covers how the SPIRV-Cross API itself was
  checked.**
- [x] Compile the emitted HLSL to bytecode via `D3DCompile` (now linked
  per 4.1), producing `ID3D11VertexShader`/`ID3D11PixelShader`-ready
  bytecode blobs. **`CompileHlslToBytecode` in the new file, targeting
  `vs_5_0`/`ps_5_0` per stage, entry point `"main"` (SPIRV-Cross's default
  -- see the code comment on why, confirmed against the real header).
  `Flags1`/`Flags2` left at `0` for now, flagged as worth revisiting once
  a real shader has actually round-tripped through this path.**
- [x] Map the 4.4 layout model onto concrete D3D11 register slots
  (`b#`/`t#`/`s#`) and build the structures that let later stages bind
  resources by the engine-owned names rather than raw slot numbers.
  **Uses SPIRV-Cross's `add_hlsl_resource_binding` (`HLSLResourceBinding`:
  `stage`/`desc_set`/`binding` identify the SPIR-V resource by its own
  decorations, `cbv`/`srv`/`uav`/`sampler` each carry a
  `register_space`/`register_binding` pair -- "On SM 5.0 and lower,
  register_space is ignored" per SPIRV-Cross's own comment, so only
  `register_binding` is set, straight from `DxResourceLayoutEntry::slot()`).
  A combined GLSL `sampler2D` -- reflected in 4.4 as two separate entries
  under one name -- is merged back into one `HLSLResourceBinding` with
  both `srv` and `sampler` populated, matching "a single binding in
  SPIR-V might contain multiple resource types ... SRV/Sampler bindings
  will be used respectively" per SPIRV-Cross's own comment. A structured
  buffer's engine slot is assigned to both `srv` and `uav`, since this
  project's layout model doesn't yet distinguish which one SPIRV-Cross
  will actually pick (readonly -> SRV `ByteAddressBuffer`, otherwise UAV).
  Java-side: `DxResourceSlotAssignment` (bridge package) and
  `DxHlslCompilationStage` (`client/shader`), which filters Milestone
  4.4's `DxResourceLayout` down to each stage's own resources
  (`usedInVertexStage()`/`usedInFragmentStage()`) before handing them to
  the bridge -- also fixed a real ambiguity bug found while wiring this
  up: `DxResourceLayout.find(String)` could silently return either half
  of a combined sampler's two same-named entries, so a kind-aware
  `find(String, DxResourceKind)` overload was added (the old overload
  kept, now documented as ambiguous for that case).**
- [x] Surface `D3DCompile` errors as structured diagnostics, same
  treatment as 4.3's `shaderc` error handling. **Split into two distinct
  statuses rather than one, since this stage has two independent failure
  points: `DxHlslCompilationStatus.EMISSION_ERROR` (SPIRV-Cross rejected
  the SPIR-V) and `.COMPILE_ERROR` (`D3DCompile` rejected the emitted
  HLSL) -- mirrored at the pipeline level by
  `DxHlslCompilationStageStatus`'s `VERTEX_EMISSION_FAILED`/
  `FRAGMENT_EMISSION_FAILED`/`VERTEX_COMPILE_FAILED`/`FRAGMENT_COMPILE_FAILED`.
  The emitted HLSL text is kept on the result even when only the compile
  step failed (`DxHlslCompilationResult.hlsl()`), so a human debugging a
  `D3DCompile` rejection can see exactly what text it was given.**

**Success criterion:** the representative shader(s) produce valid D3D11
shader bytecode end to end from original GLSL source, with register-slot
mapping data attached -- still not drawn yet, just compiled. **Not yet
exercised against a real shader or a real `D3DCompile` call -- same
Linux-sandbox constraint as every 4.x milestone before it (no
MSYS2/MSVC/`D3DCompile`/`SPIRV-Cross` available here).
`DxHlslCompilationStageTest` (pure JVM, no native call, 6 test methods)
covers the stage's shape with a fake bridge: both stages compiling,
vertex emission error stopping before fragment is attempted, a fragment
compile error being reported once vertex succeeds, bridge-unavailable
being distinct from either, and -- specific to this milestone --
confirming the per-stage resource-slot filtering actually excludes a
fragment-only sampler from the vertex stage's assignment list. What this
session *could* do that earlier 4.x sessions couldn't: fetch SPIRV-Cross's
real, current upstream `spirv_hlsl.hpp` from
`https://github.com/KhronosGroup/SPIRV-Cross` (`raw.githubusercontent.com`
is reachable from this sandbox) and check `HLSLResourceBinding`,
`CompilerHLSL::Options`, `add_hlsl_resource_binding`, and
`set_hlsl_options` against the real header directly, rather than against
recollection -- every specific field name, comment, and behavior claim in
`directexp_shader_hlsl.cpp`'s comments was checked this way, not assumed.
`D3DCompile`'s own signature was not fetched during authoring (Microsoft's
Win32 headers aren't hosted on GitHub the way SPIRV-Cross is), but was
independently confirmed afterward: the person driving this session pasted
Microsoft's official documented signature, and the actual call in
`directexp_shader_hlsl.cpp` was checked against it argument-by-argument
(all eleven parameters, order and type) -- it matches exactly, no code
changes needed. That confirms the call's shape is right, not that it has
actually been compiled or run. Running an actual
representative shader's real SPIR-V through `emitAndCompileHlsl0` on a
live Windows client, and confirming the resulting HLSL/bytecode by eye
(or via `D3DReflect`) against the 4.4 layout it was given, remains
outstanding -- see the bootstrap plan's Progress Log.**

---

## Milestone 4.6: Pipeline Object Assembly and First Draw

**Depends on:** 4.5. This is Milestone 4's actual finish line and the
design spec's stated success criterion for the whole milestone.

- [ ] **Prerequisite resource backing (approved 2026-09-28; see
  `2026-09-28-directexp-milestone4.6-source-of-truth.md`):**
  - [x] `DxBuffer` real `ID3D11Buffer` mirror (authored, unverified on Windows)
  - [x] `DxSampler` real `ID3D11SamplerState` (authored, unverified on Windows)
  - [x] `DxTextureView` real `ID3D11ShaderResourceView` (authored, unverified on Windows)
  - [ ] Render-target / depth-target support (RTV/DSV, non-RGBA8 formats) --
    open decision, see the source-of-truth doc's section 2
  - [ ] Uniform-slice binding strategy (staging buffer vs D3D11.1 ranged
    bind) -- open decision, same section
- [ ] Build the `DxRenderPipeline`-equivalent state: vertex/pixel shader
  handles, `ID3D11InputLayout` derived from the vertex format, render
  target formats, depth/stencil state, blend state, rasterizer state, and
  the resource layout references from 4.4/4.5.
- [ ] Pipeline cache keys must key on actual render state, not shader
  identity alone (design spec's Pipeline Compilation section, same
  requirement MetalExp has).
- [ ] Wire `DxRenderPassBackend.draw()` / `drawIndexed()` /
  `drawMultipleIndexed()` (currently literal no-ops marked
  `// TODO(Milestone 4)`) through the native bridge to actually issue
  `Draw`/`DrawIndexedInstanced` against the compiled pipeline state.
- [ ] Confirm on real hardware that the black main-menu fill (expected
  and documented as correct through Milestones 2-3) is replaced by actual
  rendered geometry.

**Success criterion (this is Milestone 4's overall success criterion,
carried over unchanged from the current plan):** first real pipeline
compile and first successful draw call, replacing `DxRenderPassBackend`'s
no-op `draw()`/`drawIndexed()`/`drawMultipleIndexed()`. Until this lands,
the window stays a black fill regardless of how correct everything below
this stage is -- don't mistake continued black output during 4.1-4.5 for a
regression in those stages.

---

## Suggested Session Boundaries

Given `AGENTS.md`'s guidance of 2-4 commits per substantial milestone and
that Milestone 4 as a whole is expected to span several sessions:

- 4.1 is a reasonable single session (or less) -- it's infrastructure
  wiring with a narrow, unambiguous success check.
- 4.2 and 4.3 can plausibly share a session since 4.2 is Java-only and
  low-risk.
- 4.4 should get its own dedicated session(s) and should not be rushed to
  fit a time-box -- it's the named highest-risk stage.
- 4.5 and 4.6 are natural follow-on sessions once 4.4's layout model
  exists and is trusted.

As with the rest of the plan, update the Progress Log after each session
with what actually got build/runtime-verified versus what's still
"authored but not confirmed," using the same distinction already applied
to Milestones 2 and 3 above.
