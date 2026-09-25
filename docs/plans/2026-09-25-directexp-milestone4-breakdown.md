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
  `NativeDxBridgeLoaderTest`, itself not yet run (no JDK in this sandbox).**

**Success criterion:** a build-verified log line showing GLSL source going
  in and HLSL text coming out, entirely inside `directexp_native`, with
  nothing Minecraft-specific involved yet. **Build prerequisite met; the
  runtime log criterion is not yet met** -- run the native smoke test through
  `runClient` on Windows and capture the GLSL/SPIR-V/HLSL log. See
`docs/plans/2026-09-20-directexp-bootstrap-plan.md`'s 2026-09-25 Progress
Log entry for exactly what a Windows session should check first.

---

## Milestone 4.2: Source and Preprocessing Stage

**Depends on:** 4.1 (for confidence the toolchain works; this stage itself
is Java-side and native-independent).

Goal: get real Mojang GLSL shader source loaded and preprocessed the way
the existing Vulkan path already does it, so later stages have real input
instead of a hardcoded smoke-test string.

- [ ] Locate and load shader source from the game's shader source path
  (design spec's "Source Stage").
- [ ] Apply the same preprocessing / `#define` injection the modern
  Vulkan path already uses -- this needs to be confirmed against Mojang's
  actual source, not assumed; see the toolchain research notes' "still
  genuinely unknown" section.
- [ ] Normalize stage-specific input (vertex vs. fragment) before it's
  handed to compilation.
- [ ] Unit-testable in pure Java/JVM terms (no native call needed yet) --
  keep it that way so this stage doesn't require a Windows machine to
  iterate on.

**Success criterion:** for at least one representative Mojang shader
(e.g. `position` or another simple current-line shader), DirectExp
produces preprocessed GLSL source text that matches what the Vulkan path
would compile, verified by comparison/log rather than by rendering
anything yet.

---

## Milestone 4.3: IR Stage (Real GLSL -> SPIR-V)

**Depends on:** 4.1 (toolchain) and 4.2 (real source input).

Goal: replace 4.1's hardcoded smoke-test string with real preprocessed
shader source from 4.2, and make the GLSL -> SPIR-V step a real,
JNI-exposed native call with proper error handling -- not just a proof
that the library links.

- [ ] Expose a native bridge call that takes preprocessed GLSL text (from
  4.2) and returns SPIR-V bytes (or a clear compilation error).
- [ ] Surface `shaderc` compile errors (syntax errors, unsupported
  constructs) as structured diagnostics rather than a raw native
  exception -- this is the first stage where "the shader failed to
  compile" becomes a real, expected runtime case rather than a
  can't-happen.
- [ ] Cache/store SPIR-V per shader identity as a stepping stone toward
  the pipeline cache keys 4.6 needs -- doesn't need to be the final cache
  design yet, just needs to exist so 4.4/4.5 aren't recompiling from
  scratch on every call.

**Success criterion:** the representative shader(s) from 4.2 compile to
valid SPIR-V through the real native bridge call, with at least one
deliberately-broken shader input exercised to confirm errors surface
usefully instead of crashing.

---

## Milestone 4.4: Reflection and Resource Layout Model

**Depends on:** 4.3 (needs real SPIR-V, not the 4.1 smoke test).

**This is the stage most likely to eat the most time** -- called out as
the actual hard part (not the translation step) by both the design spec
and MetalExp's own precedent. Treat schedule estimates for this
sub-milestone with more suspicion than the others.

- [ ] Reflect SPIR-V resources (constant buffers, sampled textures,
  samplers, structured buffers) via SPIRV-Cross.
- [ ] Combine reflection output with Blaze3D's pipeline declarations into
  an **engine-owned** resource layout model -- explicitly not raw SPIR-V
  descriptor-set/binding semantics passed through untouched (design
  spec's Shader and Resource Binding Design section is authoritative
  here; re-read it before improvising).
- [ ] Layout model must distinguish at minimum: constant buffers (`b#`),
  sampled-texture SRVs (`t#`), samplers (`s#`, separate from textures --
  D3D11 doesn't have GL's combined sampler model), and structured/typed
  buffers where needed.
- [ ] Decide and document how the flat engine-managed slot map (the
  binding model choice shared with MetalExp) actually assigns slot
  numbers -- this is a concrete algorithm, not just a philosophy, and
  needs to be written down since 4.5 depends on it being deterministic.

**Success criterion:** for the representative shader(s), the layout model
produced matches what a human reading the shader source would expect
(manually cross-checked, not yet exercised through an actual draw call).

---

## Milestone 4.5: HLSL Emission and D3D Compilation

**Depends on:** 4.4 (needs the layout model to map SPIR-V bindings to
concrete D3D11 register slots).

- [ ] Translate SPIR-V to HLSL via SPIRV-Cross's `CompilerHLSL`, targeting
  Shader Model 5.0 (`vs_5_0`/`ps_5_0` -- confirm the exact `HLSLOptions`
  shader-model value against the feature-level-10.1 floor from the
  existing D3D11 research notes).
- [ ] Compile the emitted HLSL to bytecode via `D3DCompile` (now linked
  per 4.1), producing `ID3D11VertexShader`/`ID3D11PixelShader`-ready
  bytecode blobs.
- [ ] Map the 4.4 layout model onto concrete D3D11 register slots
  (`b#`/`t#`/`s#`) and build the structures that let later stages bind
  resources by the engine-owned names rather than raw slot numbers.
- [ ] Surface `D3DCompile` errors as structured diagnostics, same
  treatment as 4.3's `shaderc` error handling.

**Success criterion:** the representative shader(s) produce valid D3D11
shader bytecode end to end from original GLSL source, with register-slot
mapping data attached -- still not drawn yet, just compiled.

---

## Milestone 4.6: Pipeline Object Assembly and First Draw

**Depends on:** 4.5. This is Milestone 4's actual finish line and the
design spec's stated success criterion for the whole milestone.

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
