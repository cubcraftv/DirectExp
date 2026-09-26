// Milestone 4.1: Native Shader Toolchain Bring-Up.
// Milestone 4.3: IR Stage (Real GLSL -> SPIR-V).
//
// Scope (see docs/plans/2026-09-25-directexp-milestone4-breakdown.md's
// "Milestone 4.1" and "Milestone 4.3" sections): 4.1 proved that `shaderc`
// and `SPIRV-Cross` actually link and interoperate inside
// `directexp_native`, and that `d3dcompiler` links (see
// native/CMakeLists.txt), via one hardcoded smoke-test GLSL string with no
// JNI surface of its own. 4.3 is what actually exposes the GLSL -> SPIR-V
// step through JNI (see directexp_bridge_shader.cpp's
// `compileGlslToSpirv0`, which calls `CompileGlslToSpirv` below) so real
// preprocessed shader source from Milestone 4.2 can be compiled on demand
// instead of only the 4.1 smoke-test string. There is still no
// `D3DCompile` call anywhere in this file; that is Milestone 4.5 (HLSL
// Emission and D3D Compilation).
#pragma once

#ifdef _WIN32

#include <shaderc/shaderc.hpp>

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

namespace directexp {

// Result of one `shaderc` GLSL -> SPIR-V compile attempt. Used by both
// Milestone 4.1's hardcoded smoke test (`RunShaderToolchainSmokeTest`,
// which discards this after logging) and Milestone 4.3's real
// JNI-exposed compile entrypoint (`directexp_bridge_shader.cpp`'s
// `compileGlslToSpirv0`, which turns this into structured Java-visible
// diagnostics via `BuildIrCompilationResult` instead of letting a
// `shaderc` compile error surface as a raw native exception -- see the
// Milestone 4.3 checklist's "Surface shaderc compile errors ... as
// structured diagnostics" item).
struct GlslToSpirvResult {
	bool success = false;
	std::vector<uint32_t> spirv;
	std::string errorMessage;
	int numErrors = 0;
	int numWarnings = 0;
};

// Compiles `glslSource` (of the given `shaderc_shader_kind`, e.g.
// `shaderc_glsl_vertex_shader`/`shaderc_glsl_fragment_shader`) to SPIR-V
// via `shaderc`. `shaderName` is used only for `shaderc`'s own error
// messages (the "file name" it reports compile errors against, useful
// for a human reading the log) -- it is not looked up anywhere and does
// not need to correspond to a real Mojang `Identifier`.
//
// Never throws: a `shaderc` compile failure (syntax error, unsupported
// construct) comes back as `GlslToSpirvResult::success == false` with
// `errorMessage`/`numErrors`/`numWarnings` populated from `shaderc`'s own
// diagnostics, not a C++ exception -- this is the "the shader failed to
// compile" case the Milestone 4.3 checklist calls out as "a real, expected
// runtime case rather than a can't-happen".
GlslToSpirvResult CompileGlslToSpirv(shaderc_shader_kind kind, const std::string& glslSource, const std::string& shaderName);

// Compiles one small hardcoded GLSL fragment shader (not loaded from
// Minecraft, not representative of Mojang's real shaders -- that's
// Milestone 4.2, and real on-demand compiles are Milestone 4.3's
// `CompileGlslToSpirv`/`compileGlslToSpirv0` above) through `shaderc` to
// SPIR-V, then through SPIRV-Cross's `CompilerHLSL` back to HLSL text,
// and logs the GLSL input and HLSL output. Run automatically once per
// process from `JNI_OnLoad` (see this file's .cpp) rather than from a
// dedicated JNI entrypoint, since Milestone 4.1 deliberately has no JNI
// surface of its own.
//
// Failures (a `shaderc` compile error, a SPIRV-Cross translation
// exception) are logged and swallowed here rather than propagated -- there
// is no JNI call frame for this to throw back into yet, and a broken
// toolchain smoke test should not prevent `directexp_native.dll` from
// finishing its load and serving the existing probe/surface/texture
// entrypoints.
void RunShaderToolchainSmokeTest();

// Converts a `GlslToSpirvResult` into a
// dev.cubcraftv.directexp.bridge.NativeDxIrCompilationResult instance, for
// Milestone 4.3's `compileGlslToSpirv0` JNI entrypoint
// (directexp_bridge_shader.cpp). Keep the constructor signature this
// builds (`(I[BLjava/lang/String;II)V`) in sync with that record's fields.
// Returns nullptr (with a pending JNI exception) only on a JNI-level
// failure (e.g. the class/constructor could not be found), matching
// directexp_common.h's `BuildProbeResult`/`BuildSurfaceBootstrapResult`
// convention.
jobject BuildIrCompilationResult(JNIEnv* env, const GlslToSpirvResult& result);

} // namespace directexp

#endif // _WIN32
