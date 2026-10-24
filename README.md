# DirectExp

DirectExp is an experimental Fabric-delivered project that adds a native
DirectX 11 rendering backend to modern Minecraft on Windows.

The backend is a first-class Blaze3D implementation: DirectExp owns the
graphics-API setting and startup backend negotiation, and provides the
`DxBackend` through Mojang's GPU backend interfaces.

**Status:** Done, beta testing. Not in active development, because my potato can now run minecraft without "GLFW Error 65542" thank to this mod, also see [Direcium](https://github.com/cubcraftv/Direcium) for sodium compatible.

## Compatibility target

| Area | Target |
|---|---|
| Minecraft | Versions newer than `26.3` |
| Platform | Windows desktop with a window that exposes a Win32 `HWND` |
| Graphics API | Native Direct3D 11 / DXGI |
| Device support | D3D feature level `10_1` or higher; lower feature levels are rejected |

**Only use this mod if your device not supporting OpenGL or Vulkan or encounting GLFW Error 65542 and similar.**

**Vulkan backend is already good or even better with sodium installed.**

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

MIT licensed (see [LICENSE](LICENSE)).
