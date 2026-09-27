// DirectExp native bridge - shader compile/reflect entrypoints
// (Milestone 4.3: IR Stage; Milestone 4.4: Reflection and Resource
// Layout Model).
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
// No D3DCompile call here either -- HLSL emission and D3D bytecode
// compilation are Milestone 4.5's job, once Milestone 4.4's resource
// layout model (produced Java-side, from this file's reflection output)
// exists to feed it.
//
// NOTE ON VERIFICATION: this file has not been compiled by the authoring
// agent (a Linux sandbox without an MSVC/Windows SDK toolchain, shaderc, or
// SPIRV-Cross available). Check docs/plans's Progress Log for the most
// recent build verification status on a real Windows machine before
// assuming this compiles cleanly.
//
// JNI naming: Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_<method>
// mirrors the `native` method declarations in NativeDxBridge.java.

#include "directexp_shader_toolchain.h"
#include "directexp_shader_reflection.h"

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

#else // !_WIN32

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_compileGlslToSpirv0(JNIEnv*, jclass, jstring, jstring, jint) {
	return nullptr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_reflectSpirv0(JNIEnv*, jclass, jbyteArray) {
	return nullptr;
}

#endif
