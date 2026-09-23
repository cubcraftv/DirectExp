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

## Milestone 4: Shader and Pipeline Core (planned, not started)

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

- [ ] `shaderc` (GLSL -> SPIR-V)
- [ ] `SPIRV-Cross` (reflection + SPIR-V -> HLSL)
- [ ] link `d3dcompiler` in `native/CMakeLists.txt`

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
the checklists above are the source of truth for what's actually done.

- **2026-09-20** (scaffolding session): Created the full Milestone 1 scaffold
  plus a Milestone-2-start native probe, authored in a non-Windows sandbox
  and ported/adapted from the `MetalExp` reference project in the same
  workspace. Nothing has been compiled or run. Next session should start on
  a Windows machine per "Suggested Next Session's Scope" above.
- **2026-09-20** (toolchain fixup, same day): Project owner built on Windows
  via IntelliJ + MSYS2 UCRT64 (`gcc`/`g++`/`cmake`/`ninja` added to system
  `PATH`, `JAVA_HOME` + IntelliJ's Gradle JVM pointed at a JDK 25). Fixed
  along the way: `--release 25` requires the compiler JDK itself to be 25+
  (not just source/target compatibility settings); `writeToTexture(...,
  NativeImage.Format, ...)` was missing a `height` parameter against the
  real `CommandEncoderBackend` interface; CMake's default Windows generator
  ("NMake Makefiles") assumes an MSVC Developer Command Prompt and doesn't
  suit MinGW -- switched to an explicit `-G Ninja`; `d3d11.lib`-style
  linking and `Microsoft::WRL::ComPtr` were MSVC-first choices, replaced
  with bare CMake library names and manual `Release()` calls so MinGW-w64
  works without extra dependencies. **`probe0` (the general capability
  probe) built successfully after these fixes.**
- **2026-09-20** (Milestone 2 surface lifecycle, same day): Implemented the
  real swap chain path: `probeSurface0`, `bootstrapSurface0` (persistent
  device + immediate context + flip-model `IDXGISwapChain1`),
  `configureSurface0` (resize), `acquireSurface0` (device-removed check),
  `blitSurfaceRgba80` (CPU RGBA8 upload into the back buffer),
  `presentSurface0`, `releaseSurface0`. Refactored the probe's device-
  creation logic into `directexp_common.h`/`.cpp` so it's shared instead of
  duplicated. Wired real (non-default) `NativeDxBridge` overrides for all
  of the above.
- **2026-09-20** (Milestone 2 build fix, same day): `native/CMakeLists.txt`
  build failed: `IDXGISwapChain1`, `IDXGIFactory2`, `DXGI_SWAP_CHAIN_DESC1`,
  `DXGI_ALPHA_MODE_IGNORE`, `DXGI_SCALING_STRETCH` all undeclared, plus a
  cascading `__uuidof(IDXGIFactory2)` failure. Root cause: those are all
  declared in `<dxgi1_2.h>` (DXGI 1.2), and `directexp_common.h` only
  included the base `<dxgi.h>`. One-line fix: added the `dxgi1_2.h`
  include. **`native/src/*.cpp` now builds successfully on MSYS2 UCRT64.**
  `DXGI_SWAP_EFFECT_FLIP_DISCARD` and `DXGI_MWA_NO_ALT_ENTER` were never
  flagged as errors, confirming they're already visible via the base
  `dxgi.h` on this mingw-w64 header version -- worth re-confirming if a
  different mingw-w64/SDK version is ever used.

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