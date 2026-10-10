# DirectExp

DirectExp is an experimental Fabric-delivered project that adds a native
DirectX 11 rendering backend to modern Minecraft on Windows. Its compatibility
goal is Minecraft versions **newer than 26.2**, running in a Windows desktop
window with a D3D11 device at feature level **10.1 or higher**.

DirectExp is an independent implementation, not a structural sibling or
shared codebase with MetalExp. MetalExp research informed some early
architectural investigations; DirectExp's renderer, native bridge, and
Minecraft integration are designed and maintained for DirectX 11 and Windows.

The backend is a first-class Blaze3D implementation: DirectExp owns the
graphics-API setting and startup backend negotiation, and provides the
`DxBackend` through Mojang's GPU backend interfaces.

**Status:** Backend negotiation, swap-chain presentation, GPU textures, draw
submission, and Sodium-oriented GPU buffer/texel-buffer paths are implemented
in source. Runtime validation on a D3D11 device remains outstanding. Check the
[progress log](docs/plans/2026-09-20-directexp-progress-log.md) and
[Sodium support plan](docs/plans/2026-10-06-directexp-sodium-support-plan.md)
for verification status; source implementation is not a claim of runtime
compatibility.

## Compatibility target

| Area | Target |
|---|---|
| Minecraft | Versions newer than `26.2`; Minecraft APIs evolve, so newer releases may require adaptation |
| Platform | Windows desktop with a window that exposes a Win32 `HWND` |
| Graphics API | Native Direct3D 11 / DXGI |
| Device support | D3D feature level `10_1` or higher; lower feature levels are rejected |

The Gradle properties pin Minecraft, Fabric, Loom, and Java inputs for the
current development build. Those build-time dependency versions are not the
project's compatibility target or a promise that only that exact version is
supported.

## Repository layout

```
src/main    - Fabric entrypoint, backend-agnostic bootstrap/config/diagnostics, bridge interfaces
src/client  - client-only entrypoint, DxBackend + Blaze3D backend implementations, settings UI, mixins
native/     - Win32 + D3D11/DXGI native bridge (C++, built via CMake, Windows-only)
docs/       - design spec, implementation plans/progress, and D3D11 research notes
reference/  - read-only Minecraft Blaze3D source and shader snapshots; not compiled
```

## Building

```
./gradlew build
```

On Windows, `build` also runs `buildNativeWindowsBridge` and requires CMake
plus a supported native Windows toolchain. On other operating systems the
native bridge task is skipped, while the Java build and unit tests can still
run. See [native/README.md](native/README.md) for local toolchain requirements.

```
./gradlew test
```

Unit tests are designed to be OS-independent and do not require the native
toolchain.

## License

MIT licensed (see [LICENSE](LICENSE)). Private repository for now.
