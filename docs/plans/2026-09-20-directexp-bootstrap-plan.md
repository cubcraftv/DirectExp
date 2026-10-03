## Progress Log

Add an entry here at the end of every working session. Keep entries short;
- **2026-09-20** (native output naming fix, same day): `buildNativeWindowsBridge`
  warned `directexp_native.dll was not found after the native build`. Cause:
  CMake's default shared-library naming differs by toolchain on Windows --
  MSVC produces `directexp_native.dll`, but MinGW-w64/GCC (MSYS2 UCRT64)
  follows the Unix convention and produces `libdirectexp_native.dll` unless
  told otherwise. This also would have broken
  `NativeDxBridgeLoader`'s `System.loadLibrary("directexp_native")` call at
  runtime even if the Gradle copy step had been made to tolerate the `lib`
  prefix, since Java's `loadLibrary` never adds that prefix on Windows.
  Fixed with `set_target_properties(directexp_native PROPERTIES PREFIX "")`
  in `native/CMakeLists.txt` so the output name is `directexp_native.dll`
  on both toolchains. **Not yet reconfirmed with a clean build** -- next
  session should re-run `./gradlew.bat build` and check for the file at
  `build/native/directexp_native.dll` before moving on to `runClient`.

- **2026-09-20** (Milestone 2 runtime verification, same day): `./gradlew.bat
  runClient` produced a real window -- solid black, correct size, with
  Minecraft's title-screen music and narrator audio playing, i.e. the game
  is genuinely running against the `DxBackend` path, not silently falling
  back to Vulkan/OpenGL. **This is Milestone 2's actual success
  criterion, met.** The project owner noted they can't yet pin down exactly
  which earlier step (a stale `build/native/` directory vs. an actual
  command mistake) caused the prior "no DLL produced" report, and will be
  more careful about clean-build state going forward -- worth keeping in
  mind if a similar "nothing was output" report shows up again: check
  whether `build/native/` is actually fresh before assuming a new code bug.

## Milestone 2: COMPLETE

Native swap chain lifecycle (device/context, flip-model swap chain, resize,
CPU-upload blit, present, release) is implemented, build-verified on
MSYS2 UCRT64, and now runtime-verified: the client reaches a real,
presenting DirectX-backed window. Black fill is expected and correct at
this stage -- `DxTexture`'s backing storage is zero-initialized and nothing
writes real pixel data into it yet (that's Milestone 3+ / actual rendering,
not a bug in what's implemented so far).

- **2026-09-25** (Milestone 4.1, authored in a non-Windows sandbox):
  Implemented the checklist in
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`'s Milestone
  4.1 section. `native/CMakeLists.txt` now links `shaderc_combined` and
  `spirv-cross-hlsl`/`spirv-cross-glsl`/`spirv-cross-core`, plus
  `d3dcompiler` (removing the old "deliberately not linked yet" comment);
  `native/README.md` documents the two new MSYS2 UCRT64 pacman packages.
  Added `native/src/directexp_shader_toolchain.{h,cpp}`: a smoke test that
  compiles one hardcoded GLSL fragment shader through `shaderc` to SPIR-V,
  then through SPIRV-Cross's `CompilerHLSL` to HLSL text, logging both via
  stderr/`OutputDebugStringA`. No JNI entrypoint for it yet, per the
  milestone's scope -- it runs automatically from a new `JNI_OnLoad` hook
  (fires as soon as the JVM loads `directexp_native.dll`, no Java-side
  change needed to trigger it). Also added the runtime-caveat diagnostic:
  `d3dcompiler_47.dll` is only a guaranteed Windows 8.1+ OS component, and
  since it's now statically linked, a missing copy would otherwise surface
  as a generic, confusing native-load failure for the whole bridge DLL.
  `NativeDxBridgeLoader.checkD3DCompilerAvailability` pre-checks for it at
  its well-known `%SystemRoot%\System32` location before attempting
  `System.loadLibrary`/`System.load` at all, reporting a new
  `DxBridgeLoadStatus.DEPENDENCY_MISSING` / `DxBridgeProbeStatus.RUNTIME_DEPENDENCY_MISSING`
  pair with an actionable message when it's absent. **Nothing in this
  entry has been build-verified**: this session ran in a Linux sandbox
  with no MSYS2/MSVC toolchain, `shaderc`, or `SPIRV-Cross` available, and
  no JDK (`javac`) either, so not even the plain-Java bridge changes could
  be compiled here -- only reviewed by hand against the existing style in
  `directexp_common.{h,cpp}` and `directexp_bridge_probe.cpp`. A new test,
  `NativeDxBridgeLoaderTest`, covers `checkD3DCompilerAvailability` in
  isolation (it doesn't touch `System.loadLibrary`) but has likewise not
  been run. **Next session on a Windows/MSYS2 UCRT64 machine should**:
  install the two new pacman packages, run `./gradlew.bat test` first
  (fast, no native build, confirms the Java-side changes at least compile
  and the new test passes), then `./gradlew.bat build` and confirm the
  Milestone 4.1 success criterion -- a log line showing GLSL going in and
  HLSL text coming out, visible on `runClient` startup via console or
  DebugView.
- **2026-09-25** (Milestone 4.1 Windows build verification, same day):
  On Windows 10 with MSYS2 UCRT64/GCC 16.2, the first CMake configure hit
  Shaderc/glslang's broken install export: `SPIRV` and `glslang` referenced
  `SPIRV-Tools-opt` without exporting it. Fixed this by disabling install
  rules for the in-tree dependency build and disabling Shaderc's unused,
  deprecated HLSL frontend; DirectExp accepts GLSL and uses SPIRV-Cross for
  SPIR-V -> HLSL. CMake then configured successfully and the native build
  produced `directexp_native.dll`. The CMake/Ninja tree was moved to
  `.gradle/native` so `./gradlew.bat clean` preserves Shaderc, glslang,
  SPIRV-Tools, and bridge object files; the cache path was reconfigured and
  confirmed to survive `clean`. At that point, the GLSL -> SPIR-V -> HLSL
  startup log and Java test suite still needed a fresh post-fix run; the
  suite subsequently passed, as recorded in the 2026-09-26 entry below,
  while the startup log remains outstanding.
- **2026-09-26** (Milestone 4.2, authored in a non-Windows sandbox):
  Implemented `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`'s
  Milestone 4.2 checklist (Source and Preprocessing Stage). New package
  `dev.cubcraftv.directexp.client.shader`: `DxShaderSourceStage` loads raw
  GLSL via `ShaderSource.get(id, type)` and preprocesses it via
  `GlslPreprocessor.injectDefines`, normalizing vertex and fragment
  through one code path. This session had `vulkan.zip` available -- a
  decompiled `com.mojang.blaze3d.vulkan` package for this project's own
  Minecraft version (`26.2-snapshot-1`) -- which resolved the shader
  toolchain research notes' "still genuinely unknown" preprocessing
  question by direct inspection of `VulkanDevice.compileShader` rather
  than by assumption; see that file's section 4 and the updated Milestone
  4.2 checklist for exactly what was confirmed. `DxShaderPipelineDescriptor`
  and `DxGlslDefinesInjector` exist specifically so the stage's core logic
  is unit-testable (`DxShaderSourceStageTest`) without a fully
  registry-backed `RenderPipeline` or a real `ShaderDefines`/
  `GlslPreprocessor` call -- see those classes' javadoc for why. Also
  extended `build.gradle`'s `test` source set to see `client`'s output and
  classpath, since this new code is necessarily client-only (it depends on
  `ShaderSource`/`RenderPipeline`) but the milestone requires it stay
  unit-testable without a Windows machine.
  **At the time this entry was written, nothing had been build- or
  test-verified**: this session had a JDK but no Fabric/Minecraft
  dependencies reachable (no
  network access to `maven.fabricmc.net` or Mojang's piston-meta from this
  sandbox), so `./gradlew test` could not be run here, only reviewed by
  hand against the decompiled Vulkan source and this repo's existing
  `DxBridgeLoadResult`/`DxBridgeLoadStatus`-style conventions. In
  particular, the exact shape of `ShaderSource` (whether it really is a
  single-method `get(Identifier, ShaderType)` interface, as every call
  site in the decompiled Vulkan source implies) has not been confirmed
  against real Minecraft classes here. **The remaining verification is**
  (per the Milestone 4.2 success criterion) to exercise
  `DxShaderSourceStage.load(RenderPipeline, ShaderSource)` against a real
  pipeline's `defaultShaderSource` for a representative shader (e.g.
  `core/position`) and log/compare the preprocessed output against what
  the Vulkan path produces for the same shader. The 2026-09-26 Progress
  Log entry below records the subsequently passing test/build verification.

- **2026-09-26** (Milestone 4.1/4.2 verification): `./gradlew test`
  passed, confirming the Java changes and current unit tests compile and
  pass. `./gradlew.bat buildNativeWindowsBridge --rerun-tasks` also passed
  on Windows, rebuilding the native bridge and exercising its post-build
  DLL copy. That copy had failed because the Groovy build script called
  Kotlin's unavailable `File.copyTo()` extension; it now uses Java NIO
  `Files.copy(..., REPLACE_EXISTING)`. These checks do not verify the 4.1
  GLSL -> SPIR-V -> HLSL startup smoke log or 4.2's preprocessed output
  against a live Mojang shader; those runtime checks remain outstanding.

- **2026-09-26** (Milestone 4.3 authored): implemented the IR stage's
  three checklist items --
  a real `compileGlslToSpirv0` JNI entrypoint
  (`directexp_bridge_shader.cpp`) built on a `CompileGlslToSpirv` helper
  factored out of 4.1's smoke test (which now calls it too, instead of
  duplicating the `shaderc` call), structured compile-error diagnostics
  end to end (`GlslToSpirvResult` -> `NativeDxIrCompilationResult` ->
  `DxIrCompilationResult` -> `DxIrCompilationStageResult`, keeping a real
  `shaderc` compile error distinct from a bridge-unavailable failure at
  every layer), and a `DxSpirvCache` keyed on
  `(Identifier, ShaderType, preprocessedSource)` so `DxIrCompilationStage`
  doesn't recompile an unchanged shader on every call. **At the time this
  entry was written, nothing had been build- or runtime-verified**: same
  sandbox constraint as the 4.2 entry above (no Fabric/Minecraft
  dependencies reachable, and additionally no MSYS2/MSVC Windows
  toolchain, `shaderc`, or `SPIRV-Cross` available here at all), so
  neither `./gradlew test` nor a native build could be run from this
  session. `DxIrCompilationStageTest` exercises the Java-side orchestration
  (ready path, vertex-compile-failure short-circuit, fragment-compile
  failure, bridge-unavailable, and two cache-hit/cache-miss cases) against
  a fake `DxBridge`, but that is necessarily a stand-in for real `shaderc`
  behavior, reviewed by hand against this repo's existing
  `DxBridgeProbe`/`DxShaderSourceStage` result-and-status conventions, not
  a substitute for running it. **The remaining verification is** (per the
  Milestone 4.3 success criterion) to run `./gradlew test` and
  `./gradlew.bat buildNativeWindowsBridge` on a real Windows/MSYS2 UCRT64
  machine, then exercise `compileGlslToSpirv0` against a representative
  preprocessed shader from 4.2 (e.g. `core/position`) and at least one
  deliberately-broken GLSL string, confirming the latter comes back as a
  structured `COMPILE_ERROR` result rather than crashing or throwing.

- **2026-09-26** (Milestone 4.1 runtime verification): `./gradlew.bat test`
  and `./gradlew.bat build` passed. `./gradlew.bat runClient` reached the
  DirectX backend on Windows and logged the complete native shader smoke
  test: shaderc accepted the hardcoded GLSL, produced 106 SPIR-V words,
  SPIRV-Cross emitted HLSL, and the GLSL -> SPIR-V -> HLSL round trip
  succeeded inside `directexp_native`. The same run logged the native GPU
  texture path as active. Milestone 4.1's runtime success criterion is now
  met. The live 4.2 representative-source comparison and 4.3 real JNI
  compilation/error exercise remain outstanding.

- **2026-09-27** (Milestone 4.4 authored): implemented both of the
  Reflection and Resource Layout Model's checklist halves. Native: a new
  `directexp_shader_reflection.h/.cpp` reflects SPIR-V via SPIRV-Cross's
  base `Compiler::get_shader_resources()`, exposed through a new
  `reflectSpirv0` JNI entrypoint added to the existing
  `directexp_bridge_shader.cpp` (kept in that file rather than a new
  `directexp_bridge_reflection.cpp`, since both are pure-CPU-side shader
  toolchain entrypoints with no device/surface dependency); GLSL's
  combined `sampler2D` is reflected as a paired `SAMPLED_TEXTURE`+
  `SAMPLER` entry under one engine name, anticipating the split 4.5's
  `CompilerHLSL` will need to perform. Java: `DxBridge.reflectSpirv`/
  `NativeDxBridge` (bridge package, mirroring `DxIrCompilationResult`'s
  shape) and `DxReflectionStage` (`client/shader`, mirroring
  `DxIrCompilationStage`'s per-stage short-circuit shape) reflect both
  pipeline stages; `DxResourceLayoutBuilder` (pure Java, no native call)
  then combines that with a `DxPipelineResourceDeclarations` (Blaze3D's
  own `getSamplers()`/`getUniforms()`) into the engine-owned
  `DxResourceLayout`, via a concrete, documented slot-assignment
  algorithm: merge vertex+fragment reflections by `(kind, name)`, sort
  each of the four `DxResourceKind` categories by name, assign `0`-based
  slots per kind independently. Declared-vs-reflected mismatches are
  logged, not treated as hard failures, per this milestone's own
  "manually cross-checked, not yet exercised through an actual draw call"
  success criterion. **At the time this entry was written, nothing had
  been build- or runtime-verified against real SPIRV-Cross**: same
  sandbox constraint as every 4.x entry above -- no MSYS2/MSVC Windows
  toolchain, `shaderc`, or `SPIRV-Cross` available here, and additionally
  no Fabric/Minecraft/Yarn sources or Maven Central access in this
  session to confirm `RenderPipeline.getSamplers()`/`getUniforms()`/
  `UniformDescription`'s exact shape against a decompile the way 4.2's
  `GlslPreprocessor.injectDefines` call was (flagged explicitly on
  `DxPipelineResourceDeclarations`'s javadoc as needing that confirmation
  in a live session). What *was* verified in this session, in lieu of a
  real `./gradlew test` run: all new and edited Java source (bridge
  package plus `client/shader`) was compiled with a real `javac` against
  hand-written stand-ins for the handful of external Minecraft/Blaze3D/
  slf4j types it touches (`Identifier`, `ShaderType`, `RenderPipeline`,
  `DirectExpMod.LOGGER`) -- confirming there are no Java-level type
  errors even though the Gradle/Fabric Loom build itself couldn't run --
  and `DxReflectionStageTest`/`DxResourceLayoutBuilderTest`'s 13 test
  methods were run for real (not just compiled) against a small
  hand-written JUnit-annotation-compatible runner, all passing. **The
  remaining verification is** (per the Milestone 4.4 success criterion)
  to run `./gradlew.bat test` and `./gradlew.bat buildNativeWindowsBridge`
  on a real Windows/MSYS2 UCRT64 machine, then exercise `reflectSpirv0`
  against a representative shader's real SPIR-V from 4.3 (e.g.
  `core/position`) and manually cross-check the resulting
  `DxResourceLayout` against that shader's source by eye, plus confirm
  `RenderPipeline.getSamplers()`/`getUniforms()` against a real decompile
  of `26.2-snapshot-1`.

- **2026-09-27 (later same day)** (Milestone 4.5 authored): implemented
  all four of "HLSL Emission and D3D Compilation"'s checklist items.
  Native: a new `directexp_shader_hlsl.h/.cpp` (third toolchain-concern
  file, alongside `directexp_shader_toolchain.h/.cpp` for `shaderc` and
  `directexp_shader_reflection.h/.cpp` for SPIRV-Cross reflection)
  translates SPIR-V to HLSL via `CompilerHLSL` (`shader_model = 50`) and
  compiles it via `D3DCompile` -- the first `D3DCompile` call anywhere in
  this project -- exposed through a new `emitAndCompileHlsl0` JNI
  entrypoint added to `directexp_bridge_shader.cpp`. Each resource in
  Milestone 4.4's `DxResourceLayout` is forced into the emitted HLSL as an
  explicit register via SPIRV-Cross's `add_hlsl_resource_binding`, keyed
  on each resource's own SPIR-V `(stage, desc_set, binding)` decorations
  (looked up via `get_decoration`) rather than by name -- a combined GLSL
  `sampler2D` (reflected in 4.4 as two same-named entries) is merged back
  into one `HLSLResourceBinding` with both its SRV and Sampler register
  set. Small 4.4 refactor along the way: the resource-naming fallback
  (`ResourceName`) moved out of `directexp_shader_reflection.cpp` into a
  new shared `directexp_shader_resource_naming.h`, since 4.5 needs to
  derive the exact same name for the exact same resource to look up its
  slot assignment, and a second, independently-written copy risked
  drifting out of sync with the first. Java: `DxResourceSlotAssignment`/
  `DxHlslCompilationStatus`/`DxHlslCompilationResult`/
  `NativeDxHlslCompilationResult` (bridge package) and
  `DxHlslModule`/`DxHlslPipelineModules`/`DxHlslCompilationStageStatus`/
  `DxHlslCompilationStageResult`/`DxHlslCompilationStage` (`client/shader`,
  mirroring `DxReflectionStage`'s per-stage short-circuit shape, but with
  two failure statuses per stage instead of one, since emission and
  compilation can each fail independently). Also fixed a real bug surfaced
  while wiring 4.5 to 4.4: `DxResourceLayout.find(String)` was ambiguous
  for a combined sampler's two same-named entries; added a kind-aware
  `find(String, DxResourceKind)` overload (old one kept, now documented as
  ambiguous for that case) and used it throughout 4.5's own code.
  **Verification note, and what made this session different from every
  4.x session before it:** this sandbox reached
  `raw.githubusercontent.com` and fetched SPIRV-Cross's real, current
  upstream `spirv_hlsl.hpp`/`spirv_cross.hpp`/`spirv_common.hpp`/`spirv.hpp`
  directly from `https://github.com/KhronosGroup/SPIRV-Cross`, and checked
  `HLSLResourceBinding`'s exact field names, `CompilerHLSL::Options`,
  `add_hlsl_resource_binding`'s signature, `set_hlsl_options`, the
  `ExecutionModel`/`Decoration` enums' actual namespace (`spv::`, *not*
  `spirv_cross::` -- a real mistake caught and fixed during this session
  by that check, not merely a hypothetical one), and `Resource`/
  `ShaderResources`/`get_decoration`/`get_declared_struct_size`'s
  signatures (the last two also retroactively confirming 4.4's existing
  reflection code, written before this check, is correct) against that
  real header rather than against recollection. `D3DCompile`'s own
  signature was not fetched during authoring (Microsoft's Win32 headers
  aren't on GitHub the way SPIRV-Cross is); it was instead independently
  confirmed later the same day when the person driving this session
  pasted Microsoft's official documented signature and it was checked
  argument-by-argument against the actual call in
  `directexp_shader_hlsl.cpp` -- all eleven parameters (order and type)
  matched exactly, no code changes needed. What was *not* possible here, same as every
  other 4.x entry: an actual MSYS2/MSVC/`D3DCompile` compile, or running a
  real shader's SPIR-V through this path. Java-side compile/test
  verification followed the same procedure as 4.4's entry above: all new
  and edited Java compiled cleanly under a real `javac` against the same
  hand-written external-type stand-ins, and all 19 tests across
  `DxReflectionStageTest`/`DxResourceLayoutBuilderTest`/
  `DxHlslCompilationStageTest` (13 carried over from 4.4, 6 new) passed
  under the same hand-written runner. **Remaining verification** (per the
  4.5 success criterion): a real Windows/MSYS2 build, running a
  representative shader's real SPIR-V through `emitAndCompileHlsl0`, and
  confirming the resulting HLSL/bytecode by eye (or via `D3DReflect`)
  against the 4.4 layout it was given.

- **2026-09-27 (addendum to the "(later same day)" 4.5 entry above):**
  the person driving this session pasted Microsoft's official documented
  `D3DCompile` signature. Checked argument-by-argument against the actual
  call in `directexp_shader_hlsl.cpp`: all eleven parameters (`pSrcData`,
  `SrcDataSize`, `pSourceName`, `pDefines`, `pInclude`, `pEntrypoint`,
  `pTarget`, `Flags1`, `Flags2`, `ppCode`, `ppErrorMsgs`), in that order,
  with matching types -- confirmed exact, no code changes needed. Updated
  that entry above and the 4.5 checklist's success-criterion note (in
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`) to say this
  was independently confirmed rather than "not similarly re-checked". This
  closes the one specific gap that entry flagged in how `D3DCompile` was
  verified (SPIRV-Cross's API already had upstream-header confirmation);
  it does not change the entry's other conclusion that nothing has
  actually been compiled or run on a real Windows/MSVC toolchain yet.

- **2026-09-28 -- Milestone 4.5 runtime-confirmed; Milestone 4.6 commit 1
  authored (GPU buffer / sampler / SRV backing).** The project owner
  reported 4.5 built on a Windows/MinGW-w64 toolchain with a clean
  `runClient` (no errors); this supersedes the 4.5 "native verification
  outstanding" status bullets below. 4.6 planning is captured in
  `docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md`, which is
  the source of truth for 4.6 decisions and the facts read from the decompiled
  Vulkan backend. Commit 1 (prerequisite scope the owner approved) gives
  `DxBuffer` a real `ID3D11Buffer` mirror, `DxSampler` a real
  `ID3D11SamplerState`, and `DxTextureView` a real
  `ID3D11ShaderResourceView`, via new `DxBridge`/`NativeDxBridge` methods and
  `native/src/directexp_bridge_resource.cpp`. **Verification actually done
  (Linux sandbox):** Java compiled with `javac` 21 against Minecraft stubs;
  19 unit tests pass under a stand-in runner (not real Gradle/JUnit); the
  native files pass `-fsyntax-only` under `x86_64-w64-mingw32-g++` with real
  mingw-w64 headers. **Not done:** Gradle build, native link, `runClient`.
  Nothing binds these resources yet, so `runClient` should still show a black
  fill plus one `native GPU buffer/sampler/texture view path active` log line
  each (see section 6.5 of the source-of-truth doc).

- **2026-09-28 (later) -- Milestone 4.6 commit 1 runtime-confirmed; commit 2
  authored (render/depth targets + pipeline objects).** Owner reported commit 1
  built and `runClient` clean with `native GPU buffer path active` and `native
  GPU sampler path active` lines (no texture-view line; cause undetermined, see
  source-of-truth 6.5). Owner decisions: implement RTV/DSV in commit 2; uniform
  slices use staging buffer + copy. Commit 2 adds `createTexture` (RGBA8 +
  D16/D24S8/D32/D32S8 with RENDER_TARGET/DEPTH_STENCIL/SHADER_RESOURCE binds),
  RTV/DSV creation in `createTextureView`, `createPipeline`/`releasePipeline`
  (VS/PS/input layout/blend/depth-stencil/rasterizer), `DxPipelineStateMapping`,
  `DxPipelineCompiler`, and a real `DxDeviceBackend.precompilePipeline` with an
  identity-keyed cache. **Verification actually done (Linux sandbox):** Java
  compiled against Minecraft stubs together with the real 4.2-4.5 stage
  classes; 32 unit tests pass under a stand-in runner; all native files
  `-fsyntax-only` clean under mingw-w64 with `static_assert`s pinning D3D11/DXGI
  enum numbers to the real headers. **Not done:** Gradle build, native link,
  `runClient`. Expect many "pipeline ... is invalid" warnings on first run
  (4.3 has no vertex-attribute location injection; see source-of-truth 7.6/8).
  Details: `docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md`.

- **2026-09-28 (Milestone 4.6 commit 3 -- shader interface layout, fixes the
  all-pipelines-invalid bug commit 2 predicted).** Owner supplied the
  decompiled `com.mojang.blaze3d.vulkan.glsl` package (missing from the
  original `vulkan.zip`) plus the actual `runClient` logs, confirming every
  pipeline failed with `GLSL->SPIR-V: VERTEX_COMPILE_FAILED`: our
  `directexp_shader_toolchain.cpp` compiles with default `shaderc` options,
  but Mojang's `#version 330` GLSL has no explicit vertex-attribute
  locations or uniform/sampler bindings, which Vulkan-semantics `shaderc`
  requires. Fix: a new stage, `DxShaderInterfaceLayout`, spliced into
  `DxPipelineCompiler` between the 4.2 source stage and 4.3 IR stage,
  rewrites the preprocessed GLSL text with explicit `layout(location=N)`/
  `layout(binding=N)` following the exact numbering the decompiled
  `GlslCompiler`/`IntermediaryShaderModule` use (vertex input = vertex
  format element index; varyings matched vertex-output-to-fragment-input by
  name; uniform blocks/samplers share one binding space, vertex stage
  first), plus the `#extension`s Vulkan-semantics GLSL needs for those
  layouts under `#version 330` and the same `gl_VertexID`/`gl_InstanceID`
  defines Vulkan injects. Also added the one real native change this
  investigation surfaced: `shaderc::CompileOptions::SetGenerateDebugInfo()`
  in `directexp_shader_toolchain.cpp` (matching Vulkan's own option),
  needed to keep SPIR-V debug names alive for the existing name-based 4.4
  reflection / 4.5 HLSL-binding lookups. The four questions the 2026-09-28
  (later) entry above raised are now answered in
  `docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md` section
  9: (a) attribute-name-to-location is fully decompiled and replicated; (d)
  the exact shaderc options `GlslCompiler` sets are listed, and which ones
  DirectExp does/doesn't need to copy and why; (b) Y-flip is confirmed via
  the decompile (absent from the `glsl` package) plus independently-checked
  facts about D3D11/Vulkan/OpenGL NDC conventions to be a **Vulkan-only**
  concern -- `FrontCounterClockwise = TRUE` (GL's CCW convention) was
  already correct and stays unchanged; (c) clip-space depth remap is the
  one question still genuinely open (D3D11 and Vulkan agree with each
  other here, unlike Y, so the reasoning that resolved (b) doesn't resolve
  (c) too) -- flagged for an empirical check at commit 4's first real draw
  rather than guessed at. **Verification actually done (Linux sandbox, no
  `shaderc`/Gradle):** `DxShaderInterfaceLayout` compiles under real `javac`
  21 and passes 16 unit tests under the existing stand-in runner; separately,
  five samples shaped like real vanilla pipelines (GUI/textured/particle/
  full-screen-quad/flat-varying) were rewritten by it and fed to a real
  `glslangValidator --target-env vulkan1.0`, which accepted all ten
  rewritten stages with zero errors (vs. the original text's errors,
  matching the owner's log) -- and `spirv-dis`/`spirv-cross` confirmed the
  resulting location/binding numbers and vertex-output-to-fragment-input
  HLSL semantic matching are correct. **Not done:** an actual `shaderc`
  compile of the rewritten text (only `glslangValidator` was available),
  and nothing past IR (reflection/HLSL/`D3DCompile`/a real draw) exercised
  against real vanilla shader source. Also noticed, not fixed (out of
  scope for this commit): one `native GPU buffer upload failed;
  updateBufferNative: range is outside the buffer or the mirror.` warning
  in the same log, once at startup -- see source-of-truth section 8 item 6.
  Details: `docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md`
  section 9.

- **2026-09-28 (later same day) -- Milestone 4.6 commit 3 follow-up: real
  bugs surfaced now that shaders actually compile.** Owner ran commit 3
  through `runClient` and reported "looks like preprocessing is not
  working," with a fresh `debug.log`/`latest.log`. It was working -- none of
  the three `shaderc` location/binding error kinds from the earlier entry
  appear anywhere in the new log, and `DxShaderInterfaceLayout`'s own debug
  line shows up exactly where expected. All 174 `pipeline ... is invalid`
  lines in the new log are different, later-stage failures: 148x
  `ID3D11Device::CreateVertexShader failed.`, 14x `vertexStride must be
  positive.`, 6x the already-known `TRIANGLE_FAN` gap, 2x an HLSL
  `Unsupported builtin` for a `gl_PointSize` shader. Fixed the first two
  (86% of the failures): (1) `directexp_shader_hlsl.cpp` hardcoded Shader
  Model 5.0 (`vs_5_0`/`ps_5_0`, `shader_model=50`) regardless of the
  negotiated D3D11 feature level, but this project's own accepted floor,
  `D3D_FEATURE_LEVEL_10_1`, only guarantees Shader Model 4.1 (confirmed
  against Microsoft Learn's `D3D_FEATURE_LEVEL` docs) -- `D3DCompile`
  doesn't care and happily produced SM5 bytecode anyway, but
  `CreateVertexShader` on a 10.1 device correctly refused to create a
  shader object from it, for every pipeline uniformly. Fixed by threading
  the real device's negotiated feature level (previously computed then
  discarded in `BootstrapSurface`) into a new `g_activeFeatureLevel` global,
  and branching both `ToD3DCompileTarget` and `EmitHlsl`'s `shader_model` on
  it. (2) `DxPipelineStateDescriptor` rejected any `vertexStride <= 0`
  unconditionally, but several vanilla pipelines (`blit`, `flat_clouds`,
  `animate_sprite_blit`, `lightmap`, `entity_outline_blit`, `tracy_blit`)
  legitimately have an empty vertex format (no vertex buffer, positions
  computed from `gl_VertexIndex`) and so a legitimate stride of 0 -- fixed
  to only require a positive stride when there's at least one attribute to
  stride through. **Verification:** the feature-level branch logic (not the
  whole native files, which need `shaderc`/SPIRV-Cross headers this sandbox
  lacks) was compiled standalone against real mingw-w64 `d3d11.h` and run
  under Wine -- all four feature-level cases assert correctly; the
  `vertexStride` fix has 5 new passing unit tests under `javac` 21. **Not
  done:** an actual Windows build/`runClient` of the native diff, a fix for
  the still-open `TRIANGLE_FAN`/`gl_PointSize` gaps (owner decisions
  needed), and logging the real negotiated feature level in
  `DxDeviceBackend`'s startup log line (currently a hardcoded string that
  made this bug harder to spot from the log alone -- noted as a suggested
  follow-up, not implemented). Details:
  `docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md` section
  10.

- **2026-09-30 -- Milestone 4.6 log analysis after the commit 3 follow-up
  (diagnosis only, no code changed).** Owner supplied fresh `runClient`
  logs (14:28 and 14:46) and asked for a report, then for docs. The
  follow-up is confirmed at runtime: no `CreateVertexShader failed` or
  `vertexStride` failures remain, and native buffers/samplers/textures/views
  and the first `gui` pipeline all report active. Six pipelines are still
  invalid, which makes Minecraft's `ShaderManager` throw `Failed to load
  required shader programs` and drop all resource packs on every reload
  (this comes from Minecraft treating those programs as required, not from
  `failureMode=STRICT`, which only affects backend negotiation). Causes:
  `sky`/`sunrise_sunset`/`debug_triangle_fan` -- `TRIANGLE_FAN` still
  unimplemented; `debug_points` -- `gl_PointSize` (SPIR-V builtin 1) has no
  HLSL output; `end_portal`/`end_gateway` -- a **new** bug: Mojang's
  preprocessor leaves `/*#moj_import ...*/#line N M` on one line, and
  `DxShaderInterfaceLayout.tokenize` only recognises `#` directives at a
  line start (a block comment clears that flag), so `in vec3 Position;` is
  merged into a garbage statement and silently never gets a
  `layout(location = ...)`. **Verified:** reproduced on the real class
  compiled under JDK 21 (only the Minecraft-dependent `apply()` stripped),
  with a control run confirming a newline before `#line` makes
  `Position` get its location. **Not done:** any fix. Proposed (not
  applied): let block comments preserve `atLineStart`, make `analyze` fail
  loudly on a stray `#` token, add a regression test with the exact log
  shape; separately, sync `logicalSize` instead of the 16-byte-padded
  `capacity()` in `DxBuffer`'s constructor (likely cause of the
  once-per-run `updateBufferNative: range is outside the buffer` warning,
  from code reading only). Fixing the tokenizer clears 2 of the 6 failing
  pipelines; `TRIANGLE_FAN` and `gl_PointSize` still need owner decisions
  before the required-shader check can pass. Details:
  `docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md`
  section 11.

## Current Status (as of the last entry above)

- **Runtime-verified on Windows (MSYS2 UCRT64):** Milestones 1, 2, and 3 all
  confirmed working end to end: config -> plan -> negotiation -> settings UI
  -> `DxBackend` -> real D3D11 device/context -> flip-model swap chain ->
  presenting window, with the GPU-texture path (`createTexture2DNative` /
  `uploadTextureRgba8Native` / `blitSurfaceTexture0`) confirmed active via
  the `native GPU texture path active` diagnostic line.
- **Still black on screen, but that's Milestone 4's gap, not Milestone 3's:**
  the window shows a black fill because `DxRenderPassBackend.draw()`/
  `drawIndexed()` are still no-ops -- nothing issues real GPU draw work yet.
  That's expected at this milestone boundary; it does not indicate the
  texture upload/blit path is CPU-only, which the diagnostic line above
  rules out.
- **Milestone 4.1 (shader toolchain bring-up) runtime-verified:** the native
  configure/build, persistent cache, and Java test suite are confirmed on
  Windows/MSYS2 UCRT64, and `runClient` logged the complete GLSL -> SPIR-V
  -> HLSL startup smoke test on 2026-09-26. See the 2026-09-26 Progress Log
  entry and
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`. This does not
  change the black-fill status above -- 4.1 is infrastructure wiring plus
  a standalone smoke test, not a draw call.
- **Milestone 4.2 (source/preprocessing stage) unit-tested; live shader
  comparison outstanding:** `DxShaderSourceStage` and its supporting types
  pass `./gradlew test` as of 2026-09-26. Real preprocessed output has not
  yet been checked against the Vulkan path for a live shader. See the
  2026-09-26 Progress Log entry above. This stage is not wired into
  `DxDeviceBackend.precompilePipeline` yet -- that's later Milestone 4 work
  (4.3+), once there's an IR stage for it to feed into.
- **Milestone 4.3 (IR stage/real GLSL -> SPIR-V) Java-side build-verified,
  native integration still outstanding:** `./gradlew.bat test` and
  `./gradlew.bat build` pass, covering the structured result chain and
  `DxSpirvCache`/`DxIrCompilationStage` unit tests. The real
  `compileGlslToSpirv0` call has not yet been exercised against a
  representative preprocessed shader or a deliberately broken GLSL input,
  and this stage is not wired into
  `DxDeviceBackend.precompilePipeline` either yet -- same reason as 4.2's
  bullet above, now one stage further along.
- **Milestone 4.4 (reflection and resource layout model) authored,
  compiled and unit-tested outside Gradle; native and live-decompile
  verification outstanding:** `DxReflectionStage`/`DxResourceLayoutBuilder`
  and their supporting bridge/client types compile cleanly under a real
  `javac` and their 13 unit tests pass under a hand-written runner (this
  session had no Fabric/Minecraft dependencies or Maven Central access to
  run the real `./gradlew test`). The real `reflectSpirv0` call has not
  been exercised against real SPIR-V, `RenderPipeline.getSamplers()`/
  `getUniforms()` has not been confirmed against a decompile, and this
  stage is not wired into `DxDeviceBackend.precompilePipeline` yet -- same
  reason as 4.2/4.3's bullets above, now one stage further along. See the
  2026-09-27 Progress Log entry above and
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`.
- **Milestone 4.5 (HLSL emission and D3D compilation) authored, compiled
  and unit-tested outside Gradle; native verification outstanding:**
  `DxHlslCompilationStage` and its supporting bridge/native types compile
  cleanly under a real `javac`/(recollection-independent, upstream-checked)
  read of SPIRV-Cross's real headers, and 19 unit tests across all three
  of 4.4/4.5's stage test classes pass under a hand-written runner. The
  real `emitAndCompileHlsl0` call -- SPIRV-Cross HLSL emission followed by
  the project's first ever `D3DCompile` call -- has not been exercised
  against real SPIR-V, and this stage is not wired into
  `DxDeviceBackend.precompilePipeline` yet -- same reason as every 4.x
  bullet above, now one stage further along, one milestone item (4.6)
  short of Milestone 4's actual finish line. See the "2026-09-27 (later
  same day)" Progress Log entry above and
  `docs/plans/2026-09-25-directexp-milestone4-breakdown.md`.
- **Milestone 4.6 commit 1 runtime-verified on Windows; commit 2 authored,
  build/`runClient`-unverified; commit 3 authored and fixes the bug commit
  2's own runtime check (correctly) predicted:** commit 1 (GPU-backed
  buffer/sampler/texture-view objects) is confirmed on Windows/MSYS2
  UCRT64. Commit 2 (render/depth targets, real `createPipeline`, a real
  `DxDeviceBackend.precompilePipeline`) compiles and unit-tests outside
  Gradle only; running it was expected to -- and, per the owner's actual
  `runClient` log, did -- produce a `pipeline ... is invalid:
  GLSL->SPIR-V: VERTEX_COMPILE_FAILED` warning for every one of the 86
  vanilla pipelines Blaze3D tried, because Mojang's GLSL has no explicit
  vertex-attribute locations or uniform/sampler bindings and our
  `shaderc` call used default options. Commit 3 fixes this: see the
  2026-09-28 Progress Log entry above and
  `docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md`
  section 9 for the fix, the four questions it answers about the Vulkan
  backend's own shader-compile behavior, and what was and wasn't possible
  to verify without a Windows `shaderc`/Gradle build. Commit 3 *was* then
  run through `runClient`, confirming the shader-interface fix works
  (no more location/binding errors) and surfacing two further,
  previously-hidden bugs now that shaders compile far enough to reach real
  pipeline creation: a Shader-Model-5.0-vs-feature-level-10.1 mismatch
  (148 of 174 failures) and an over-strict `vertexStride` check rejecting
  legitimate zero-vertex-format pipelines (14 of 174) -- both fixed in the
  "commit 3 follow-up" Progress Log entry above and source-of-truth section
  10. Milestone 4 is still not complete -- none of commit 3 or its
  follow-up has been run through the project's own Windows build, two
  smaller gaps remain open (`TRIANGLE_FAN`, an HLSL `gl_PointSize`
  translation failure -- recommended implementations are in source-of-truth
  section 8 items 2 and 7), and commit 4 (clears, viewport/scissor,
  resource binding, and the first real draw) has not started.
  *Update 2026-09-30 (14:28/14:46 runs):* the follow-up held at
  runtime, and six pipelines
  remain invalid -- the two known gaps above plus a newly diagnosed
  tokenizer bug behind `end_portal`/`end_gateway` (see the 2026-09-30
  Progress Log entry and source-of-truth section 11; not yet fixed).
  *Follow-up before the 17:58 run:* the tokenizer bug is now fixed and
  covered by a regression test; the focused layout test and full
  `./gradlew.bat test` pass. A fresh
  `runClient` has not yet confirmed that the two End pipelines recover.
  Recommended next work: expand triangle fans to triangle-list indices in
  draw submission, and preserve `debug_point`'s `LineWidth` with optional
  geometry-shader quad expansion. Neither is implemented; see source-of-
  truth section 11.3.
  *17:58 run follow-up:* the log reports no invalid End, triangle-fan, or
  debug-point pipelines, but seven empty-vertex-format pipelines fail the
  native `vertexStride` check. The C++ guard now matches Java's contract
  (zero stride allowed only with no attributes); Windows `./gradlew.bat
  build` and `./gradlew.bat test` pass. A post-fix `runClient` remains
  outstanding. Fan and point-size implementations are now present and
  tested; actual draw output is not yet runtime-verified. Details:
  `docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md` sections
  11.3 and 11.6.

- **2026-10-01 -- 4.6 commit 4 (draw path) authored.** Render/depth target
  binding, clears, viewport/scissor, pipeline + vertex/index buffer binding,
  constant-buffer staging, SRV/sampler binding per the 4.4 layout, and real
  `Draw`/`DrawIndexed` (including fan expansion) now go through the native
  bridge (`native/src/directexp_bridge_draw.cpp`, `DxResourceBinder`,
  `DxDrawScratch`, `DxRenderPassBackend`). The 11.4 buffer-sync bug is fixed.
  `drawMultipleIndexed` is **not** implemented: it needs
  `RenderPass.Draw`/`UniformUploader` from the decompiled Blaze3D, so batched
  draws (likely chunk terrain) stay no-ops with a one-time warning. Verified
  in the authoring sandbox only: new Minecraft-free helpers under `javac` 21
  (13 new tests, stand-in runner) and the native file under mingw-w64
  `-fsyntax-only -Wall -Wextra`; the Blaze3D-facing classes were not compiled.
  **Not** built through Gradle or run in `runClient`. Open assumptions
  (scissor origin, ARGB vs ABGR clear color, clip-space depth) are listed in
  source-of-truth section 12.4. Render-target readback is the next, separate
  commit.

- **2026-10-02 -- 4.6 commit 4 follow-up after first `runClient`.** The GUI
  (logo, text, F3 background) renders through the new draw path. Using the
  decompiled Vulkan backend as reference: implemented `drawMultipleIndexed`,
  corrected the scissor convention (Blaze3D is already top-left; no Y flip),
  and compile pipelines on first use from the device's default `ShaderSource`
  (fixes the never-compiled `mojang_logo`/`blur/*`). Not yet compiled against
  the real Blaze3D types or run. Still open: cubemap panorama, texel buffers
  (clouds), render-target readback, clear-color packing, clip-space depth. See
  source-of-truth section 13.

- **2026-10-02 -- Main-menu visual priorities.** Owner reports the initial
  clear appears blue instead of red, button labels render without their
  backgrounds, and the panorama is absent. Added
  `docs/plans/2026-10-02-directexp-milestone4.7-main-menu-visuals.md` to
  prioritize clear-color correctness, button backplates, then six-face
  cubemap support, with runtime acceptance checks for each. The supplied
  Vulkan decompile is `C:\Users\user\projects\vulkan.zip`; its cube-view
  contract is summarized in the new plan. These three outcomes are not yet
  verified.

- **2026-10-02 -- Main-menu visual fixes implemented.** `DxTexture.fillRegion`
  now converts packed ARGB explicitly to RGBA8 bytes; pure-red/pure-blue
  regression coverage was added. Missing-source shader pipelines are deferred
  until Mojang clears the pipeline cache on resource reload, preventing the
  textured GUI pipeline from being retried/logged each frame; permanent
  compile failures remain cached. Cubemap usage now creates six-layer,
  cube-compatible D3D11 textures and `TEXTURECUBE` SRVs, with per-layer CPU
  storage and uploads forwarded from Blaze3D's `writeToTexture` layer index.
  Native upload validates the mip, layer, dimensions, and layered buffer size.
  The focused `DxTextureTargetsTest` passes (10 tests), client compilation and
  the Windows native bridge build pass, and a fresh `runClient` reached GUI
  atlas creation without the old per-frame missing-source spam, panorama
  missing-view warning, or JVM crash. The log has one early splash-time source
  miss, then none after resource reload. Pixel-level screenshot confirmation
  of the red clear and button backplates remains outstanding; the 12:34 run
  log shows no post-reload invalid textured pipeline.

- **2026-10-02 -- Splash logo startup shader fallback.** The 16:43 client log
  confirmed `minecraft:pipeline/mojang_logo` first requests
  `minecraft:core/position_tex_color` before the resource-backed `ShaderSource`
  is ready. Deferred compilation cannot recover the splash's one-shot draw.
  `DxShaderSourceStage` now falls back to the snapshot's import-expanded
  `position_tex_color` vertex/fragment sources only when the provider returns
  null; provider/resource-pack sources still take precedence. The focused
  `DxShaderSourceStageTest` passes. A fresh client run and first-logo screenshot
  are still needed to verify the runtime result.

- **2026-10-02 -- GUI atlas SRV/upload diagnostics.** Fixed `DxTexture.writeRegion`
  to read from the input `ByteBuffer.position()`, made missing-SRV diagnostics
  pipeline-qualified and specific about absent bindings, closed/non-native views,
  or failed SRV creation, and made native upload-failure logging once per texture
  rather than once per process. Added regression tests for positioned writes and
  per-pipeline missing-texture reports. Focused tests and `./gradlew.bat test`
  pass. `runClient` created `minecraft:textures/atlas/gui.png-atlas` (1024x1024)
  but produced no draw-time SRV/upload diagnostics before the client stopped
  advancing; the run was terminated, so the atlas root cause remains unconfirmed.

- **2026-10-03 -- GUI copy-pass binding diagnostics.** The one-time GUI atlas
  composition log reports the `Sprite` SRV and sampler slots/stage masks,
  bound handles, and source view/texture. Texture descriptions include the last
  native `UpdateSubresource` return/failure state. A follow-up review of the
  09:20 `runClient` log found the first logged draw sampled
  `minecraft:hud/heart/withered_full`; this is the first sprite processed while
  Minecraft builds the GUI atlas, not evidence of a wrong binding for the
  button. The log was misleading because the one-shot diagnostic recorded the
  first copy draw before the button, and the CPU-mirror sample was zero because
  GPU render-target writes are not mirrored into CPU storage. The diagnostic
  now waits for the `minecraft:widget/button` source before logging and no
  longer treats the atlas CPU mirror as evidence. No binding/shader behavior
  changed; the actual black-button cause remains unconfirmed pending a new
  `runClient` log/screenshot.

- **2026-10-03 -- Native texture names for RenderDoc correlation.** The 1.46
  capture's XML shows the GUI-atlas draw binds a non-null t0 SRV, but its
  captured D3D resource IDs could not be mapped to the Java-side native
  pointer handles or labels. `DxTexture` now passes its debug label through
  `DxBridge`; the native bridge applies D3D11 debug names to textures and
  their SRV/RTV/DSV views, including dimensions and mip ranges. Java tests and
  the full Windows `./gradlew.bat build` (including native DLL compilation)
  pass. A fresh `runClient` capture is still needed to confirm RenderDoc shows
  names such as `minecraft:widget/button [SRV mips 0..0]` at t0.

- **2026-10-03 -- GUI black-button investigation paused for release migration.**
  The `10-59-32.rdc` capture was correlated with the 10:59:32 run log. It
  contains the GUI atlas as the render target, and the normal-button copy draw
  binds `minecraft:widget/button [SRV mips 0..0]` at pixel-shader t0 immediately
  before drawing six vertices. The capture also has separate highlighted and
  disabled button-source draws. This rules out a wrong-source binding for the
  captured normal-button copy, but does not establish its sampled/output color
  or the final atlas pixel history; the black-button cause remains open.
  Defer further GUI diagnosis until after moving off the `26.2-snapshot-1`
  development target. Resume with RenderDoc pixel history and shader output
  inspection on the release build.

- **2026-10-03 -- Retargeting from snapshot to stable Minecraft release.**
  The Mojang version manifest identifies `26.3` as the latest release. The
  project is being moved from `26.2-snapshot-1` to `26.3`, with matching stable
  Fabric Loader, Loom, and Fabric API versions. Revalidate and repair
  Minecraft-facing integration against the release APIs before resuming the
  deferred GUI investigation.
