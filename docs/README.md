# DirectExp Documentation

DirectExp is an independent native Direct3D 11 backend for Minecraft versions
newer than 26.2 on Windows desktop, using a window with a Win32 `HWND` and
devices at D3D feature level 10.1 or higher. MetalExp research informed some
early exploration; it is not a structural sibling or implementation template.

The backend includes Win32/DXGI surface management, D3D11 device and resource
lifecycles, shader translation and draw submission. Current implementation
and validation status is recorded in the
[progress log](plans/2026-09-20-directexp-progress-log.md) and individual
[plans](plans/).

The Gradle configuration currently pins a Minecraft/Fabric/Loom dependency
set for compilation. These values are development inputs, not the project's
compatibility target; Minecraft-facing code must be maintained as supported
Minecraft APIs evolve.

## Local build requirements

The Gradle task `buildNativeWindowsBridge` only runs `onlyIf { isWindows }`
and shells out to `cmake -G Ninja ...`. Two toolchains are supported; pick
one. **Whichever you pick, you must run `./gradlew.bat build` from a shell
that already has that toolchain's tools on `PATH`** -- a plain PowerShell/
cmd prompt has neither by default, which is why "NMake Makefiles" / "no
such file" errors show up if you skip this.

### Option A: MSYS2 UCRT64 (MinGW-w64 GCC)

```
pacman -S mingw-w64-ucrt-x86_64-toolchain mingw-w64-ucrt-x86_64-cmake mingw-w64-ucrt-x86_64-ninja
```

Milestone 4 (shader toolchain) additionally needs `SPIRV-Cross`, available
as a prebuilt UCRT64 package:

```
pacman -S mingw-w64-ucrt-x86_64-spirv-cross
```

`shaderc` is **not** installed from a package. MSYS2's prebuilt
`mingw-w64-ucrt-x86_64-shaderc` doesn't actually bundle glslang/SPIRV-Tools
object code into `libshaderc_combined.a` the way the upstream build does
(see the "Vendored shaderc" comment block in `CMakeLists.txt` for the full
story), so it fails to link. Instead, `CMakeLists.txt` builds shaderc (and
the matching pinned glslang/SPIRV-Tools/SPIRV-Headers versions) from source
via `FetchContent` as part of this project's own configure step. That
needs two extra things on `PATH` in your MSYS2 UCRT64 shell:

```
pacman -S git mingw-w64-ucrt-x86_64-python
```

(`git` for `FetchContent` to check out the pinned commits; Python for
shaderc's own build-version-header generation step.) The first configure
will clone ~3 repos (needs network access) and the first build will take
noticeably longer than before, since glslang + SPIRV-Tools + shaderc are
now all compiled from source rather than downloaded prebuilt. Later
configures/builds reuse the checked-out sources and only recompile what
changed, as usual.

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

On Windows, `./gradlew.bat build` emits
`build/native/directexp_native.dll` and bundles it in the mod JAR at
`META-INF/native/windows-x86_64/directexp_native.dll`. Packaged runs extract
the bundled DLL before loading it; `runClient` uses the freshly built file
directly through the `directexp.nativeLibraryPath` system property.

## Why CMake

Windows development commonly uses either MSVC or MinGW-w64/MSYS2. CMake
abstracts compiler invocation and library-naming differences between the
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