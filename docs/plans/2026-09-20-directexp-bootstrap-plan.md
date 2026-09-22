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

## Suggested Next Session's Scope: Milestone 3 (Resource Core)

Milestones 1 and 2 are done. Per the design spec's milestone list, Milestone
3 is buffers, textures, samplers, uploads, blit support, and basic frame
lifecycle -- concretely, in priority order:

1. **`createTexture2D` for real.** Implement the native entrypoint (new
   `directexp_bridge_texture.cpp`, following the same pattern as
   `directexp_bridge_surface.cpp`): create an `ID3D11Texture2D` with
   `D3D11_USAGE_DEFAULT` and `D3D11_BIND_SHADER_RESOURCE`, format
   `DXGI_FORMAT_R8G8B8A8_UNORM`, matching the `width`/`height`/`mipLevels`
   already passed from `DxTexture`'s Java-side best-effort call. Wire the
   matching `NativeDxBridge` override (currently absent -- `createTexture2D`
   is still `DxBridge`'s default-throw stub, so `DxTexture` always falls
   back to CPU today).
2. **`uploadTextureRgba8` for real.** `ID3D11DeviceContext::UpdateSubresource`
   into the right mip subresource (`D3D11CalcSubresource`), same technique
   already used for `blitSurfaceRgba80`.
3. **`releaseTexture`.** Release the `ID3D11Texture2D`, free the handle.
4. **`blitSurfaceTexture`.** `CopyResource` from the GPU texture straight
   into the swap chain back buffer -- replaces the CPU round-trip
   (`blitSurfaceRgba8`) for anything Blaze3D marks as texture-backed, which
   should be the common case once step 1 works. Note this needs a real
   `nativeCommandContextHandle` value to mean something -- right now
   `createCommandContext`/`submitCommandContext`/`releaseCommandContext`
   are Java-only sentinel counters (see `NativeDxBridge.java`); decide
   whether they stay that way (single immediate context, ignore the handle
   value) or need to become real per-context state. The design spec's
   Open Decisions section defers deferred-context/multithreading, so
   "stay Java-only, ignore the handle" is the consistent choice unless
   something concrete forces otherwise.
5. **Verify visually, not just "it compiles/runs".** Once texture upload
   works, the window should start showing Minecraft's actual title-screen
   content (logo, buttons, background) instead of solid black. That's the
   real Milestone 3 success signal -- a black window that doesn't crash is
   not sufficient evidence the texture path works, since black is also
   what the current CPU-fallback path already produces.

Do not jump ahead to shader/pipeline work (Milestone 4) while resources are
still not GPU-round-trip-verified -- this mirrors the discipline MetalExp's
own `AGENTS.md` calls out explicitly.

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

- **Runtime-verified on Windows (MSYS2 UCRT64):** Milestones 1 and 2 both
  confirmed working end to end: config -> plan -> negotiation -> settings UI
  -> `DxBackend` -> real D3D11 device/context -> flip-model swap chain ->
  presenting window.
- **Still entirely CPU-side:** every pixel currently goes through
  `blitSurfaceRgba8` (CPU upload), because `createTexture2D` is still a
  stub and `DxTexture` silently falls back to CPU storage. This was the
  right call for reaching a presenting window quickly, but it's the reason
  the window is black rather than showing anything -- nothing populates
  that CPU buffer with real content yet, and even once something does, the
  CPU round-trip is not the long-term performance path.
