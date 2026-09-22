# DirectExp Agent Guide

This file is the working agreement for agents and collaborators in this repository.

It applies to the repository root and all git worktrees created from it.

## Project Goal

`DirectExp` is not a lightweight tweak mod. It is an experimental Fabric-delivered
backend program whose goal is to add a real native DirectX 11 backend to
modern Minecraft snapshot builds on Windows, targeting feature level 10.1
devices at minimum.

The intended architecture is:

- `DxBackend` as a first-class backend parallel to Mojang's `GlBackend` and `VulkanBackend`
- a `DirectExp`-owned graphics API setting instead of patching Mojang's enum
- startup backend negotiation controlled by `DirectExp` config and mixins
- a native Windows bridge for Win32, `IDXGISwapChain`, and D3D11 device/context lifecycle
- a shader/toolchain route based on `GLSL -> SPIR-V -> HLSL`

## Reference Project

`DirectExp` is a structural sibling of `MetalExp` (same author's Metal-backend
equivalent for macOS, same Minecraft snapshot line). Where a design question
has already been answered by MetalExp and nothing about DirectX 11 forces a
different answer, follow MetalExp's precedent instead of re-deriving it from
scratch. Where DirectX 11 genuinely differs (see "Where DirectExp Diverges
From MetalExp" below), document the divergence in the design spec rather than
silently drifting.

## Non-Goals

Do not drift into these directions unless the user explicitly changes scope:

- D3D12 or a D3D12-on-11 shim presented as "native DirectX 11"
- WARP (software rasterizer) presented as a supported hardware path; WARP may
  be useful for CI/diagnostics but is not a target device tier
- dynamically extending Mojang's `PreferredGraphicsApi` enum
- broad renderer-mod compatibility as an early priority
- overdesigning abstractions before the current milestone requires them
- silently dropping the feature level 10.1 floor to chase broader hardware
  coverage without an explicit decision to do so

## Where DirectExp Diverges From MetalExp

- **Window/surface handle**: Metal needs an `NSWindow` *and* `NSView` pair to
  attach a `CAMetalLayer`. D3D11/DXGI only needs the Win32 `HWND` to build an
  `IDXGISwapChain`. `Win32HostSurface` is therefore a single-field record,
  not a pair like `CocoaHostSurface`.
- **Feature level gating**: MetalExp does not have an equivalent concept.
  DirectExp must probe and reject devices below `D3D_FEATURE_LEVEL_10_1`
  before doing anything else, and report *why* a device was rejected
  (no D3D11 runtime vs. below floor vs. other) distinctly in diagnostics.
- **Native toolchain**: MetalExp shells out to `xcrun clang` directly because
  Apple's toolchain is fixed per-OS. DirectExp's native build goes through
  CMake (see `native/README.md`) because the Windows toolchain story (MSVC
  vs. clang-cl, SDK location) is not as uniform.
- **Shader destination**: MSL for MetalExp, HLSL for DirectExp. Both start
  from the same GLSL source and the same SPIR-V intermediate step
  (SPIRV-Cross for the SPIR-V -> target-language step).
- **Binding model**: D3D11 has no argument buffers and no root signatures,
  only flat constant-buffer/SRV/sampler/UAV slot binding. This is actually a
  slightly easier fit for the engine-owned slot map than Metal is.

## Canonical Docs

Read these before making architecture-affecting changes.

1. Design spec:
   `docs/specs/2026-09-20-directexp-design.md`
2. Current bootstrap plan:
   `docs/plans/2026-09-20-directexp-bootstrap-plan.md`
3. Project-local D3D11 feature-level / shader-route research notes:
   `docs/research/2026-09-20-directexp-d3d11-research-notes.md`

When working on Minecraft integration points, use those docs as the source
of truth before inventing a new path. When working on backend-agnostic
bootstrap/config/diagnostics code, the equivalent MetalExp source file is
often the fastest way to find the intended shape.

## Current Implementation Path

Build the project in these major slices:

1. Bootstrap, config persistence, diagnostics, and planning (this session's scaffold)
2. Settings UI replacement with a `DirectExp`-owned graphics API option (this session's scaffold)
3. Startup backend negotiation override driven by `DirectExp` config (this session's scaffold)
4. Real `DxBackend` introduction as a backend parallel to GL and Vulkan (stubbed, not real yet)
5. Native bridge bring-up for Win32 window extraction, `IDXGISwapChain`, device, context, and drawable lifecycle
6. Surface/device/resource core
7. Shader toolchain and pipeline compilation
8. Rendering viability for menu, world entry, resize, and shutdown

Do not skip directly to broad renderer work if the previous layer is still
fake or only half-wired. Current status (see docs/plans's Progress Log for
exact dates and build-verification status): slices 1-3 are wired end to end
(config -> plan -> mixin override -> settings UI) and build-verified.
Slice 4 exists as compiling stub classes implementing the real Blaze3D
backend interfaces but backed by CPU-side placeholder storage (same shape
MetalExp used at its own bootstrap stage). Slice 5 has a build-verified
general capability probe plus a real (but not yet compiled/verified) swap
chain lifecycle -- device/context/flip-model `IDXGISwapChain1`, resize,
CPU-upload blit, present, release -- reachable end to end through
`DxBackend` -> `DxBackendBootstrapContext` -> `DxSurfaceLease`. GPU-texture-
backed resources (slice 6/Milestone 3) are not started: `createTexture2D`
et al. are still default-throw stubs, and `DxTexture` silently falls back
to the CPU blit path when they're unavailable, which is why the CPU path
being real is enough to reach a presenting window before slice 6 exists.

## Git And PR Rules

`dev` is the canonical development branch for this repository.

### Branch policy

- Do normal implementation work on `dev`
- Do not create `dev/xxx` or `dev-xxx` branches unless the user explicitly asks for an auxiliary branch
- If an auxiliary branch is needed, use a descriptive name that reflects the work and keep the dependency chain explicit
- Keep review boundaries coherent through commits and PR scope even when work lands on `dev`

### Commit policy

- Do not make one giant commit for a whole milestone
- Do not make commits so tiny that each one is meaningless noise
- Prefer 2-4 commits for a substantial milestone when the work benefits from review checkpoints
- Each commit should still be coherent on its own

### PR policy

- This is a private repository; PRs are optional review checkpoints, not a
  public-facing requirement. Use them when a milestone is worth a checkpoint.
- Unless the user specifies a different base, treat `dev` as the default integration base

## Working Style

- Follow the current milestone and avoid overdesign
- Keep Minecraft-facing hooks thin and policy in plain Java where possible
- Prefer testable units before deep mixin work
- Verify claims with fresh `./gradlew test` runs before saying work is complete.
  `./gradlew build` (which also builds the native DLL) can only be verified
  on an actual Windows machine with the D3D11 SDK on PATH — say so explicitly
  rather than claiming a Windows-only build step succeeded from a non-Windows
  session.
- If reverse-engineering Minecraft internals is needed, use the same
  decompiled-source workflow MetalExp's AGENTS.md describes instead of guessing

## DirectX Integration Guidance

The point at which DirectX 11 is considered "really integrated" is not when
docs mention it. It is when:

- `BackendKind.DIRECTX` resolves to a real `DxBackend`
- startup negotiation actually instantiates and tries `DxBackend`
- strict/fallback behavior depends on real DirectX backend creation results
- the backend exposes a valid device (feature level >= 10.1) and swap-chain-backed
  surface path instead of being skipped up front

Until then, any DirectX path is scaffolding, not completion. That is exactly
where this scaffold currently sits: the negotiation and config plumbing is
real, but `DxBackend.createDevice(...)` does not yet stand up a real device.

## Notes For Future Agents

- Treat the design spec and research docs as constraints, not inspiration-only reading
- Preserve the repo's current milestone discipline even when work lands on `dev`
- If a worktree or branch is only a scratch area, do not treat it as the canonical development branch
- Prefer adding or updating `docs/plans/` when a new major milestone starts
- This is a multi-session project. Before starting work, read the bootstrap
  plan's "Progress Log" section (bottom of the file) to see what the last
  session actually finished vs. only stubbed, then update it before ending
  your own session.
