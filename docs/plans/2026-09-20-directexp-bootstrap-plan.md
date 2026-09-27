# DirectExp Bootstrap Plan

## Purpose

This plan tracks Milestone 1 (Project Bootstrap) and the very start of
Milestone 2 (Native Surface Bring-Up) as defined in
`docs/specs/2026-09-20-directexp-design.md`. It exists specifically to
support multi-session work: read the "Progress Log" at the bottom before
starting, and update it before ending a session.

## Milestone 1 Scope

- [x] Fabric project scaffold (`build.gradle`, `gradle.properties`,
  `settings.gradle`, split `main`/`client` source sets via Loom)
- [x] `BackendKind` / `FailureMode` / `GraphicsApiPreference` config models
- [x] `DirectExpConfig` + `DirectExpConfigStore` (properties-file backed,
  same format as MetalExp's `metalexp.properties`)
- [x] `BackendPlan` / `BackendPlanner` / `BackendNegotiationResolver` /
  `BootstrapState`
- [x] `DirectExpMod` main entrypoint wiring config load -> plan -> bootstrap state
- [x] `DiagnosticsSnapshot` / `DiagnosticsReporter` startup log line
- [x] `DirectExpClient` client entrypoint logging the diagnostics snapshot
- [x] `MinecraftBackendNegotiationMixin` overriding
  `PreferredGraphicsApi.getBackendsToTry()` the same way MetalExp does
- [x] `VideoSettingsDisplayOptionsMixin` / `VideoSettingsRestartWarningMixin`
  replacing the graphics API row in the video settings screen
- [x] `DirectExpGraphicsApiOptionFactory` / `DirectExpGraphicsApiSelectionState`
- [x] `fabric.mod.json`, both mixin config files, `en_us.json` lang entries
- [x] Unit tests for the backend-agnostic planning/config logic

## Milestone 2: Native Surface Bring-Up

- [x] `native/CMakeLists.txt` targeting a `directexp_native` shared library,
  linking `d3d11` / `dxgi` on Windows (bare names, portable across MSVC
  and MinGW-w64 -- see the comment in `CMakeLists.txt`). `d3dcompiler`
  is deliberately not linked yet; nothing uses it until Milestone 4.
- [x] `native/src/directexp_common.h` / `.cpp`: shared device-capability
  probe logic (`RunDeviceCapabilityProbe`), JNI object builders, and
  small portability helpers (DPI query via `GetProcAddress` so it links
  on older mingw-w64 import libs too). Factored out so
  `directexp_bridge_probe.cpp` and `directexp_bridge_surface.cpp` don't
  duplicate the feature-level-gated device creation logic.
- [x] `native/src/directexp_bridge_probe.cpp`: `probe0` -- the general
  system capability probe (temporary device, feature level 10.1 floor,
  adapter identity for diagnostics). **Build-verified by the project
  owner on MSYS2 UCRT64** as of this plan's most recent Progress Log
  entry below.
- [x] `native/src/directexp_bridge_surface.cpp`: real swap chain lifecycle --
  `probeSurface0` (HWND validity + throwaway device check),
  `bootstrapSurface0` (persistent device + immediate context +
  flip-model `IDXGISwapChain1` via `CreateSwapChainForHwnd`,
  `DXGI_MWA_NO_ALT_ENTER`), `configureSurface0` (`ResizeBuffers`, with
  vsync flag stored), `acquireSurface0` (device-removed check only --
  flip model doesn't need an explicit acquire), `blitSurfaceRgba80`
  (CPU-side RGBA8 upload straight into the back buffer via
  `UpdateSubresource`), `presentSurface0`, `releaseSurface0`.
  **NOT YET build-verified** -- authored after the probe was confirmed
  building, but not itself compiled by anyone yet. This should be the
  very first thing the next Windows session checks.
- [x] `DxBridge` / `NativeDxBridge` Java-side now has real (non-default)
  overrides for `probeSurface`, `bootstrapSurface`, `configureSurface`,
  `acquireSurface`, `blitSurfaceRgba8`, `presentSurface`,
  `releaseSurface`. `createCommandContext` / `submitCommandContext` /
  `releaseCommandContext` are intentionally still Java-only (no native
  call) -- see the file-header comment in
  `directexp_bridge_surface.cpp` for why that's correct for now, not a
  shortcut: nothing currently reaches those calls because
  `createTexture2D` is still a stub (see below).
- [ ] **Not done yet**: GPU-texture-to-backbuffer blit
  (`blitSurfaceTexture`), and therefore `createTexture2D` /
  `uploadTextureRgba8` / `releaseTexture` staying as default-throw
  stubs is a *deliberate, currently-harmless* gap -- `DxTexture` only
  attempts `createTexture2D` best-effort and silently falls back to the
  CPU path (`blitSurfaceRgba8`, which is real) when it's unavailable.
  This means the surface pipeline should work end-to-end today purely
  through the CPU path. Implementing the GPU path is Milestone 3 work,
  not blocking.
- [ ] **Not done yet**: deferred-context / multithreaded command recording
  (explicit later decision per the design spec).

## Explicit Non-Goals For This Scaffold

- No real swap chain / back buffer / present path yet (Milestone 2 proper)
- No shader toolchain work yet (Milestone 4)
- No resource core (buffers/textures backed by real D3D11 objects) yet
  (Milestone 3) -- the stub `DxBuffer`/`DxTexture`/etc. classes in this
  scaffold hold CPU-side storage only, mirroring the shape MetalExp's own
  early buffer/texture classes had before native-backed uploads existed
- No deferred-context / multithreaded command recording (explicit later
  decision per the design spec's Open Decisions)

## Milestone 3: Resource Core (texture path)

- [x] `native/src/directexp_bridge_texture.cpp`: `createTexture2DNative`
  (real `ID3D11Texture2D`, `D3D11_USAGE_DEFAULT` +
  `D3D11_BIND_SHADER_RESOURCE`, `DXGI_FORMAT_R8G8B8A8_UNORM`),
  `uploadTextureRgba8Native` (`UpdateSubresource` via
  `D3D11CalcSubresource` for the right mip), `releaseTexture0`,
  `blitSurfaceTexture0` (`CopyResource` GPU-to-GPU straight into the
  swap chain back buffer).
- [x] `g_activeDevice`/`g_activeContext` globals added to
  `directexp_common.h`/`.cpp`, set in `bootstrapSurface0` and cleared in
  `releaseSurface0` -- texture creation has no per-call device handle of
  its own (matches `DxBridge`'s existing surface-agnostic
  `createTexture2D`/`uploadTextureRgba8` signatures), so it operates
  against whichever device is currently active. Single-window
  assumption, consistent with the rest of the project.
- [x] `NativeDxBridge` now has real (non-default) overrides for
  `createTexture2D`, `uploadTextureRgba8`, `releaseTexture`,
  `blitSurfaceTexture`. `DxTexture`'s existing best-effort
  try/catch (written speculatively back when these were stubs) needed
  no changes -- it now actually succeeds instead of always falling
  back, which is exactly the seam it was designed for. Refreshed its
  now-stale "still a stub" doc comments to describe the real behavior.
- [ ] **Explicitly deferred, not a gap:** `nativeCommandContextHandle` stays
  a Java-only sentinel counter (see `NativeDxBridge.java`'s
  `NEXT_COMMAND_CONTEXT_HANDLE`), ignored on the native side. This is
  the intended v1 shape per the design spec's Open Decisions (single
  immediate context, no deferred contexts) -- not something to "fix"
  without a concrete reason to adopt deferred contexts first.
- [ ] **Not done yet:** no dimension/format mismatch check before
  `CopyResource` -- if a texture's mip 0 doesn't match the current back
  buffer size (e.g. stale after a resize), D3D11 silently skips the
  copy (debug-layer warning only, not a crash) rather than DirectExp
  catching it earlier with a clearer diagnostic.
- [x] **Verified:** `directexp_bridge_texture.cpp` builds cleanly and the
  native GPU-texture path is confirmed active at runtime -- the log
  shows `DirectExp: native GPU texture path active...`, not the
  CPU-fallback `native GPU texture creation unavailable...` line. See
  the 2026-09-23 Progress Log entry and "Milestone 3: COMPLETE" below.

- **2026-09-20** (Milestone 3 runtime result + diagnostic logging, same
  day): `runClient` result: a brief purple/blue flash (Minecraft's earliest
  loading screen, verified twice independently by the project owner across
  two separate build locations) followed by a solid black main menu, no
  crash. **This is the expected result at the current milestone boundary,
  not a bug**: the loading-screen flash is just a color clear (works since
  Milestone 2, doesn't need real draw calls), while the title screen's
  logo/buttons/panorama require actual geometry to be drawn through the
  render pipeline -- and `DxRenderPassBackend.draw()`/`drawIndexed()` are
  still literal no-ops (explicitly `// TODO(Milestone 4)`). Nothing submits
  real GPU draw work yet, so nothing beyond a clear color can appear,
  regardless of whether Milestone 3's texture upload path is itself
  working correctly. Added one-time (not per-frame) diagnostic logging in
  `DxTexture.createNativeTextureHandle` -- `DirectExp: native GPU texture
  path active...` on first success, `DirectExp: native GPU texture
  creation unavailable, using CPU-side fallback...` on first failure -- so
  future sessions can distinguish "Milestone 3's GPU path is engaged but
  Milestone 4 doesn't exist yet" from "Milestone 3 is silently falling back
  to CPU," which look visually identical right now. **Next session should
  check the log for this line before assuming Milestone 3 needs more work.**

- **2026-09-23** (Milestone 3 GPU path verified): Checked the log per the
  prior entry's instruction -- `DirectExp: native GPU texture path
  active...` appears, not the CPU-fallback
  `native GPU texture creation unavailable...` line. Confirms
  `createTexture2DNative` / `uploadTextureRgba8Native` /
  `blitSurfaceTexture0` are genuinely exercising real GPU-backed textures,
  not silently falling back to `blitSurfaceRgba8`. Milestone 3 is complete.

## Milestone 3: COMPLETE

GPU-texture resource core (`ID3D11Texture2D` creation/upload, GPU-to-GPU
back-buffer blit via `CopyResource`) is implemented and now
runtime-verified: the `native GPU texture path active` diagnostic line
confirms textures upload to real GPU memory rather than silently falling
back to the CPU path. The title screen remains a black fill, which is
still expected -- nothing issues real draw calls yet
(`DxRenderPassBackend.draw()`/`drawIndexed()` are still no-ops); that's
Milestone 4 work.

## Milestone 4: Shader and Pipeline Core (in progress -- see sub-milestone breakdown)

This milestone is now broken into sequential sub-milestones (4.1-4.6) in
`docs/plans/2026-09-25-directexp-milestone4-breakdown.md`; that doc is the
source of truth for per-stage scope and checklists going forward. Milestone
4.1 (Native Shader Toolchain Bring-Up) has been authored -- see the
Progress Log below for status. The pipeline-stage summary and dependency
list immediately below are kept for context but are superseded by the
breakdown doc's per-stage detail.

Scope per the design spec: `GLSL -> SPIR-V -> HLSL` pipeline, reflection,
layout mapping, first pipeline compile, first successful draw. This is a
substantially bigger milestone than 1-3 combined; expect it to span
several sessions on its own.

This plan is adapted from `MetalExp`'s Shader/Toolchain Layer design
(`docs/specs/2026-04-09-metalexp-design.md` and the `26.2` Vulkan/Metal
research notes in the `MetalExp` reference repo). That project has not
built this either -- it's a design-only precedent there too, not borrowed
code. The pipeline is identical through SPIR-V; only the final translation
target differs (HLSL instead of MSL), since `SPIRV-Cross` has a
first-class HLSL backend alongside its MSL one.

### Pipeline stages

- [ ] **Source stage:** load Mojang's GLSL shader source from the game's
  shader source path; apply the same preprocessing/define injection
  the modern Vulkan path already uses, so DirectExp isn't inventing a
  new shader front end.
- [ ] **IR stage:** compile GLSL -> SPIR-V via `shaderc`, the same
  compiler Mojang's own Vulkan backend already uses. SPIR-V is the
  canonical intermediate representation for the first implementation
  line.
- [ ] **Reflection/binding stage:** reflect SPIR-V resources with
  `SPIRV-Cross`, and combine that with Blaze3D's pipeline declarations
  into an engine-owned resource layout model -- not raw SPIR-V
  descriptor-set/binding semantics passed through untouched. Per
  MetalExp's own note, this mapping step is the actual hard part, not
  the translation step.
- [ ] **HLSL emission stage:** translate SPIR-V -> HLSL via
  `SPIRV-Cross`'s HLSL backend, compile the result via `D3DCompile`
  (`d3dcompiler`, which `native/CMakeLists.txt` deliberately hasn't
  linked yet -- this is the milestone that needs it, see Milestone 2's
  checklist above), and build structures that map the engine-owned
  layout model onto concrete D3D11 register slots (`b#`/`t#`/`s#`).

### Binding model

Use a flat, engine-managed slot map, not argument buffers or root
signatures -- easier to debug, simpler deterministic diagnostics, lower
first-version complexity. This is the same choice MetalExp made for its
own binding model, and it fits DirectExp especially well since D3D11 has
no argument-buffer or root-signature equivalent anyway (see AGENTS.md's
"Binding model" divergence note) -- so it's a natural fit here, not a
compromise.

### Dependencies still to add

- [x] `shaderc` (GLSL -> SPIR-V) -- linked in `native/CMakeLists.txt`
  (Milestone 4.1), **authored but not build-verified** (see Progress Log)
- [x] `SPIRV-Cross` (reflection + SPIR-V -> HLSL) -- linked in
  `native/CMakeLists.txt` (Milestone 4.1), **authored but not
  build-verified**
- [x] link `d3dcompiler` in `native/CMakeLists.txt` -- done (Milestone
  4.1), **authored but not build-verified**

### Success criterion for this milestone

First real pipeline compile and first successful draw call, replacing
`DxRenderPassBackend`'s current no-op `draw()`/`drawIndexed()`/
`drawMultipleIndexed()`. Until a draw call lands, the window stays a black
fill regardless of how correct the texture path is (see Milestone 3's
note in Current Status above) -- don't mistake continued black output
during this milestone for a texture-path regression.

## Suggested Next Session's Scope

The GPU-texture path is confirmed active (see Progress Log above), so
Milestone 3's texture upload path genuinely works, not just compiles.
Start Milestone 4 above. Re-read the design spec's Shader and Resource
Binding Design section before starting rather than improvising the
binding model, and treat the pipeline-stage breakdown above as the
checklist to work through in order -- reflection/binding is the stage
most likely to eat the most time, per MetalExp's own risk note.

## Progress Log

Add an entry here at the end of every working session. Keep entries short;
- **2026-09-20** (native output naming fix, same day): `buildNativeWindowsBridge`
  warned `directexp_native.dll was not found after the native build`. Cause:
  CMake's default shared-library naming differs by toolchain on Windows --
  MSVC produces `directexp_native.dll`, but MinGW-w64/GCC (MSYS2 UCRT64)
  follows the Unix convention and produces `libdirectexp_native.dll` unless
  told otherwise. This also would have broken
  `NativeDxBridgeLoader`'s `System.loadLibrary("directexp_native")` call at
  runtime even if the Gradle copy step had been made to tolerate the `lib`
  prefix, since Java's `loadLibrary` never adds that prefix on Windows.
  Fixed with `set_target_properties(directexp_native PROPERTIES PREFIX "")`
  in `native/CMakeLists.txt` so the output name is `directexp_native.dll`
  on both toolchains. **Not yet reconfirmed with a clean build** -- next
  session should re-run `./gradlew.bat build` and check for the file at
  `build/native/directexp_native.dll` before moving on to `runClient`.

- **2026-09-20** (Milestone 2 runtime verification, same day): `./gradlew.bat
  runClient` produced a real window -- solid black, correct size, with
  Minecraft's title-screen music and narrator audio playing, i.e. the game
  is genuinely running against the `DxBackend` path, not silently falling
  back to Vulkan/OpenGL. **This is Milestone 2's actual success
  criterion, met.** The project owner noted they can't yet pin down exactly
  which earlier step (a stale `build/native/` directory vs. an actual
  command mistake) caused the prior "no DLL produced" report, and will be
  more careful about clean-build state going forward -- worth keeping in
  mind if a similar "nothing was output" report shows up again: check
  whether `build/native/` is actually fresh before assuming a new code bug.

## Milestone 2: COMPLETE

Native swap chain lifecycle (device/context, flip-model swap chain, resize,
CPU-upload blit, present, release) is implemented, build-verified on
MSYS2 UCRT64, and now runtime-verified: the client reaches a real,
presenting DirectX-backed window. Black fill is expected and correct at
this stage -- `DxTexture`'s backing storage is zero-initialized and nothing
writes real pixel data into it yet (that's Milestone 3+ / actual rendering,
not a bug in what's implemented so far).

- **2026-09-25** (Milestone 4.1, authored in a non-Windows sandbox):
  Implemented the checklist in
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`'s Milestone
  4.1 section. `native/CMakeLists.txt` now links `shaderc_combined` and
  `spirv-cross-hlsl`/`spirv-cross-glsl`/`spirv-cross-core`, plus
  `d3dcompiler` (removing the old "deliberately not linked yet" comment);
  `native/README.md` documents the two new MSYS2 UCRT64 pacman packages.
  Added `native/src/directexp_shader_toolchain.{h,cpp}`: a smoke test that
  compiles one hardcoded GLSL fragment shader through `shaderc` to SPIR-V,
  then through SPIRV-Cross's `CompilerHLSL` to HLSL text, logging both via
  stderr/`OutputDebugStringA`. No JNI entrypoint for it yet, per the
  milestone's scope -- it runs automatically from a new `JNI_OnLoad` hook
  (fires as soon as the JVM loads `directexp_native.dll`, no Java-side
  change needed to trigger it). Also added the runtime-caveat diagnostic:
  `d3dcompiler_47.dll` is only a guaranteed Windows 8.1+ OS component, and
  since it's now statically linked, a missing copy would otherwise surface
  as a generic, confusing native-load failure for the whole bridge DLL.
  `NativeDxBridgeLoader.checkD3DCompilerAvailability` pre-checks for it at
  its well-known `%SystemRoot%\System32` location before attempting
  `System.loadLibrary`/`System.load` at all, reporting a new
  `DxBridgeLoadStatus.DEPENDENCY_MISSING` / `DxBridgeProbeStatus.RUNTIME_DEPENDENCY_MISSING`
  pair with an actionable message when it's absent. **Nothing in this
  entry has been build-verified**: this session ran in a Linux sandbox
  with no MSYS2/MSVC toolchain, `shaderc`, or `SPIRV-Cross` available, and
  no JDK (`javac`) either, so not even the plain-Java bridge changes could
  be compiled here -- only reviewed by hand against the existing style in
  `directexp_common.{h,cpp}` and `directexp_bridge_probe.cpp`. A new test,
  `NativeDxBridgeLoaderTest`, covers `checkD3DCompilerAvailability` in
  isolation (it doesn't touch `System.loadLibrary`) but has likewise not
  been run. **Next session on a Windows/MSYS2 UCRT64 machine should**:
  install the two new pacman packages, run `./gradlew.bat test` first
  (fast, no native build, confirms the Java-side changes at least compile
  and the new test passes), then `./gradlew.bat build` and confirm the
  Milestone 4.1 success criterion -- a log line showing GLSL going in and
  HLSL text coming out, visible on `runClient` startup via console or
  DebugView.
- **2026-09-25** (Milestone 4.1 Windows build verification, same day):
  On Windows 10 with MSYS2 UCRT64/GCC 16.2, the first CMake configure hit
  Shaderc/glslang's broken install export: `SPIRV` and `glslang` referenced
  `SPIRV-Tools-opt` without exporting it. Fixed this by disabling install
  rules for the in-tree dependency build and disabling Shaderc's unused,
  deprecated HLSL frontend; DirectExp accepts GLSL and uses SPIRV-Cross for
  SPIR-V -> HLSL. CMake then configured successfully and the native build
  produced `directexp_native.dll`. The CMake/Ninja tree was moved to
  `.gradle/native` so `./gradlew.bat clean` preserves Shaderc, glslang,
  SPIRV-Tools, and bridge object files; the cache path was reconfigured and
  confirmed to survive `clean`. At that point, the GLSL -> SPIR-V -> HLSL
  startup log and Java test suite still needed a fresh post-fix run; the
  suite subsequently passed, as recorded in the 2026-09-26 entry below,
  while the startup log remains outstanding.
- **2026-09-26** (Milestone 4.2, authored in a non-Windows sandbox):
  Implemented `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`'s
  Milestone 4.2 checklist (Source and Preprocessing Stage). New package
  `dev.cubcraftv.directexp.client.shader`: `DxShaderSourceStage` loads raw
  GLSL via `ShaderSource.get(id, type)` and preprocesses it via
  `GlslPreprocessor.injectDefines`, normalizing vertex and fragment
  through one code path. This session had `vulkan.zip` available -- a
  decompiled `com.mojang.blaze3d.vulkan` package for this project's own
  Minecraft version (`26.2-snapshot-1`) -- which resolved the shader
  toolchain research notes' "still genuinely unknown" preprocessing
  question by direct inspection of `VulkanDevice.compileShader` rather
  than by assumption; see that file's section 4 and the updated Milestone
  4.2 checklist for exactly what was confirmed. `DxShaderPipelineDescriptor`
  and `DxGlslDefinesInjector` exist specifically so the stage's core logic
  is unit-testable (`DxShaderSourceStageTest`) without a fully
  registry-backed `RenderPipeline` or a real `ShaderDefines`/
  `GlslPreprocessor` call -- see those classes' javadoc for why. Also
  extended `build.gradle`'s `test` source set to see `client`'s output and
  classpath, since this new code is necessarily client-only (it depends on
  `ShaderSource`/`RenderPipeline`) but the milestone requires it stay
  unit-testable without a Windows machine.
  **At the time this entry was written, nothing had been build- or
  test-verified**: this session had a JDK but no Fabric/Minecraft
  dependencies reachable (no
  network access to `maven.fabricmc.net` or Mojang's piston-meta from this
  sandbox), so `./gradlew test` could not be run here, only reviewed by
  hand against the decompiled Vulkan source and this repo's existing
  `DxBridgeLoadResult`/`DxBridgeLoadStatus`-style conventions. In
  particular, the exact shape of `ShaderSource` (whether it really is a
  single-method `get(Identifier, ShaderType)` interface, as every call
  site in the decompiled Vulkan source implies) has not been confirmed
  against real Minecraft classes here. **The remaining verification is**
  (per the Milestone 4.2 success criterion) to exercise
  `DxShaderSourceStage.load(RenderPipeline, ShaderSource)` against a real
  pipeline's `defaultShaderSource` for a representative shader (e.g.
  `core/position`) and log/compare the preprocessed output against what
  the Vulkan path produces for the same shader. The 2026-09-26 Progress
  Log entry below records the subsequently passing test/build verification.

- **2026-09-26** (Milestone 4.1/4.2 verification): `./gradlew test`
  passed, confirming the Java changes and current unit tests compile and
  pass. `./gradlew.bat buildNativeWindowsBridge --rerun-tasks` also passed
  on Windows, rebuilding the native bridge and exercising its post-build
  DLL copy. That copy had failed because the Groovy build script called
  Kotlin's unavailable `File.copyTo()` extension; it now uses Java NIO
  `Files.copy(..., REPLACE_EXISTING)`. These checks do not verify the 4.1
  GLSL -> SPIR-V -> HLSL startup smoke log or 4.2's preprocessed output
  against a live Mojang shader; those runtime checks remain outstanding.

- **2026-09-26** (Milestone 4.3 authored): implemented the IR stage's
  three checklist items --
  a real `compileGlslToSpirv0` JNI entrypoint
  (`directexp_bridge_shader.cpp`) built on a `CompileGlslToSpirv` helper
  factored out of 4.1's smoke test (which now calls it too, instead of
  duplicating the `shaderc` call), structured compile-error diagnostics
  end to end (`GlslToSpirvResult` -> `NativeDxIrCompilationResult` ->
  `DxIrCompilationResult` -> `DxIrCompilationStageResult`, keeping a real
  `shaderc` compile error distinct from a bridge-unavailable failure at
  every layer), and a `DxSpirvCache` keyed on
  `(Identifier, ShaderType, preprocessedSource)` so `DxIrCompilationStage`
  doesn't recompile an unchanged shader on every call. **At the time this
  entry was written, nothing had been build- or runtime-verified**: same
  sandbox constraint as the 4.2 entry above (no Fabric/Minecraft
  dependencies reachable, and additionally no MSYS2/MSVC Windows
  toolchain, `shaderc`, or `SPIRV-Cross` available here at all), so
  neither `./gradlew test` nor a native build could be run from this
  session. `DxIrCompilationStageTest` exercises the Java-side orchestration
  (ready path, vertex-compile-failure short-circuit, fragment-compile
  failure, bridge-unavailable, and two cache-hit/cache-miss cases) against
  a fake `DxBridge`, but that is necessarily a stand-in for real `shaderc`
  behavior, reviewed by hand against this repo's existing
  `DxBridgeProbe`/`DxShaderSourceStage` result-and-status conventions, not
  a substitute for running it. **The remaining verification is** (per the
  Milestone 4.3 success criterion) to run `./gradlew test` and
  `./gradlew.bat buildNativeWindowsBridge` on a real Windows/MSYS2 UCRT64
  machine, then exercise `compileGlslToSpirv0` against a representative
  preprocessed shader from 4.2 (e.g. `core/position`) and at least one
  deliberately-broken GLSL string, confirming the latter comes back as a
  structured `COMPILE_ERROR` result rather than crashing or throwing.

- **2026-09-26** (Milestone 4.1 runtime verification): `./gradlew.bat test`
  and `./gradlew.bat build` passed. `./gradlew.bat runClient` reached the
  DirectX backend on Windows and logged the complete native shader smoke
  test: shaderc accepted the hardcoded GLSL, produced 106 SPIR-V words,
  SPIRV-Cross emitted HLSL, and the GLSL -> SPIR-V -> HLSL round trip
  succeeded inside `directexp_native`. The same run logged the native GPU
  texture path as active. Milestone 4.1's runtime success criterion is now
  met. The live 4.2 representative-source comparison and 4.3 real JNI
  compilation/error exercise remain outstanding.

- **2026-09-27** (Milestone 4.4 authored): implemented both of the
  Reflection and Resource Layout Model's checklist halves. Native: a new
  `directexp_shader_reflection.h/.cpp` reflects SPIR-V via SPIRV-Cross's
  base `Compiler::get_shader_resources()`, exposed through a new
  `reflectSpirv0` JNI entrypoint added to the existing
  `directexp_bridge_shader.cpp` (kept in that file rather than a new
  `directexp_bridge_reflection.cpp`, since both are pure-CPU-side shader
  toolchain entrypoints with no device/surface dependency); GLSL's
  combined `sampler2D` is reflected as a paired `SAMPLED_TEXTURE`+
  `SAMPLER` entry under one engine name, anticipating the split 4.5's
  `CompilerHLSL` will need to perform. Java: `DxBridge.reflectSpirv`/
  `NativeDxBridge` (bridge package, mirroring `DxIrCompilationResult`'s
  shape) and `DxReflectionStage` (`client/shader`, mirroring
  `DxIrCompilationStage`'s per-stage short-circuit shape) reflect both
  pipeline stages; `DxResourceLayoutBuilder` (pure Java, no native call)
  then combines that with a `DxPipelineResourceDeclarations` (Blaze3D's
  own `getSamplers()`/`getUniforms()`) into the engine-owned
  `DxResourceLayout`, via a concrete, documented slot-assignment
  algorithm: merge vertex+fragment reflections by `(kind, name)`, sort
  each of the four `DxResourceKind` categories by name, assign `0`-based
  slots per kind independently. Declared-vs-reflected mismatches are
  logged, not treated as hard failures, per this milestone's own
  "manually cross-checked, not yet exercised through an actual draw call"
  success criterion. **At the time this entry was written, nothing had
  been build- or runtime-verified against real SPIRV-Cross**: same
  sandbox constraint as every 4.x entry above -- no MSYS2/MSVC Windows
  toolchain, `shaderc`, or `SPIRV-Cross` available here, and additionally
  no Fabric/Minecraft/Yarn sources or Maven Central access in this
  session to confirm `RenderPipeline.getSamplers()`/`getUniforms()`/
  `UniformDescription`'s exact shape against a decompile the way 4.2's
  `GlslPreprocessor.injectDefines` call was (flagged explicitly on
  `DxPipelineResourceDeclarations`'s javadoc as needing that confirmation
  in a live session). What *was* verified in this session, in lieu of a
  real `./gradlew test` run: all new and edited Java source (bridge
  package plus `client/shader`) was compiled with a real `javac` against
  hand-written stand-ins for the handful of external Minecraft/Blaze3D/
  slf4j types it touches (`Identifier`, `ShaderType`, `RenderPipeline`,
  `DirectExpMod.LOGGER`) -- confirming there are no Java-level type
  errors even though the Gradle/Fabric Loom build itself couldn't run --
  and `DxReflectionStageTest`/`DxResourceLayoutBuilderTest`'s 13 test
  methods were run for real (not just compiled) against a small
  hand-written JUnit-annotation-compatible runner, all passing. **The
  remaining verification is** (per the Milestone 4.4 success criterion)
  to run `./gradlew.bat test` and `./gradlew.bat buildNativeWindowsBridge`
  on a real Windows/MSYS2 UCRT64 machine, then exercise `reflectSpirv0`
  against a representative shader's real SPIR-V from 4.3 (e.g.
  `core/position`) and manually cross-check the resulting
  `DxResourceLayout` against that shader's source by eye, plus confirm
  `RenderPipeline.getSamplers()`/`getUniforms()` against a real decompile
  of `26.2-snapshot-1`.

- **2026-09-27 (later same day)** (Milestone 4.5 authored): implemented
  all four of "HLSL Emission and D3D Compilation"'s checklist items.
  Native: a new `directexp_shader_hlsl.h/.cpp` (third toolchain-concern
  file, alongside `directexp_shader_toolchain.h/.cpp` for `shaderc` and
  `directexp_shader_reflection.h/.cpp` for SPIRV-Cross reflection)
  translates SPIR-V to HLSL via `CompilerHLSL` (`shader_model = 50`) and
  compiles it via `D3DCompile` -- the first `D3DCompile` call anywhere in
  this project -- exposed through a new `emitAndCompileHlsl0` JNI
  entrypoint added to `directexp_bridge_shader.cpp`. Each resource in
  Milestone 4.4's `DxResourceLayout` is forced into the emitted HLSL as an
  explicit register via SPIRV-Cross's `add_hlsl_resource_binding`, keyed
  on each resource's own SPIR-V `(stage, desc_set, binding)` decorations
  (looked up via `get_decoration`) rather than by name -- a combined GLSL
  `sampler2D` (reflected in 4.4 as two same-named entries) is merged back
  into one `HLSLResourceBinding` with both its SRV and Sampler register
  set. Small 4.4 refactor along the way: the resource-naming fallback
  (`ResourceName`) moved out of `directexp_shader_reflection.cpp` into a
  new shared `directexp_shader_resource_naming.h`, since 4.5 needs to
  derive the exact same name for the exact same resource to look up its
  slot assignment, and a second, independently-written copy risked
  drifting out of sync with the first. Java: `DxResourceSlotAssignment`/
  `DxHlslCompilationStatus`/`DxHlslCompilationResult`/
  `NativeDxHlslCompilationResult` (bridge package) and
  `DxHlslModule`/`DxHlslPipelineModules`/`DxHlslCompilationStageStatus`/
  `DxHlslCompilationStageResult`/`DxHlslCompilationStage` (`client/shader`,
  mirroring `DxReflectionStage`'s per-stage short-circuit shape, but with
  two failure statuses per stage instead of one, since emission and
  compilation can each fail independently). Also fixed a real bug surfaced
  while wiring 4.5 to 4.4: `DxResourceLayout.find(String)` was ambiguous
  for a combined sampler's two same-named entries; added a kind-aware
  `find(String, DxResourceKind)` overload (old one kept, now documented as
  ambiguous for that case) and used it throughout 4.5's own code.
  **Verification note, and what made this session different from every
  4.x session before it:** this sandbox reached
  `raw.githubusercontent.com` and fetched SPIRV-Cross's real, current
  upstream `spirv_hlsl.hpp`/`spirv_cross.hpp`/`spirv_common.hpp`/`spirv.hpp`
  directly from `https://github.com/KhronosGroup/SPIRV-Cross`, and checked
  `HLSLResourceBinding`'s exact field names, `CompilerHLSL::Options`,
  `add_hlsl_resource_binding`'s signature, `set_hlsl_options`, the
  `ExecutionModel`/`Decoration` enums' actual namespace (`spv::`, *not*
  `spirv_cross::` -- a real mistake caught and fixed during this session
  by that check, not merely a hypothetical one), and `Resource`/
  `ShaderResources`/`get_decoration`/`get_declared_struct_size`'s
  signatures (the last two also retroactively confirming 4.4's existing
  reflection code, written before this check, is correct) against that
  real header rather than against recollection. `D3DCompile`'s own
  signature was not fetched during authoring (Microsoft's Win32 headers
  aren't on GitHub the way SPIRV-Cross is); it was instead independently
  confirmed later the same day when the person driving this session
  pasted Microsoft's official documented signature and it was checked
  argument-by-argument against the actual call in
  `directexp_shader_hlsl.cpp` -- all eleven parameters (order and type)
  matched exactly, no code changes needed. What was *not* possible here, same as every
  other 4.x entry: an actual MSYS2/MSVC/`D3DCompile` compile, or running a
  real shader's SPIR-V through this path. Java-side compile/test
  verification followed the same procedure as 4.4's entry above: all new
  and edited Java compiled cleanly under a real `javac` against the same
  hand-written external-type stand-ins, and all 19 tests across
  `DxReflectionStageTest`/`DxResourceLayoutBuilderTest`/
  `DxHlslCompilationStageTest` (13 carried over from 4.4, 6 new) passed
  under the same hand-written runner. **Remaining verification** (per the
  4.5 success criterion): a real Windows/MSYS2 build, running a
  representative shader's real SPIR-V through `emitAndCompileHlsl0`, and
  confirming the resulting HLSL/bytecode by eye (or via `D3DReflect`)
  against the 4.4 layout it was given.

- **2026-09-27 (addendum to the "(later same day)" 4.5 entry above):**
  the person driving this session pasted Microsoft's official documented
  `D3DCompile` signature. Checked argument-by-argument against the actual
  call in `directexp_shader_hlsl.cpp`: all eleven parameters (`pSrcData`,
  `SrcDataSize`, `pSourceName`, `pDefines`, `pInclude`, `pEntrypoint`,
  `pTarget`, `Flags1`, `Flags2`, `ppCode`, `ppErrorMsgs`), in that order,
  with matching types -- confirmed exact, no code changes needed. Updated
  that entry above and the 4.5 checklist's success-criterion note (in
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`) to say this
  was independently confirmed rather than "not similarly re-checked". This
  closes the one specific gap that entry flagged in how `D3DCompile` was
  verified (SPIRV-Cross's API already had upstream-header confirmation);
  it does not change the entry's other conclusion that nothing has
  actually been compiled or run on a real Windows/MSVC toolchain yet.

## Current Status (as of the last entry above)

- **Runtime-verified on Windows (MSYS2 UCRT64):** Milestones 1, 2, and 3 all
  confirmed working end to end: config -> plan -> negotiation -> settings UI
  -> `DxBackend` -> real D3D11 device/context -> flip-model swap chain ->
  presenting window, with the GPU-texture path (`createTexture2DNative` /
  `uploadTextureRgba8Native` / `blitSurfaceTexture0`) confirmed active via
  the `native GPU texture path active` diagnostic line.
- **Still black on screen, but that's Milestone 4's gap, not Milestone 3's:**
  the window shows a black fill because `DxRenderPassBackend.draw()`/
  `drawIndexed()` are still no-ops -- nothing issues real GPU draw work yet.
  That's expected at this milestone boundary; it does not indicate the
  texture upload/blit path is CPU-only, which the diagnostic line above
  rules out.
- **Milestone 4.1 (shader toolchain bring-up) runtime-verified:** the native
  configure/build, persistent cache, and Java test suite are confirmed on
  Windows/MSYS2 UCRT64, and `runClient` logged the complete GLSL -> SPIR-V
  -> HLSL startup smoke test on 2026-09-26. See the 2026-09-26 Progress Log
  entry and
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`. This does not
  change the black-fill status above -- 4.1 is infrastructure wiring plus
  a standalone smoke test, not a draw call.
- **Milestone 4.2 (source/preprocessing stage) unit-tested; live shader
  comparison outstanding:** `DxShaderSourceStage` and its supporting types
  pass `./gradlew test` as of 2026-09-26. Real preprocessed output has not
  yet been checked against the Vulkan path for a live shader. See the
  2026-09-26 Progress Log entry above. This stage is not wired into
  `DxDeviceBackend.precompilePipeline` yet -- that's later Milestone 4 work
  (4.3+), once there's an IR stage for it to feed into.
- **Milestone 4.3 (IR stage/real GLSL -> SPIR-V) Java-side build-verified,
  native integration still outstanding:** `./gradlew.bat test` and
  `./gradlew.bat build` pass, covering the structured result chain and
  `DxSpirvCache`/`DxIrCompilationStage` unit tests. The real
  `compileGlslToSpirv0` call has not yet been exercised against a
  representative preprocessed shader or a deliberately broken GLSL input,
  and this stage is not wired into
  `DxDeviceBackend.precompilePipeline` either yet -- same reason as 4.2's
  bullet above, now one stage further along.
- **Milestone 4.4 (reflection and resource layout model) authored,
  compiled and unit-tested outside Gradle; native and live-decompile
  verification outstanding:** `DxReflectionStage`/`DxResourceLayoutBuilder`
  and their supporting bridge/client types compile cleanly under a real
  `javac` and their 13 unit tests pass under a hand-written runner (this
  session had no Fabric/Minecraft dependencies or Maven Central access to
  run the real `./gradlew test`). The real `reflectSpirv0` call has not
  been exercised against real SPIR-V, `RenderPipeline.getSamplers()`/
  `getUniforms()` has not been confirmed against a decompile, and this
  stage is not wired into `DxDeviceBackend.precompilePipeline` yet -- same
  reason as 4.2/4.3's bullets above, now one stage further along. See the
  2026-09-27 Progress Log entry above and
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`.
- **Milestone 4.5 (HLSL emission and D3D compilation) authored, compiled
  and unit-tested outside Gradle; native verification outstanding:**
  `DxHlslCompilationStage` and its supporting bridge/native types compile
  cleanly under a real `javac`/(recollection-independent, upstream-checked)
  read of SPIRV-Cross's real headers, and 19 unit tests across all three
  of 4.4/4.5's stage test classes pass under a hand-written runner. The
  real `emitAndCompileHlsl0` call -- SPIRV-Cross HLSL emission followed by
  the project's first ever `D3DCompile` call -- has not been exercised
  against real SPIR-V, and this stage is not wired into
  `DxDeviceBackend.precompilePipeline` yet -- same reason as every 4.x
  bullet above, now one stage further along, one milestone item (4.6)
  short of Milestone 4's actual finish line. See the "2026-09-27 (later
  same day)" Progress Log entry above and
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`.