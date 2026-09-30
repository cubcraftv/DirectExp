// Milestone 4.5: HLSL Emission and D3D Compilation.
//
// Scope (see docs/plans/2026-09-25-directexp-milestone4-breakdown.md's
// "Milestone 4.5" section): translate one shader stage's SPIR-V (from
// Milestone 4.3) to HLSL via SPIRV-Cross's `CompilerHLSL`, targeting
// Shader Model 5.0 (`vs_5_0`/`ps_5_0`, matching the feature-level-10.1
// floor -- see the D3D11 research notes and 4.1's smoke test, which
// already picked `shader_model = 50` for the same reason), then compile
// that HLSL to D3D11 shader bytecode via `D3DCompile`. This is also where
// Milestone 4.4's engine-owned `DxResourceLayout` actually becomes real:
// each resource's engine-assigned slot is forced into the emitted HLSL as
// an explicit `register(b#/t#/s#)` via SPIRV-Cross's
// `add_hlsl_resource_binding`, rather than letting `CompilerHLSL` pick
// registers on its own -- see this file's .cpp for exactly how that
// forcing works and why it was verified against SPIRV-Cross's real
// `spirv_hlsl.hpp` (fetched from the upstream KhronosGroup/SPIRV-Cross
// repository during this session, since this sandbox has neither a
// vendored copy nor a Windows toolchain to check against directly).
//
// Kept in its own file (as opposed to extending
// directexp_shader_toolchain.h/.cpp, which owns the `shaderc` GLSL ->
// SPIR-V step, or directexp_shader_reflection.h/.cpp, which owns
// SPIRV-Cross reflection): this is a third, distinct toolchain concern
// (SPIRV-Cross HLSL emission + `D3DCompile`), matching this project's
// existing "one file per toolchain concern" split.
#pragma once

#ifdef _WIN32

#include "directexp_shader_reflection.h"

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

namespace directexp {

// Which shader stage this call is for. A separate, tiny enum from
// SPIRV-Cross's own `spv::ExecutionModel` (kept out of this header so
// callers of this header alone don't need <spirv_cross/...> transitively)
// and from `shaderc_shader_kind` (directexp_shader_toolchain.h) --
// translated to both inside the .cpp. Ordinal values match
// dev.cubcraftv.directexp.bridge.DxShaderStageKind's own
// `nativeShaderKind()` encoding, the same encoding
// directexp_bridge_shader.cpp's `ToShadercShaderKind` already uses for
// `compileGlslToSpirv0`.
enum class ShaderExecutionStage : int32_t {
	Vertex = 0,
	Fragment = 1,
	// Only for CompileHlslToD3DBytecode (generated point-sprite geometry
	// shader); SPIRV-Cross emission never targets it.
	Geometry = 2,
};

// One resource's engine-assigned D3D11 register slot, as produced
// Java-side by Milestone 4.4's `DxResourceLayoutBuilder`
// (`DxResourceLayoutEntry`) and handed down here so SPIRV-Cross's HLSL
// emission can force that exact register rather than choosing one on its
// own. `kind`/`name` together identify which reflected resource
// (Milestone 4.4's `DxReflectedResource`) this assignment is for; `slot`
// is that resource's `0`-based, per-kind index from
// `DxResourceLayoutEntry::slot()`.
struct HlslResourceSlotAssignment {
	std::string name;
	SpirvResourceKind kind = SpirvResourceKind::ConstantBuffer;
	uint32_t slot = 0;
};

// Result of one `CompileSpirvToD3DBytecode` call. `emissionSucceeded` and
// `compileSucceeded` are reported separately (rather than a single
// success flag) so a caller can tell *which* of the two independent
// failure modes happened -- SPIRV-Cross's SPIR-V -> HLSL translation, or
// `D3DCompile`'s HLSL -> bytecode compilation -- matching the "surface
// D3DCompile errors as structured diagnostics, same treatment as 4.3's
// shaderc error handling" checklist item, now with an extra stage of its
// own to distinguish. `hlsl` is populated whenever emission succeeded,
// even if the subsequent `D3DCompile` call then failed -- seeing the
// exact HLSL text `D3DCompile` rejected is far more useful for debugging
// than only the compiler's own error string.
struct ShaderCompilationResult {
	bool emissionSucceeded = false;
	bool compileSucceeded = false;
	std::string hlsl;
	std::vector<uint8_t> bytecode;
	std::string errorMessage;
};

// Translates `spirv` to HLSL via SPIRV-Cross's `CompilerHLSL` (Shader
// Model 5.0), forcing each resource named in `assignments` to the exact
// register `assignments` says, then compiles the result to D3D11 shader
// bytecode via `D3DCompile` (target `vs_5_0` for
// `ShaderExecutionStage::Vertex`, `ps_5_0` for
// `ShaderExecutionStage::Fragment`). `shaderName` is used only for
// `D3DCompile`'s own `pSourceName` diagnostic parameter (the same "not
// looked up anywhere, just a human-readable label" role
// `CompileGlslToSpirv`'s `shaderName` parameter already plays for
// `shaderc`).
//
// Never throws: both the SPIRV-Cross translation (a C++ exception on
// malformed/unsupported SPIR-V -- should not happen for SPIR-V this
// project's own `CompileGlslToSpirv` just produced, but not structurally
// impossible) and the `D3DCompile` call (a failed `HRESULT`, which is
// D3DCompile's own normal error-reporting mechanism, not an exception)
// report failure through the returned `ShaderCompilationResult`'s
// booleans/`errorMessage`, matching this project's "expected runtime
// case, not a can't-happen" treatment of every other toolchain step.
ShaderCompilationResult CompileSpirvToD3DBytecode(
	const std::vector<uint32_t>& spirv,
	ShaderExecutionStage stage,
	const std::string& shaderName,
	const std::vector<HlslResourceSlotAssignment>& assignments
);

// Compiles already-written HLSL text (no SPIRV-Cross step) to D3D11 bytecode
// via `D3DCompile`, entry point `main`, profile chosen like
// CompileSpirvToD3DBytecode's (`gs_5_0`/`gs_4_1` for Geometry). Used for the
// geometry shader DirectExp generates itself for `gl_PointSize`. Reports
// failure through the result exactly like CompileSpirvToD3DBytecode;
// `emissionSucceeded` is always true and `hlsl` echoes the input.
ShaderCompilationResult CompileHlslToD3DBytecode(
	const std::string& hlslSource,
	ShaderExecutionStage stage,
	const std::string& shaderName
);

// Converts a ShaderCompilationResult into a
// dev.cubcraftv.directexp.bridge.NativeDxHlslCompilationResult instance,
// for Milestone 4.5's `emitAndCompileHlsl0` JNI entrypoint
// (directexp_bridge_shader.cpp). Keep the constructor signature this
// builds in sync with that record's fields. Returns nullptr (with a
// pending JNI exception) only on a JNI-level failure, matching
// directexp_shader_reflection.h's `BuildReflectionResult` convention.
jobject BuildHlslCompilationResult(JNIEnv* env, const ShaderCompilationResult& result);

} // namespace directexp

#endif // _WIN32
