# DirectExp Design Spec

## Summary

`DirectExp` is an experimental, private Fabric mod project that aims to add
a native DirectX 11 rendering backend to modern Minecraft builds on Windows,
targeting the Minecraft 26.2 release APIs and feature level 10.1 hardware at
minimum.

The project is explicitly **not** a lightweight compatibility patch. It is a
backend program delivered in mod form, structurally mirroring the `MetalExp`
sibling project:

- the user-facing graphics API setting is replaced by a `DirectExp`-owned setting
- the startup backend negotiation path is overridden by mixins
- the renderer itself is implemented as a first-class backend stack
- the project includes a native Windows bridge for Win32 and D3D11/DXGI APIs

The current target is the modern Blaze3D architecture in Minecraft `26.2`
(release). Initial backend research was done against `26.2-snapshot-1`;
the release retarget requires active maintenance of Minecraft-facing code.

## Goals

- Deliver a real native DirectX 11 backend, not a translation/emulation layer
- Support feature level 10.1 as the hardware floor, so the mod runs on a
  meaningfully broader hardware population than a DX11.1/11.2-feature-gated
  backend would
- Keep the project structurally close to a typical Fabric mod project so the
  Minecraft-facing layer remains approachable
- Use the new backend seams in Blaze3D instead of scattering rendering hacks
  across unrelated codepaths
- Provide a clean user-visible graphics API choice: `DirectX`, `Vulkan`, or `OpenGL`
- Build strong diagnostics and failure reporting into the project from the start
- Stay structurally consistent with `MetalExp` wherever DirectX 11 does not
  force a different answer, so both projects remain easy to cross-reference

## Non-Goals

- D3D12, or a D3D12-backed implementation presented as "native DirectX 11"
- Treating WARP (Microsoft's software rasterizer) as a supported hardware tier
- Broad compatibility with other rendering mods in the first release line
- Immediate support for non-Windows platforms
- Immediate feature parity with every edge case in Mojang's OpenGL and Vulkan backends
- A direct dynamic extension of Mojang's `PreferredGraphicsApi` enum
- Public distribution in the first phase (private repository; revisit if scope changes)

## Product Positioning

`DirectExp` is intended, at minimum, for its own author's use as a technical
experiment, with the same honesty MetalExp commits to if it is ever shared
further:

- Windows only
- Minecraft-version-sensitive
- renderer-mod incompatible unless explicitly documented otherwise
- diagnostics-first support model
- feature level 10.1 is the hard floor; devices below it are rejected with a
  clear, distinct diagnostic rather than a generic failure

## Background

Initial research on Minecraft `26.2-snapshot-1` (validated against the
`MetalExp` reference project in this same workspace) showed Blaze3D's
explicit backend boundary. The active target is now Minecraft 26.2 release;
its checked-in decompile and assets are the source of truth for current API
and shader behavior:

- `GpuBackend`
- `GpuDeviceBackend`
- `GpuSurfaceBackend`
- `CommandEncoderBackend`
- `RenderPassBackend`

This is the same boundary MetalExp targets. The DirectX 11 backend sits
alongside `GlBackend`, `VulkanBackend`, and `MetalBackend` (if that mod is
also installed) as one more `GpuBackend` implementation. See
`docs/research/2026-09-20-directexp-d3d11-research-notes.md` for feature
level and shader-route specifics.

## User Experience

### Graphics API Selection

`DirectExp` replaces the original graphics API option with a project-owned
option. The user should see a direct and explicit choice:

- `DirectX`
- `Vulkan`
- `OpenGL`

The project does **not** attempt to dynamically inject a `DIRECTX` constant
into Mojang's enum. Instead:

- the original option is removed or hidden from the effective settings UI
- `DirectExp` inserts its own replacement option
- the real source of truth is stored in `DirectExp` configuration
- startup backend negotiation reads the `DirectExp` configuration and ignores
  the original option for actual backend selection

### Failure Modes

The project must support at least two runtime behaviors:

- `fallback` mode: if `DirectX` fails, continue with `Vulkan` and then `OpenGL`
- `strict` mode: if `DirectX` fails, abort with a precise error

`fallback` is friendlier for users. `strict` is better for development,
triage, and reproducibility. A device that fails the feature-level-10.1
floor check must produce a specific diagnostic distinguishing it from "no
D3D11 runtime at all" and from "device creation failed for an unrelated
reason" -- this distinction matters for triage on unfamiliar hardware.

## Architecture

### Top-Level Layout

- `src/main` and `src/client`
  - Fabric entrypoints
  - mixins
  - mod resources
  - configuration wiring
- `shared/`
  - backend selection models
  - diagnostics payloads
  - capability summaries
  - shared error types
- `native/`
  - Win32 bridge
  - `IDXGISwapChain` management
  - D3D11 device/context/resource lifecycle
  - JNI boundary
- `docs/`
  - design specs
  - research
  - implementation plans
  - debugging notes

### Logical Modules

#### 1. Bootstrap Layer

Responsibilities:

- Fabric initialization
- client-only initialization
- configuration loading
- diagnostics initialization
- settings UI replacement
- startup backend negotiation hooks

This layer should stay small. It should not contain renderer business logic.

#### 2. Shared Model Layer

Responsibilities:

- `BackendKind` model such as `DIRECTX`, `VULKAN`, `OPENGL`, optional future `AUTO`
- diagnostics schemas
- capability summaries (including negotiated D3D feature level)
- backend failure and fallback descriptions
- shared configuration DTOs

This layer exists to prevent the Fabric bootstrap layer and the renderer
backend from tightly coupling to each other.

#### 3. Java Backend Layer

Responsibilities:

- `DxBackend`
- `DxDeviceBackend`
- `DxSurfaceBackend` (wraps the `IDXGISwapChain` lifecycle)
- `DxCommandEncoderBackend`
- `DxRenderPassBackend`
- `DxBuffer`
- `DxTexture`
- `DxTextureView`
- `DxSampler`

This layer must speak Blaze3D semantics upward and native bridge semantics
downward.

#### 4. Shader/Toolchain Layer

Responsibilities:

- shader source lookup
- preprocessing and define injection
- GLSL to SPIR-V compilation (`shaderc`, same as MetalExp)
- SPIR-V reflection
- resource layout construction
- SPIR-V to HLSL translation (SPIRV-Cross)
- HLSL compilation to D3D11 shader bytecode (`D3DCompile` / DXC targeting
  `ps_5_0`/`vs_5_0` shader model, which feature level 10.1 devices support)
- shader/pipeline cache keys

This is one of the two highest-risk parts of the project and must remain
structurally isolated, matching MetalExp's stance on its own shader layer.

#### 5. Native Bridge Layer

Responsibilities:

- GLFW window to Win32 `HWND` extraction
- `IDXGISwapChain` creation and lifecycle (including resize/present)
- `ID3D11Device` / `ID3D11DeviceContext` creation, gated at feature level 10.1
- HLSL shader bytecode -> `ID3D11VertexShader` / `ID3D11PixelShader` creation
- input layout / pipeline state object creation
- back buffer acquisition
- command submission via the immediate context (deferred contexts are an
  explicit later-milestone decision, not assumed up front)
- `IDXGISwapChain::Present`

This layer is a formal subsystem, not a bag of helper calls.

## Minecraft Integration Strategy

### Why Not Extend Mojang's Enum

Same rationale as MetalExp: dynamically adding `DIRECTX` to
`PreferredGraphicsApi` creates avoidable fragility around enum constant
arrays, codecs and config serialization, switch tables, option instance
behavior, and snapshot-to-snapshot maintenance. Replacing the setting at the
UI and configuration layer produces cleaner product semantics with much less
runtime fragility.

### Integration Entry Points

#### Settings Replacement

- remove or suppress the original graphics API option
- insert a `DirectExp` graphics API option
- store the result in `DirectExp` configuration

#### Startup Negotiation

At startup:

- read `DirectExp` configuration
- compute backend trial order
- attempt `DxBackend` first when the user chose `DirectX`
- apply `fallback` or `strict` failure policy
- if the user chose `Vulkan` or `OpenGL`, defer to the corresponding original
  backend path as much as possible

#### Window and Surface Bring-Up

The project should keep Minecraft's existing window ownership and host
abstraction. `DirectExp` only takes over the backend-specific surface path:

- use the existing GLFW window handle
- resolve the Win32 `HWND` through the native bridge (a single handle --
  unlike Cocoa, DXGI does not need a separate "view" handle)
- create an `IDXGISwapChain` bound to that `HWND`
- build `DxGpuSurface` around the swap chain's back-buffer/present lifecycle

#### Renderer Initialization

Once `DxBackend.createDevice(...)` succeeds:

- return a valid `GpuDevice` implementation
- pass it through normal renderer initialization
- avoid further Mixin-based rendering patches whenever possible

## Shader and Resource Binding Design

### Chosen Route

`GLSL -> SPIR-V -> HLSL`, kept deliberately parallel to MetalExp's
`GLSL -> SPIR-V -> MSL` route:

- aligns with the existing Mojang Vulkan-era shader story
- reuses the engine's current source and preprocessing expectations
- produces a stable intermediate representation shared with the Metal backend's route
- SPIRV-Cross's HLSL backend is mature and widely used (it is, notably, part
  of how Proton/DXVK-adjacent and Vulkan-portability tooling handles the
  reverse direction; the SPIR-V -> HLSL direction used here is a
  well-trodden path in game engine tooling generally)

### Shader Pipeline Stages

#### 1. Source Stage

- load Mojang shader source from the game's shader source path
- inject the same or equivalent preprocessing defines used by the modern Vulkan path
- normalize stage-specific input before compilation

#### 2. IR Stage

- use `shaderc` to compile source into SPIR-V
- treat SPIR-V as the canonical intermediate representation, same as MetalExp

#### 3. Reflection and Binding Stage

- inspect SPIR-V resources
- combine reflection results with Blaze3D pipeline declarations
- build an engine-owned resource layout model (shared conceptually with
  MetalExp's layout model, even though the concrete Metal/HLSL slot mapping
  differs)

#### 4. HLSL Emission Stage

- translate SPIR-V to HLSL via SPIRV-Cross, targeting Shader Model 5.0
  (`vs_5_0`/`ps_5_0`), which is the shader model feature level 10.1 devices
  support
- compile HLSL to bytecode via `D3DCompile` (or precompile via DXC's legacy
  HLSL front end if SM 5.0 output is targeted; final choice is an
  implementation-time decision, not a design blocker)
- build structures that map the engine-owned layout model into concrete
  D3D11 register slots (`b#` constant buffers, `t#` shader resource views,
  `s#` samplers)

### Binding Model

D3D11 has no argument buffers and no root signatures -- only flat
constant-buffer/SRV/sampler/UAV register-slot binding via the immediate
context's `PSSetConstantBuffers` / `VSSetShaderResources` / etc. family of
calls. This means the engine-managed slot map MetalExp chose for its own
first version is, if anything, a more natural fit for D3D11 than it is for
Metal (which does have argument buffers as an alternative it deliberately
avoided in v1).

The layout model must distinguish at least:

- constant buffers (`cbuffer` / `b#` slots)
- shader resource views for sampled textures (`t#` slots)
- samplers (`s#` slots, note D3D11 separates sampler state from the texture
  itself, same as Metal and Vulkan, unlike GL's combined sampler model)
- structured/typed buffer resources where the pipeline needs them

The important architectural decision, shared with MetalExp, is that the
project owns an intermediate layout model. It must not permanently encode
Vulkan descriptor-set assumptions into the DirectX backend.

### Pipeline Compilation

`DxRenderPipeline`-equivalent state should own:

- vertex and pixel shader handles (`ID3D11VertexShader`, `ID3D11PixelShader`)
- an `ID3D11InputLayout` derived from the vertex format
- render target formats
- depth/stencil state (`ID3D11DepthStencilState`)
- blend state (`ID3D11BlendState`)
- rasterizer state (`ID3D11RasterizerState`)
- resource layout references
- stable cache keys

Pipeline caching must key on actual render state, not only shader identity,
same as MetalExp.

## Command Encoding, Synchronization, and Presentation

### Core Principle

The project should not try to reproduce Vulkan behavior mechanically. It
should preserve Blaze3D-visible semantics while allowing the DirectX backend
to absorb D3D11-specific execution details -- most notably that D3D11's
immediate context already serializes and defers actual GPU work the way the
driver sees fit, which is a meaningfully different execution model from
Vulkan/Metal's explicit command buffers.

### Command Encoding Model

`DxCommandEncoderBackend` should be the central frame execution coordinator.
It should manage:

- immediate-context command issuance (a deferred-context / command-list
  model is an explicit future decision, not assumed for v1)
- render target / depth-stencil binding transitions between passes
- draw submission
- output stage coordination
- present scheduling

### Render Pass Mapping

Because modern Minecraft is already using a dynamic-rendering style on the
Vulkan path, `RenderPassBackend` maps onto a D3D11 "bind render targets, set
state, draw, unbind" sequence reasonably directly:

- resolve attachments and clear/load/store semantics into explicit
  `ClearRenderTargetView` / `ClearDepthStencilView` calls plus
  `OMSetRenderTargets`
- bind pipeline state objects and resources
- issue draw calls
- leave the render-target binding in a well-defined state at pass end

### Synchronization Strategy

The first implementation should be conservative, matching MetalExp's own
stance:

- single immediate context, no deferred contexts/command lists in v1
- render and resource-upload work ordered explicitly by call order (D3D11's
  immediate context model makes this the default behavior, not something
  DirectExp has to build)
- swap chain present encoded at the end of the frame
- correctness first, optimization later; multithreaded command recording via
  deferred contexts is an explicit, later, opt-in decision

### Surface and Output Path

Same as MetalExp: the engine renders primarily into internal render targets
and only later emits the final image to the presentable surface (the swap
chain back buffer).

Advantages:

- weaker coupling between scene rendering and surface presentation
- easier debugging and capture (e.g. via PIX or RenderDoc's D3D11 support)
- better alignment with resize and swap-chain-rebuild scenarios (D3D11
  requires releasing all back-buffer-derived views before calling
  `ResizeBuffers`, so keeping presentation as a distinct late step matters
  even more here than it does for Metal)

## Native Bridge Requirements

The native bridge is required, not optional.

It must support:

- Win32 interop for the GLFW-owned host window (`HWND` resolution)
- `IDXGISwapChain` creation, resize, and lifecycle
- D3D11 device and immediate-context creation, gated at feature level 10.1
- HLSL bytecode compilation and shader object creation
- pipeline state object creation (input layout, blend/depth/rasterizer state)
- back buffer acquisition and `Present`
- explicit error reporting back to Java, including a distinct code path for
  "feature level below floor" vs. other failures

The project should prefer a small, disciplined JNI surface instead of
leaking large quantities of low-level D3D11/DXGI interaction into Java, same
discipline MetalExp applies to its Objective-C boundary.

## Configuration

The project owns its own configuration file rather than treating Mojang's
original option as the runtime source of truth.

At minimum the config should include:

- selected backend: `directx`, `vulkan`, `opengl`
- failure policy: `fallback`, `strict`
- diagnostics level
- optional future capability gating toggles (e.g. an explicit opt-in to
  allow feature-level-10.0 devices with reduced functionality, should that
  ever become a project goal -- it is not one today)

The UI should reflect this config, and startup must consume it directly.

## Diagnostics and Supportability

Diagnostics are a core feature of the project, same as MetalExp.

The project must log or export:

- selected backend and fallback decisions
- Windows and GPU capability summary, including the negotiated D3D feature
  level and adapter identity (`DXGI_ADAPTER_DESC`)
- shader compilation phase failures (GLSL->SPIR-V, SPIR-V->HLSL, and
  HLSL->bytecode failures reported as distinct phases)
- pipeline compilation failures
- swap chain creation/resize/present failures
- startup negotiation transcript

## Testing Strategy

### Unit Tests

Cover:

- configuration parsing
- backend selection logic
- fallback policy
- cache keys
- resource layout mapping

### Toolchain Tests

Cover:

- shader preprocessing
- GLSL to SPIR-V compilation
- SPIR-V to HLSL translation
- reflection outputs
- layout generation

### Native Integration Tests

Windows-only; cannot run in a non-Windows CI/dev environment. Cover:

- feature level negotiation and floor rejection
- device/context creation
- swap chain creation against a real `HWND`
- minimal pipeline creation
- back buffer acquisition and present

### Minecraft Integration Tests

Cover:

- startup to main menu
- entry into a world
- basic UI rendering
- basic 3D rendering
- resize handling (including the `ResizeBuffers` view-release requirement)
- clean shutdown

## Compatibility Policy

Same as MetalExp: the first version does not promise compatibility with
other deep rendering mods. Intended support target is a plain Fabric
environment, non-renderer mods on a best-effort basis, no promise for
Sodium/Iris-equivalent render-stack rewrites.

## Versioning and Maintenance Strategy

Because Minecraft's backend APIs can change between releases, the codebase
must separate:

- Minecraft-version-sensitive adaptation code
- backend core code
- shader/toolchain core code
- native bridge code

This keeps release retargeting work concentrated in the adaptation layer
instead of destabilizing the entire renderer. Since `gradle.properties`
intentionally tracks the same `minecraft_version` as MetalExp, retargeting
both projects together (when the user chooses to) keeps their Blaze3D
assumptions from silently drifting apart.

## Milestones

### Milestone 1: Project Bootstrap (this scaffolding session)

- stable Fabric scaffold
- project configuration model
- diagnostics bootstrap
- settings replacement scaffolding
- startup negotiation scaffolding

### Milestone 2: Native Surface Bring-Up

- Win32 `HWND` extraction
- feature-level-gated D3D11 device/context creation
- `IDXGISwapChain` attachment
- back buffer acquisition, clear, present

### Milestone 3: Resource Core

- buffers
- textures
- samplers
- uploads
- blit support
- basic frame lifecycle

### Milestone 4: Shader and Pipeline Core

- GLSL to SPIR-V to HLSL pipeline
- reflection
- layout mapping
- first pipeline compile
- first successful draw

### Milestone 5: Rendering Viability

- menu rendering
- world entry
- depth and stencil support
- texture correctness
- basic ongoing stability

### Milestone 6: Experimental Release (public or private)

- fallback and strict modes
- issue-oriented diagnostics export
- installation documentation
- version support statement
- triage templates (even for a private repo, worth having for future-self)

## Success Criteria

- users can select `DirectX` in the replaced graphics API UI
- the game can start on supported Windows systems with a feature-level-10.1+
  device and initialize a native DirectX 11 backend
- devices below feature level 10.1 fail with a specific, actionable
  diagnostic rather than a generic crash
- the client can reach the main menu and enter a world with visible rendering
- failures produce actionable diagnostics
- the backend is implemented through the backend boundary, not by broad
  rendering-time patching

## Risks

### High Risk

- shader binding and resource layout correctness across the SPIR-V -> HLSL
  translation step
- D3D11's implicit synchronization model producing subtly different
  ordering bugs than the explicit command-buffer models Blaze3D's Vulkan
  path assumes
- Minecraft-version-driven hook drift

### Medium Risk

- pipeline cache invalidation mistakes
- swap chain recreation during resize (the `ResizeBuffers` view-release
  requirement is an easy source of leaks/crashes if not handled carefully)
- JNI boundary design becoming too chatty or too low-level
- feature-level-10.1 hardware having meaningfully different driver quality
  than more modern feature levels, surfacing bugs late

### Lower but Still Relevant Risk

- underestimated maintenance cost even for a private, single-maintainer project
- toolchain friction (MSVC/Windows SDK availability) slowing iteration
  compared to MetalExp's more uniform Apple toolchain story

## Open Decisions

These are intentionally left as project decisions, not design gaps:

- whether to introduce `AUTO` in the first configuration schema
- how much capability probing is exposed in the UI versus diagnostics only
- whether the native bridge is implemented in C++, or a mixed C++ plus C JNI
  boundary (current scaffold uses plain C++)
- whether to ever revisit the feature-level-10.1 floor (down to 10.0, or up
  to 11.0/11.1) once real-world diagnostics data exists
- deferred-context / command-list adoption for multithreaded recording, once
  the single-immediate-context model's limits are actually felt

None of these decisions block project bootstrap.
