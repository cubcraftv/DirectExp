# Native Bridge

This directory is reserved for the Windows native bridge used by `DirectExp`.

Planned responsibilities:

- resolve the GLFW-owned window into its Win32 `HWND`
- create the `ID3D11Device` / `ID3D11DeviceContext` pair at feature level
  10.1 or higher (with a defined fallback/reject policy below that)
- create and manage an `IDXGISwapChain` bound to that `HWND`
- own core D3D11 object lifecycles (device, immediate context, swap chain,
  render target views)
- expose a small JNI bridge to the Fabric-side backend

Current scope:

- a minimal JNI-loadable stub library target named `directexp_native`
- a native probe entrypoint that:
  - creates a temporary `ID3D11Device` via `D3D11CreateDevice` with a
    feature-level request list capped at `D3D_FEATURE_LEVEL_11_1` and
    floored at `D3D_FEATURE_LEVEL_10_1`
  - reports the negotiated feature level back to Java
  - reports `DXGI_ADAPTER_DESC` identity (description string, vendor ID,
    dedicated video memory) for diagnostics
  - reports failure distinctly for "no D3D11 runtime", "no adapter
    reaching 10.1", and "device creation failed for another reason"
- no swap chain, no window attachment, no command list/draw submission yet
- (Milestone 4.1) `shaderc`/`SPIRV-Cross`/`d3dcompiler` linked, plus a
  `JNI_OnLoad`-triggered smoke test (`directexp_shader_toolchain.cpp`) that
  compiles one hardcoded GLSL string through `shaderc` to SPIR-V and
  through SPIRV-Cross's `CompilerHLSL` to HLSL text, logging both -- not
  yet wired to any real Mojang shader source, JNI entrypoint, or
  `D3DCompile` call (see the Milestone 4 breakdown doc for what those need)

## Local build requirements

The Gradle task `buildNativeWindowsBridge` only runs `onlyIf { isWindows }`
and shells out to `cmake -G Ninja ...`. Two toolchains are supported; pick
one. **Whichever you pick, you must run `./gradlew.bat build` from a shell
that already has that toolchain's tools on `PATH`** -- a plain PowerShell/
cmd prompt has neither by default, which is why "NMake Makefiles" / "no
such file" errors show up if you skip this.

The CMake/Ninja build tree is kept in `.gradle/native`, outside Gradle's
disposable `build/` directory. `./gradlew.bat clean` therefore removes the
final DLL and Java outputs without discarding the compiled Shaderc, glslang,
SPIRV-Tools, and native bridge objects. The next native build can reuse them.

### Option A: MSYS2 UCRT64 (MinGW-w64 GCC)

```
pacman -S mingw-w64-ucrt-x86_64-toolchain mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja
```

Milestone 4 (shader toolchain) additionally needs `shaderc` and
`SPIRV-Cross`, both available as prebuilt UCRT64 packages -- no need to
build either from source:

```
pacman -S mingw-w64-ucrt-x86_64-shaderc mingw-w64-ucrt-x86_64-spirv-cross
```

`d3dcompiler` needs no separate package on either toolchain (see "Known
toolchain-specific caveats" below) -- it's linked the same bare-name way
`d3d11`/`dxgi` already are.

Then run `./gradlew.bat build` from the **"MSYS2 UCRT64"** shell
specifically (not the plain "MSYS2" shell, and not a separate PowerShell
window without that shell's `PATH`) -- that's the one that puts
`ucrt64\bin` (containing `gcc.exe`, `g++.exe`, `cmake.exe`, `ninja.exe`)
first on `PATH`.

### Option B: MSVC (Visual Studio Build Tools)

Install the "Desktop development with C++" workload (includes the Windows
10/11 SDK, `d3d11.h`/`dxgi.h`/`d3dcompiler.h`, and the "C++ CMake tools for
Windows" component, which brings `ninja.exe`). Then open an **"x64 Native
Tools Command Prompt for VS"** (or run `vcvars64.bat` in your shell first)
and run `./gradlew.bat build` from there, so `cl.exe` and `ninja.exe` are
on `PATH`.

### Either way

`-G Ninja` is passed explicitly (see `build.gradle`) so the generator
choice doesn't depend on CMake's ambiguous Windows default -- it works the
same whether the discovered compiler is `cl.exe` or `gcc`/`g++`, as long as
`ninja` is on `PATH` in that shell.

On Windows, `./gradlew.bat build` emits `build/native/directexp_native.dll`.
Point the Java bridge loader at it during iteration with the
`directexp.nativeLibraryPath` system property (this is wired up
automatically for `runClient`).

## Why CMake instead of a raw compiler invocation

MetalExp calls `xcrun clang` directly because Apple's toolchain is
effectively fixed per-OS. On Windows there are two genuinely different,
both-legitimate toolchains (MSVC and MinGW-w64/MSYS2), so the native build
goes through CMake instead of a hardcoded compiler command line -- CMake
abstracts the compiler invocation and library-naming differences between
the two (see the "bare library names" comment in `CMakeLists.txt`), and a
future contributor building on their own machine gets a normal CMake error
message instead of a hardcoded-path failure.

## Known toolchain-specific caveats

- **MinGW-w64 / UCRT64**: `d3dcompiler` is now linked (Milestone 4.1) the
  same bare-name way as `d3d11`/`dxgi` -- mingw-w64's CRT ships a generated
  `.def` file for `d3dcompiler_47.dll` that produces a linkable import
  library, so this needed no extra pacman package. **Runtime caveat**:
  unlike `d3d11.dll`/`dxgi.dll`, `d3dcompiler_47.dll` is only guaranteed
  preinstalled as an OS component on Windows 8.1 and later; on older
  Windows it needs a separate redistributable.
  `NativeDxBridgeLoader.checkD3DCompilerAvailability` (Java side) pre-checks
  for this specific DLL before the native library load is even attempted,
  so a missing copy reports `DxBridgeLoadStatus.DEPENDENCY_MISSING` with an
  actionable message instead of a generic, confusing native-load failure.
- **MSVC**: if `D3D11_CREATE_DEVICE_DEBUG` / the debug layer is needed
  later, confirm the "Graphics Tools" optional Windows feature is installed
  (see the open research question in `docs/research/`).
