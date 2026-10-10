# DirectExp Agent Guide

This file is the working agreement for agents and collaborators in this repository.

It applies to the repository root and all git worktrees created from it.

## Project Goal

`DirectExp` is not a lightweight tweak mod. It is an experimental Fabric-delivered
backend program whose goal is to add a real native DirectX 11 backend to
Minecraft versions newer than 26.2 on Windows desktop, with a window that
provides a Win32 `HWND` and D3D11 devices at feature level 10.1 or higher.
The current Gradle dependency versions are build inputs, not the support target.

The intended architecture is:

- `DxBackend` as a first-class backend parallel to Mojang's `GlBackend` and `VulkanBackend`
- a `DirectExp`-owned graphics API setting instead of patching Mojang's enum
- startup backend negotiation controlled by `DirectExp` config and mixins
- a native Windows bridge for Win32, `IDXGISwapChain`, and D3D11 device/context lifecycle
- a shader/toolchain route based on `GLSL -> SPIR-V -> HLSL`

## Research Background

DirectExp is an independent DirectX 11 implementation. MetalExp research
informed some early investigation, but DirectExp is not a structural sibling,
does not share an implementation plan, and should not copy MetalExp's
architecture by default. Use project-local D3D11 research and the current
Minecraft API as the source of truth. Consult MetalExp research only when it
provides relevant background, and document any adopted idea with its DirectX
11 rationale.

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

## DirectX 11 Constraints

- **Window/surface handle**: D3D11/DXGI uses the Win32 `HWND` to build an
  `IDXGISwapChain`; `Win32HostSurface` therefore carries that handle.
- **Feature level gating**: DirectExp must probe and reject devices below
  `D3D_FEATURE_LEVEL_10_1`
  before doing anything else, and report *why* a device was rejected
  (no D3D11 runtime vs. below floor vs. other) distinctly in diagnostics.
- **Native toolchain**: DirectExp's Windows native build goes through CMake
  (see `native/README.md`) to support available Windows toolchains and SDKs.
- **Shader destination**: DirectExp translates GLSL through SPIR-V to HLSL
  using SPIRV-Cross.
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
of truth before inventing a new path. MetalExp may be consulted as research
background, but it is not DirectExp's implementation template.

## Implementation Guidance

Work incrementally from backend negotiation through native device/surface
lifecycle, resources, shader/pipeline support, and rendering validation. Do not
skip ahead while a dependency layer is fake or only half-wired. Consult the
bootstrap plan and progress log for the current work and verification status;
do not rely on a static roadmap summary here.

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
- If reverse-engineering Minecraft internals is needed, inspect the current
  Minecraft decompile and checked-in API references instead of guessing

## DirectX Integration Guidance

DirectX 11 integration is established by the implementation and its evidence,
not by documentation claims. Verify that:

- `BackendKind.DIRECTX` resolves to a real `DxBackend`
- startup negotiation actually instantiates and tries `DxBackend`
- strict/fallback behavior depends on real DirectX backend creation results
- the backend exposes a valid device (feature level >= 10.1) and swap-chain-backed
  surface path instead of being skipped up front

## Notes For Future Agents

- Treat the design spec and research docs as constraints, not inspiration-only reading
- Preserve the repo's current milestone discipline even when work lands on `dev`
- If a worktree or branch is only a scratch area, do not treat it as the canonical development branch
- Prefer adding or updating `docs/plans/` when a new major milestone starts
- Keep progress documentation evidence-based; distinguish implementation
  from build and runtime verification.