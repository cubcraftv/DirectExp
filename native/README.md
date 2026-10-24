# Native Bridge

The Windows native bridge is the C++/JNI implementation for DirectExp's
Win32, DXGI, and D3D11 backend. It resolves the GLFW host window to an
`HWND`, owns the D3D11 device/context and swap chain, manages native
resources and pipelines, and exposes shader/draw operations to the Java
backend.

The project compatibility target is Minecraft versions newer than 26.3 on
Windows desktop with a window-backed surface and D3D feature level 10.1 or
higher. Minecraft/Fabric/Loom coordinates in Gradle are pinned development
dependencies, not a fixed product target.

The native library currently includes device probing, swap-chain lifecycle,
resource creation and transfer, render-pass state and draw submission, and
the GLSL -> SPIR-V -> HLSL -> D3D shader path. Consult the project
[progress log](../docs/plans/2026-09-20-directexp-progress-log.md) for the
current build and runtime verification status.

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

The shader toolchain needs `SPIRV-Cross` from the UCRT64 repository.
`CMakeLists.txt` fetches and builds shaderc and its matching glslang,
SPIRV-Tools, and SPIRV-Headers dependencies from pinned upstream revisions:

```
pacman -S mingw-w64-ucrt-x86_64-spirv-cross
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

On Windows, `./gradlew.bat build` emits `build/native/directexp_native.dll`
and bundles it in the mod JAR at
`META-INF/native/windows-x86_64/directexp_native.dll`. At runtime, the
loader extracts the bundled DLL to a temporary file and loads it. During
development, `runClient` still sets `directexp.nativeLibraryPath` to load
the freshly built DLL directly instead.

## Why CMake

Windows development commonly uses either MSVC or MinGW-w64/MSYS2. CMake
abstracts compiler invocation and library-naming differences between these
toolchains (see the "bare library names" comment in `CMakeLists.txt`), so
contributors get normal CMake diagnostics instead of hardcoded-path failures.

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
