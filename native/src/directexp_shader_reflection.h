// Milestone 4.4: Reflection and Resource Layout Model.
//
// Scope (see docs/plans/2026-09-25-directexp-milestone4-breakdown.md's
// "Milestone 4.4" section): reflect the resources (constant buffers,
// sampled textures, samplers, structured buffers) declared by one shader
// stage's SPIR-V, produced by Milestone 4.3's `CompileGlslToSpirv`. This
// file only reflects -- it does not combine the result with Blaze3D's
// pipeline declarations into the engine-owned layout model (that
// combination is Java-side, deterministic, and needs no native call; see
// `DxResourceLayoutBuilder` in `client/shader`), and it does not translate
// anything to HLSL (that's Milestone 4.5's `CompilerHLSL` job).
//
// Per the toolchain research notes' "SPIRV-Cross (reflection + SPIR-V ->
// HLSL)" section, resource reflection uses SPIRV-Cross's base
// `spirv_cross::Compiler` class (`get_shader_resources()`), not
// `spirv_cross::CompilerReflection` (which emits a JSON reflection
// dump -- a different, string-oriented tool this project has no use for)
// and not `spirv_cross::CompilerHLSL` (needed only once actual HLSL
// emission starts in 4.5). `spirv-cross-core` is already linked by
// Milestone 4.1's `native/CMakeLists.txt`, so this needs no new library.
#pragma once

#ifdef _WIN32

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

namespace directexp {

// Mirrors dev.cubcraftv.directexp.bridge.DxResourceKind's ordinal values
// -- keep the two in sync. Matches the design spec's "Binding Model"
// section, which requires the layout model to distinguish at least these
// four categories.
enum class SpirvResourceKind : int32_t {
	ConstantBuffer = 0,
	SampledTexture = 1,
	Sampler = 2,
	StructuredBuffer = 3,
};

// One resource as reflected directly from SPIR-V, before any combination
// with Blaze3D's own pipeline declarations (that combination is
// `DxResourceLayoutBuilder`'s job, Java-side). `name` is SPIR-V's debug
// name for the resource (its GLSL source name, assuming `shaderc` did not
// strip debug info -- see `ResourceName` in the .cpp for the fallback
// when it did). `sizeBytes` is only meaningful for `ConstantBuffer`/
// `StructuredBuffer` (the declared struct size backing the block);
// textures and samplers leave it 0.
struct SpirvResource {
	std::string name;
	SpirvResourceKind kind = SpirvResourceKind::ConstantBuffer;
	uint32_t sizeBytes = 0;
};

// Result of reflecting one shader stage's SPIR-V module.
struct SpirvReflectionResult {
	bool success = false;
	std::vector<SpirvResource> resources;
	std::string errorMessage;
};

// Reflects `spirv` (raw SPIR-V words, the same form `DxSpirvModule` stores
// on the Java side) via SPIRV-Cross. Never throws: a SPIRV-Cross exception
// (malformed SPIR-V -- should not happen for SPIR-V this project's own
// `CompileGlslToSpirv` just produced, but a defensive stage boundary
// costs little) comes back as `SpirvReflectionResult::success == false`
// with `errorMessage` populated, the same "expected runtime case, not a
// can't-happen" treatment `CompileGlslToSpirv` gives a `shaderc` compile
// error.
SpirvReflectionResult ReflectSpirv(const std::vector<uint32_t>& spirv);

// Converts a SpirvReflectionResult into a
// dev.cubcraftv.directexp.bridge.NativeDxSpirvReflectionResult instance,
// for the `reflectSpirv0` JNI entrypoint (directexp_bridge_shader.cpp).
// Keep the constructor signature this builds
// (`(I[Ljava/lang/String;[I[ILjava/lang/String;)V`) in sync with that
// record's fields. Returns nullptr (with a pending JNI exception) only on
// a JNI-level failure (class/constructor/array allocation could not be
// found or created), matching directexp_shader_toolchain.h's
// `BuildIrCompilationResult` convention.
jobject BuildReflectionResult(JNIEnv* env, const SpirvReflectionResult& result);

} // namespace directexp

#endif // _WIN32
