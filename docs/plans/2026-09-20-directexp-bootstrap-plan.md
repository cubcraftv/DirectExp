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

## Milestone 2 Start (native probe only)

- [x] `native/CMakeLists.txt` targeting a `directexp_native` shared library,
      linking `d3d11.lib` / `dxgi.lib` / `d3dcompiler.lib` on Windows
- [x] `native/src/directexp_bridge_probe.cpp`: a JNI entrypoint that creates
      a temporary `ID3D11Device`, requests feature levels down to 10.1,
      rejects anything below that floor, and reports adapter identity
- [x] `DxBridge` / `NativeDxBridge` / `NativeDxBridgeLoader` Java-side
      surface for calling into the probe, with the same
      unsupported-OS / not-found / load-failed / native-error status
      modeling MetalExp uses
- [ ] **Not done yet**: swap chain creation, window attachment, or anything
      past the capability probe. `DxBackend.createDevice(...)` currently
      throws once probing succeeds -- it does not stand up a real device.
- [ ] **Not verified**: the native `.cpp` file has not been compiled. This
      scaffold was authored in a non-Windows sandbox with no MSVC/Windows
      SDK available. First real session on a Windows machine should start
      by running `./gradlew.bat build` and fixing whatever the compiler
      finds before writing any more native code.

## Explicit Non-Goals For This Scaffold

- No real swap chain / back buffer / present path yet (Milestone 2 proper)
- No shader toolchain work yet (Milestone 4)
- No resource core (buffers/textures backed by real D3D11 objects) yet
  (Milestone 3) -- the stub `DxBuffer`/`DxTexture`/etc. classes in this
  scaffold hold CPU-side storage only, mirroring the shape MetalExp's own
  early buffer/texture classes had before native-backed uploads existed
- No deferred-context / multithreaded command recording (explicit later
  decision per the design spec's Open Decisions)

## Suggested Next Session's Scope

In priority order:

1. Get the native library actually compiling and loading on a real Windows
   machine (`./gradlew.bat build`, iterate on `native/src/*.cpp` and
   `native/CMakeLists.txt` until `directexp_native.dll` exists and
   `NativeDxBridgeTest`-equivalent probe checks pass).
2. Extend the native bridge with `HWND` resolution + `IDXGISwapChain`
   creation, and extend `DxBridge`/`NativeDxBridge` to call it, following
   the same "probe -> bootstrap -> configure/acquire/present -> release"
   shape MetalExp's `MetalBridge` interface uses for its own surface
   lifecycle.
3. Wire `DxBackendBootstrapContext` to actually use the new swap-chain
   bootstrap result instead of the current placeholder path.
4. Only after 1-3 are real: start on resource core (Milestone 3).

Do not jump ahead to shader/pipeline work (Milestone 4) while the surface
path is still a stub -- this mirrors the discipline MetalExp's own
`AGENTS.md` calls out explicitly.

## Progress Log

Add an entry here at the end of every working session. Keep entries short;
the checklists above are the source of truth for what's actually done.

- **2026-09-20** (scaffolding session): Created the full Milestone 1 scaffold
  plus a Milestone-2-start native probe, authored in a non-Windows sandbox
  and ported/adapted from the `MetalExp` reference project in the same
  workspace. Nothing has been compiled or run. Next session should start on
  a Windows machine per "Suggested Next Session's Scope" above.
