# DirectExp

Experimental Fabric-delivered backend project that adds a native DirectX 11
rendering backend to Minecraft on Windows, targeting the 26.2 release APIs
and feature level 10.1 devices at minimum.

This is a structural sibling of `MetalExp` (the same approach applied to
Apple's Metal API on macOS): rather than patching Mojang's OpenGL/Vulkan
renderer, `DirectExp` replaces the graphics-API setting, overrides startup
backend negotiation, and implements a first-class `DxBackend` against the
same Blaze3D backend boundary (`GpuBackend` / `GpuDeviceBackend` /
`GpuSurfaceBackend` / `CommandEncoderBackend` / `RenderPassBackend`).

**Status: Milestones 1-3 and the native texture path are runtime-verified on
Windows (MSYS2 UCRT64). Milestone 4 shader/pipeline work is underway; the
26.2 release retarget still needs runtime verification.** See
`docs/plans/2026-09-20-directexp-bootstrap-plan.md` for exactly what's real
vs. stubbed, and its Progress Log for session-to-session history.

## Target

| | |
|---|---|
| Minecraft version | `26.2` (release) |
| Fabric Loader | `0.19.5` |
| Fabric Loom | `1.18.2` |
| Gradle | `9.8.0` |
| Fabric API | `0.161.0+26.2` |
| Java | 25 |
| Graphics API | DirectX 11, feature level **10.1 minimum** (10.0 devices are rejected, not silently downgraded) |
| Platform | Windows only |

## Why feature level 10.1, not just "DirectX 11"

DirectX 11 *devices* can be created at several feature levels (9.1 through
11.1), which mostly determine which older/newer GPU generations are
supported, not which D3D11 API surface is used. This project uses the
D3D11 API throughout but floors hardware support at feature level 10.1 --
broader than requiring "true" 11.0/11.1 hardware, while still ruling out
the oldest (10.0/9.x) tier. See the design spec's Goals section for the
full rationale.

## Repository layout

```
src/main    - Fabric entrypoint, backend-agnostic bootstrap/config/diagnostics, bridge interfaces
src/client  - client-only entrypoint, DxBackend + Blaze3D backend implementations, settings UI, mixins
shared/     - reserved for cross-cutting model types (currently a placeholder)
native/     - Win32 + D3D11/DXGI native bridge (C++, built via CMake, Windows-only)
docs/       - design spec, bootstrap plan + progress log, research notes
```

## Building

```
./gradlew build
```

`build` also runs `buildNativeWindowsBridge`, which is a Windows-only task
(`onlyIf { isWindows }`) that shells out to `cmake`. On any other OS it's a
no-op so the Java side still builds and tests fine. See `native/README.md`
for the exact local toolchain requirements (CMake + MSVC or clang-cl +
Windows SDK).

```
./gradlew test
```

Unit tests are OS-independent and do not require the native toolchain.

## Author / license

Author: `cubcraftv`. MIT licensed (see `LICENSE`). Private repository for
now; see the design spec's Product Positioning section if that changes.

## Multi-session workflow

This project is worked on across multiple sessions/agents. Before doing
anything else in a new session:

1. Read `AGENTS.md` (working agreement, non-goals, where this diverges from MetalExp)
2. Read `docs/plans/2026-09-20-directexp-bootstrap-plan.md`'s Progress Log
   (bottom of the file) for what the last session actually finished
3. Read `docs/specs/2026-09-20-directexp-design.md` for anything touching
   architecture

Update the Progress Log before ending your own session.