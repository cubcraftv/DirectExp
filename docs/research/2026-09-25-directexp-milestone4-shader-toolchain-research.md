# DirectExp Milestone 4 Toolchain Research Notes

Companion to `docs/research/2026-09-20-directexp-d3d11-research-notes.md`.
That file covers device/feature-level/swap-chain questions (Milestones 2-3).
This file covers the `shaderc` / `SPIRV-Cross` / `D3DCompile` toolchain
questions specific to Milestone 4, gathered from public documentation before
any of it has been wired into `native/CMakeLists.txt`. As with the existing
research doc, this is not a substitute for hands-on verification on the
Windows/MSYS2 UCRT64 environment this project actually builds on -- treat it
as a starting point for Milestone 4.1, not a finished answer.

`MetalExp` does not have a built shader toolchain either -- its
`26.2` Vulkan/Metal research notes cover the same `GLSL -> SPIR-V -> <target>`
decision at the design level, not a working implementation to port code
from. Nothing below assumes MetalExp code exists to copy.

## 1. `shaderc` (GLSL -> SPIR-V)

- MSYS2 UCRT64 (the toolchain this project already builds on, per the
  bootstrap plan's Progress Log) has a prebuilt package:
  `mingw-w64-ucrt-x86_64-shaderc`. It installs `libshaderc.a` /
  `libshaderc_combined.a` (static) and `libshaderc_shared.dll` (+import
  lib), plus the `shaderc/shaderc.hpp` C++ header and the standalone
  `glslc.exe` CLI. This means Milestone 4.1 almost certainly does **not**
  need to build shaderc from source via CMake `FetchContent` -- a single
  `pacman -S mingw-w64-ucrt-x86_64-shaderc` plus `find_package`/manual
  `target_link_libraries` against `shaderc_combined` should be enough to
  get a linkable library. This should be confirmed on the actual dev
  machine as the first concrete step of 4.1, and the required pacman
  package should be documented in `native/README.md` the same way the
  existing `gcc`/`g++`/`cmake`/`ninja` MSYS2 packages already are.
- The `shaderc.hpp` C++ wrapper (`shaderc::Compiler`, `shaderc::CompileOptions`,
  `CompileGlslToSpv`) is the natural fit given this project's existing
  "plain C++ native bridge" decision (see `AGENTS.md`'s Open Decisions) --
  no need to touch the lower-level C API.
- One upstream note worth flagging even though it shouldn't affect this
  project: recent `shaderc` releases mark **HLSL-as-input** compilation as
  deprecated (it depends on `glslang`'s HLSL front end, which upstream
  `glslang` is deprecating). This is irrelevant to DirectExp specifically
  because the pipeline only ever feeds `shaderc` **GLSL** input
  (`GLSL -> SPIR-V`); it never asks `shaderc` to compile HLSL. Worth a
  one-line comment in the 4.1 code so a future reader doesn't confuse this
  deprecation notice with a blocker.

## 2. `SPIRV-Cross` (reflection + SPIR-V -> HLSL)

- Also has a prebuilt MSYS2 UCRT64 package: `mingw-w64-ucrt-x86_64-spirv-cross`,
  currently tracking upstream KhronosGroup/SPIRV-Cross releases. Same
  implication as `shaderc`: 4.1 should try linking against the packaged
  library before reaching for `FetchContent`/vendoring.
- SPIRV-Cross exposes both a C++ API (`spirv_cross::CompilerHLSL`,
  `spirv_cross::CompilerReflection`) and a separate C API
  (`spirv_cross_c.h`, useful for language bindings). Given this project's
  native bridge is plain C++ throughout, the C++ API
  (`spirv_cross::CompilerHLSL` for stage 4.5, `spirv_cross::Compiler`'s
  resource-reflection surface for stage 4.4) is the natural fit -- no
  reason to route through the C API.
- `CompilerHLSL` has its own `HLSLOptions` struct (shader model target,
  point-size builtin handling, etc.) separate from the general
  `CompilerGLSL`-inherited options. Confirming the right `shader_model`
  value for Shader Model 5.0 (`vs_5_0`/`ps_5_0`, matching the feature-level
  10.1 floor from the existing research doc) is 4.5 work, not 4.1 --
  4.1 only needs to prove the library links and a trivial shader
  round-trips.

## 3. `D3DCompile` / `d3dcompiler` on MinGW-w64

- mingw-w64's CRT ships a generated `.def` file for `d3dcompiler_47.dll`
  (covering `D3DCompile`, `D3DReflect`, `D3DCreateBlob`, etc.), which is
  how the toolchain produces a linkable import library from a bare
  `-ld3dcompiler` the same way it already does for `d3d11`/`dxgi`. This is
  consistent with the plan's existing comment in `native/CMakeLists.txt`
  ("d3dcompiler is deliberately not linked yet ... revisit explicitly in
  Milestone 4") -- linking it should be a straightforward addition once
  4.1 starts, not a toolchain fight.
- **Runtime caveat worth a diagnostic, not just a build-time link:**
  unlike `d3d11.dll`/`dxgi.dll`, `d3dcompiler_47.dll` is only guaranteed
  preinstalled as an OS component on **Windows 8.1 and later**; on
  Windows 7/Vista it requires a separate redistributable. Since this
  project already targets "modern Windows" and gates on feature level
  10.1 (which in practice implies a reasonably modern OS too), this is
  unlikely to bite real users, but it's the kind of "why did loading fail"
  case the existing `DiagnosticsReporter`/`FailureMode` machinery is
  designed for -- worth a specific diagnostic message
  ("`d3dcompiler_47.dll` could not be loaded" vs. a generic native-load
  failure) rather than folding it into the generic DLL-load failure path,
  the same way feature-level rejection already gets its own distinct
  diagnostic per `AGENTS.md`'s "Where DirectExp Diverges From MetalExp"
  section.

## 4. What's still genuinely unknown (needs the actual Mojang source, not web research)

These can't be resolved by researching the libraries in isolation --
they need the decompiled/source reference for Mojang's current-line
Vulkan shaders, which the design spec already flags as the reflection
stage's real risk (`docs/specs/2026-09-20-directexp-design.md`, Shader and
Resource Binding Design):

- The exact preprocessing/define-injection Mojang's modern Vulkan path
  applies before compiling GLSL, so DirectExp's source stage matches it
  rather than inventing a divergent front end.
- Whether any of Mojang's current shaders use SPIR-V/GLSL features that
  SPIRV-Cross's HLSL backend historically has rough edges on (e.g. certain
  combined-image-sampler patterns, specific UBO layouts) -- this is the
  kind of thing that's only findable by actually running real shaders
  through the pipeline, not by reading SPIRV-Cross's docs.
- How Blaze3D's `RenderPipeline` resource declarations should be merged
  with SPIR-V reflection output into a single engine-owned layout model --
  this is a DirectExp-side design decision informed by both sources, not
  something either library documents for you.

These three are why Milestone 4.4 (Reflection and Resource Layout Model)
is called out as the stage most likely to eat the most time, consistent
with the design spec's risk note and MetalExp's own precedent.
