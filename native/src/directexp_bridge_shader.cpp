// DirectExp native bridge - shader compile/reflect/emit entrypoints
// (Milestone 4.3: IR Stage; Milestone 4.4: Reflection and Resource
// Layout Model; Milestone 4.5: HLSL Emission and D3D Compilation).
//
// Scope: this is the JNI surface Milestone 4.1 deliberately deferred (see
// directexp_shader_toolchain.h/.cpp) -- a real, on-demand compile call that
// takes preprocessed GLSL text (from Milestone 4.2's
// DxShaderSourceStage/DxPreprocessedShaderSource) and returns SPIR-V bytes
// or a structured compile error, instead of only running a fixed
// hardcoded smoke-test string once at load time. The actual `shaderc` call
// and the GlslToSpirvResult -> Java conversion live in
// directexp_shader_toolchain.h/.cpp (shared with the 4.1 smoke test); this
// file is just the thin JNI entrypoint that unmarshals the two jstring
// arguments and forwards to them, matching this project's existing split
// between shared native logic and per-feature JNI entrypoint files (see
// directexp_bridge_probe.cpp, directexp_bridge_surface.cpp,
// directexp_bridge_texture.cpp).
//
// Milestone 4.4 adds `reflectSpirv0` alongside `compileGlslToSpirv0`
// rather than a new directexp_bridge_reflection.cpp: both are
// pure-CPU-side "shader toolchain" JNI entrypoints with no device/surface
// involvement, and reflection's real logic (ReflectSpirv/
// BuildReflectionResult) lives in the new, separate
// directexp_shader_reflection.h/.cpp -- kept apart from
// directexp_shader_toolchain.h/.cpp since reflection is SPIRV-Cross-only
// work with no `shaderc` call in it, not because it belongs in a
// different bridge file.
//
// No device/context/surface involvement here at all -- unlike
// directexp_bridge_texture.cpp, shader compilation via `shaderc` and
// `SPIRV-Cross` is pure CPU-side work with no dependency on
// g_activeDevice/g_activeContext (see directexp_common.h), so this call
// works even before any DirectX surface has been bootstrapped.
//
// Milestone 4.5 adds a third entrypoint, `emitAndCompileHlsl0`, again in
// this same file rather than a new directexp_bridge_hlsl.cpp: still a
// pure-CPU-side shader toolchain entrypoint with no device/surface
// dependency, same reasoning as `reflectSpirv0` above. Its real logic
// (SPIRV-Cross HLSL emission + D3DCompile) lives in the new
// directexp_shader_hlsl.h/.cpp, a third toolchain-concern file alongside
// directexp_shader_toolchain.h/.cpp (shaderc) and
// directexp_shader_reflection.h/.cpp (SPIRV-Cross reflection). This is
// also the first `D3DCompile` call anywhere in this project.
//
// JNI naming: Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_<method>
// mirrors the `native` method declarations in NativeDxBridge.java.

#include "directexp_shader_toolchain.h"
#include "directexp_shader_reflection.h"
#include "directexp_shader_hlsl.h"

#ifdef _WIN32

using namespace directexp;

namespace {

// Keep in sync with DxShaderStageKind.java's `nativeShaderKind()`
// encoding. Anything other than 1 is treated as vertex rather than
// throwing, on the theory that an unrecognized stage kind should still
// attempt a compile (and let `shaderc` itself reject genuinely invalid
// GLSL) rather than fail this JNI call before it can even report a
// structured diagnostic back to Java.
shaderc_shader_kind ToShadercShaderKind(jint stageKind) {
	switch (stageKind) {
		case 1:
			return shaderc_glsl_fragment_shader;
		case 0:
		default:
			return shaderc_glsl_vertex_shader;
	}
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_compileGlslToSpirv0(
	JNIEnv* env, jclass, jstring shaderName, jstring glslSource, jint shaderStageKind
) {
	if (shaderName == nullptr || glslSource == nullptr) {
		GlslToSpirvResult failure;
		failure.success = false;
		failure.errorMessage = "compileGlslToSpirv0 requires a non-null shader name and GLSL source.";
		return BuildIrCompilationResult(env, failure);
	}

	const char* nameChars = env->GetStringUTFChars(shaderName, nullptr);
	const char* sourceChars = env->GetStringUTFChars(glslSource, nullptr);

	// GetStringUTFChars can return nullptr on an OOM (it would also throw
	// OutOfMemoryError in that case, which we let propagate rather than
	// masking with ThrowIllegalState -- there's no useful diagnostic to
	// add on top of "the JVM ran out of memory").
	std::string name = nameChars != nullptr ? std::string(nameChars) : std::string();
	std::string source = sourceChars != nullptr ? std::string(sourceChars) : std::string();

	if (nameChars != nullptr) {
		env->ReleaseStringUTFChars(shaderName, nameChars);
	}
	if (sourceChars != nullptr) {
		env->ReleaseStringUTFChars(glslSource, sourceChars);
	}

	if (nameChars == nullptr || sourceChars == nullptr) {
		return nullptr;
	}

	GlslToSpirvResult compileResult = CompileGlslToSpirv(ToShadercShaderKind(shaderStageKind), source, name);
	return BuildIrCompilationResult(env, compileResult);
}

// Milestone 4.4: reflects one shader stage's compiled SPIR-V (from
// compileGlslToSpirv0 above) via SPIRV-Cross. Stage-agnostic -- unlike
// compileGlslToSpirv0, there is no `shaderStageKind` parameter, since
// resource classification (ClassifyAndCollect in
// directexp_shader_reflection.cpp) depends only on the SPIR-V itself, not
// on which stage it came from; the Java-side caller
// (DxReflectionStage in client/shader) is what keeps vertex/fragment
// results distinct.
extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_reflectSpirv0(
	JNIEnv* env, jclass, jbyteArray spirvBytes
) {
	if (spirvBytes == nullptr) {
		SpirvReflectionResult failure;
		failure.success = false;
		failure.errorMessage = "reflectSpirv0 requires non-null SPIR-V bytes.";
		return BuildReflectionResult(env, failure);
	}

	jsize byteLength = env->GetArrayLength(spirvBytes);
	if (byteLength < 0 || byteLength % static_cast<jsize>(sizeof(uint32_t)) != 0) {
		SpirvReflectionResult failure;
		failure.success = false;
		failure.errorMessage = "reflectSpirv0 received a SPIR-V byte array whose length is not a positive multiple of 4.";
		return BuildReflectionResult(env, failure);
	}

	std::vector<uint32_t> spirv(static_cast<size_t>(byteLength) / sizeof(uint32_t));
	if (!spirv.empty()) {
		env->GetByteArrayRegion(spirvBytes, 0, byteLength, reinterpret_cast<jbyte*>(spirv.data()));
	}

	SpirvReflectionResult reflectionResult = ReflectSpirv(spirv);
	return BuildReflectionResult(env, reflectionResult);
}

// Milestone 4.5: translates one shader stage's SPIR-V to HLSL and
// compiles it to D3D11 bytecode. `shaderStageKind` uses the same encoding
// as `compileGlslToSpirv0` (see `ToShadercShaderKind` above), but is
// translated to `ShaderExecutionStage` (directexp_shader_hlsl.h) instead,
// since this call has nothing to do with `shaderc`.
// `resourceNames`/`resourceKinds`/`resourceSlots` are three parallel
// arrays -- the same convention `NativeDxSpirvReflectionResult` uses in
// the other direction -- describing the engine-owned resource layout
// (Milestone 4.4's `DxResourceLayout`, filtered Java-side to just this
// stage's resources) that SPIRV-Cross's HLSL emission should force each
// named resource's register to.
namespace {

ShaderExecutionStage ToShaderExecutionStage(jint stageKind) {
	switch (stageKind) {
		case 1:
			return ShaderExecutionStage::Fragment;
		case 2:
			return ShaderExecutionStage::Geometry;
		default:
			return ShaderExecutionStage::Vertex;
	}
}

// Unmarshals the three parallel resource-slot-assignment arrays into
// native form. Returns an empty vector (not an error) if any of the three
// arrays is null or their lengths disagree -- a caller passing no
// assignments at all is a legitimate case (e.g. a shader stage with no
// resources), and a length mismatch, while it should not happen from
// NativeDxBridge's own well-formed call, is treated the same "don't force
// any registers, let SPIRV-Cross pick" way as a resource this stage's
// SPIR-V doesn't actually contain (see directexp_shader_hlsl.cpp's
// `ApplyResourceBindings`) rather than failing the whole call over it.
std::vector<HlslResourceSlotAssignment> ToResourceSlotAssignments(
	JNIEnv* env, jobjectArray resourceNames, jintArray resourceKinds, jintArray resourceSlots
) {
	std::vector<HlslResourceSlotAssignment> assignments;

	if (resourceNames == nullptr || resourceKinds == nullptr || resourceSlots == nullptr) {
		return assignments;
	}

	jsize count = env->GetArrayLength(resourceNames);
	if (env->GetArrayLength(resourceKinds) != count || env->GetArrayLength(resourceSlots) != count) {
		return assignments;
	}

	std::vector<jint> kinds(static_cast<size_t>(count));
	std::vector<jint> slots(static_cast<size_t>(count));
	if (count > 0) {
		env->GetIntArrayRegion(resourceKinds, 0, count, kinds.data());
		env->GetIntArrayRegion(resourceSlots, 0, count, slots.data());
	}

	assignments.reserve(static_cast<size_t>(count));
	for (jsize i = 0; i < count; ++i) {
		auto nameObj = static_cast<jstring>(env->GetObjectArrayElement(resourceNames, i));
		if (nameObj == nullptr) {
			continue;
		}

		const char* nameChars = env->GetStringUTFChars(nameObj, nullptr);
		if (nameChars != nullptr) {
			HlslResourceSlotAssignment assignment;
			assignment.name = nameChars;
			assignment.kind = static_cast<SpirvResourceKind>(kinds[static_cast<size_t>(i)]);
			assignment.slot = static_cast<uint32_t>(slots[static_cast<size_t>(i)]);
			assignments.push_back(std::move(assignment));
			env->ReleaseStringUTFChars(nameObj, nameChars);
		}
		env->DeleteLocalRef(nameObj);
	}

	return assignments;
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_emitAndCompileHlsl0(
	JNIEnv* env, jclass,
	jstring shaderName, jbyteArray spirvBytes, jint shaderStageKind,
	jobjectArray resourceNames, jintArray resourceKinds, jintArray resourceSlots
) {
	if (shaderName == nullptr || spirvBytes == nullptr) {
		ShaderCompilationResult failure;
		failure.emissionSucceeded = false;
		failure.errorMessage = "emitAndCompileHlsl0 requires a non-null shader name and SPIR-V bytes.";
		return BuildHlslCompilationResult(env, failure);
	}

	jsize byteLength = env->GetArrayLength(spirvBytes);
	if (byteLength < 0 || byteLength % static_cast<jsize>(sizeof(uint32_t)) != 0) {
		ShaderCompilationResult failure;
		failure.emissionSucceeded = false;
		failure.errorMessage = "emitAndCompileHlsl0 received a SPIR-V byte array whose length is not a positive multiple of 4.";
		return BuildHlslCompilationResult(env, failure);
	}

	std::vector<uint32_t> spirv(static_cast<size_t>(byteLength) / sizeof(uint32_t));
	if (!spirv.empty()) {
		env->GetByteArrayRegion(spirvBytes, 0, byteLength, reinterpret_cast<jbyte*>(spirv.data()));
	}

	const char* nameChars = env->GetStringUTFChars(shaderName, nullptr);
	std::string name = nameChars != nullptr ? std::string(nameChars) : std::string();
	if (nameChars != nullptr) {
		env->ReleaseStringUTFChars(shaderName, nameChars);
	}

	std::vector<HlslResourceSlotAssignment> assignments = ToResourceSlotAssignments(env, resourceNames, resourceKinds, resourceSlots);

	ShaderCompilationResult compileResult = CompileSpirvToD3DBytecode(spirv, ToShaderExecutionStage(shaderStageKind), name, assignments);
	return BuildHlslCompilationResult(env, compileResult);
}

// HLSL text -> D3D11 bytecode (no SPIRV-Cross), for the generated point-sprite
// geometry shader. Stage encoding as emitAndCompileHlsl0 (2 = geometry).
extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_compileHlsl0(
	JNIEnv* env, jclass, jstring shaderName, jstring hlslSource, jint shaderStageKind
) {
	if (shaderName == nullptr || hlslSource == nullptr) {
		ShaderCompilationResult failure;
		failure.emissionSucceeded = false;
		failure.errorMessage = "compileHlsl0 requires a non-null shader name and HLSL source.";
		return BuildHlslCompilationResult(env, failure);
	}

	const char* nameChars = env->GetStringUTFChars(shaderName, nullptr);
	std::string name = nameChars != nullptr ? std::string(nameChars) : std::string();
	if (nameChars != nullptr) {
		env->ReleaseStringUTFChars(shaderName, nameChars);
	}

	const char* sourceChars = env->GetStringUTFChars(hlslSource, nullptr);
	std::string source = sourceChars != nullptr ? std::string(sourceChars) : std::string();
	if (sourceChars != nullptr) {
		env->ReleaseStringUTFChars(hlslSource, sourceChars);
	}

	ShaderCompilationResult compileResult = CompileHlslToD3DBytecode(source, ToShaderExecutionStage(shaderStageKind), name);
	return BuildHlslCompilationResult(env, compileResult);
}

#else // !_WIN32

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_compileGlslToSpirv0(JNIEnv*, jclass, jstring, jstring, jint) {
	return nullptr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_reflectSpirv0(JNIEnv*, jclass, jbyteArray) {
	return nullptr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_emitAndCompileHlsl0(
	JNIEnv*, jclass, jstring, jbyteArray, jint, jobjectArray, jintArray, jintArray
) {
	return nullptr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_compileHlsl0(JNIEnv*, jclass, jstring, jstring, jint) {
	return nullptr;
}

#endif
