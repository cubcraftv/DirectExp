// See directexp_shader_toolchain.h for scope. This file also owns
// `JNI_OnLoad`, the standard JVM/JNI lifecycle hook that fires
// automatically as soon as `directexp_native.dll` is loaded (via either
// `System.loadLibrary` or `System.load`, see NativeDxBridgeLoader.java) --
// before any `native` method declared elsewhere in this project is ever
// called. That makes it the natural place to run a "does the toolchain
// work at all" smoke test that Milestone 4.1 says should need no JNI
// surface of its own: nothing on the Java side has to change to trigger
// it, and no other file in this project defines `JNI_OnLoad` (there can
// only be one per shared library).
//
// Milestone 4.3 adds `CompileGlslToSpirv` (the real, reusable compile
// helper -- the smoke test above now calls it too, instead of duplicating
// the `shaderc` call inline) and `BuildIrCompilationResult` (the
// GlslToSpirvResult -> Java conversion). The actual JNI entrypoint that
// calls both of those for a real, on-demand compile
// (`compileGlslToSpirv0`) lives in the new directexp_bridge_shader.cpp,
// matching this project's existing split between shared native logic
// (this file, directexp_common.cpp) and per-feature JNI entrypoint files
// (directexp_bridge_probe.cpp, directexp_bridge_surface.cpp,
// directexp_bridge_texture.cpp).
//
// NOTE: like the rest of this native bridge, this file has not been
// compiled by the authoring agent (a Linux sandbox without an MSYS2/MSVC
// Windows toolchain, shaderc, or SPIRV-Cross available). Check
// docs/plans/2026-09-20-directexp-bootstrap-plan.md's Progress Log for
// whether a later session has build/runtime-verified it since.

#include "directexp_shader_toolchain.h"
#include "directexp_common.h"

#include <jni.h>

#ifdef _WIN32

#include <spirv_cross/spirv_hlsl.hpp>

#include <windows.h>
#include <cstdio>
#include <string>
#include <vector>

namespace directexp {

namespace {

// Deliberately trivial and self-contained: a valid GLSL fragment shader
// with no inputs/uniforms, just enough for shaderc to have real work to
// do. Not loaded from Minecraft and not meant to resemble Mojang's actual
// shaders -- that real source is Milestone 4.2's job (Source and
// Preprocessing Stage). This string never changes at runtime, so a
// successful round-trip here says only "the toolchain works", not
// anything about Mojang's shaders specifically.
constexpr const char* kSmokeTestGlslSource =
	"#version 450\n"
	"layout(location = 0) out vec4 outColor;\n"
	"void main() {\n"
	"\toutColor = vec4(1.0, 0.0, 1.0, 1.0);\n"
	"}\n";

constexpr const char* kSmokeTestShaderName = "directexp_milestone4_1_smoke_test.frag";

// No Java-side logging bridge exists yet at this stage of Milestone 4 --
// wiring native diagnostics through to DirectExpMod.LOGGER is real JNI
// surface, which this milestone explicitly defers. stderr is visible when
// the game is launched from a console/terminal (the common case for
// `./gradlew.bat runClient` during development); OutputDebugStringA is
// visible via DebugView or Visual Studio's Output window when launched
// without one attached.
void LogLine(const std::string& line) {
	std::fprintf(stderr, "[DirectExp][Milestone4.1] %s\n", line.c_str());
	std::string debugLine = "[DirectExp][Milestone4.1] " + line + "\n";
	OutputDebugStringA(debugLine.c_str());
}

} // namespace

GlslToSpirvResult CompileGlslToSpirv(shaderc_shader_kind kind, const std::string& glslSource, const std::string& shaderName) {
	GlslToSpirvResult result;

	shaderc::Compiler compiler;
	shaderc::CompileOptions options;

	// Keep the SPIR-V debug names (OpName). Milestone 4.4 reflects resources
	// by name and Milestone 4.5 looks their forced registers up by the same
	// name, so a stripped module would degrade to synthesized
	// `spirv_resource_<id>` names and stop matching the pipeline's declared
	// uniforms/samplers. Mojang's Vulkan GlslCompiler sets the same option
	// (decompiled 26.2 snapshot 1); its reflection is name-based too.
	//
	// Locations/bindings are NOT set through shaderc options here: unlike
	// Vulkan (auto-map + auto-bind, then patching the SPIR-V words),
	// DirectExp writes explicit layout(location/binding) into the GLSL text
	// beforehand -- see DxShaderInterfaceLayout.java.
	options.SetGenerateDebugInfo();

	// GLSL -> SPIR-V only, never HLSL -> SPIR-V: shaderc's HLSL-as-input
	// path is upstream-deprecated (see the toolchain research notes'
	// shaderc section), but that's irrelevant here since this pipeline
	// only ever asks shaderc to compile GLSL.
	shaderc::SpvCompilationResult spirvResult = compiler.CompileGlslToSpv(
		glslSource,
		kind,
		shaderName.c_str(),
		options
	);

	result.numErrors = static_cast<int>(spirvResult.GetNumErrors());
	result.numWarnings = static_cast<int>(spirvResult.GetNumWarnings());

	if (spirvResult.GetCompilationStatus() != shaderc_compilation_status_success) {
		result.success = false;
		result.errorMessage = spirvResult.GetErrorMessage();
		if (result.errorMessage.empty()) {
			// shaderc's own message is usually non-empty for real syntax
			// errors, but fall back to something non-empty anyway so a
			// caller never has to special-case "compile failed but there
			// is no detail string".
			result.errorMessage = "shaderc GLSL -> SPIR-V compilation failed (status "
				+ std::to_string(static_cast<int>(spirvResult.GetCompilationStatus())) + ") with no further detail.";
		}
		return result;
	}

	result.success = true;
	result.spirv.assign(spirvResult.cbegin(), spirvResult.cend());
	return result;
}

void RunShaderToolchainSmokeTest() {
	LogLine("Shader toolchain smoke test starting (shaderc + SPIRV-Cross, no Minecraft/JNI involvement).");
	LogLine("GLSL input:");
	LogLine(kSmokeTestGlslSource);

	GlslToSpirvResult compileResult = CompileGlslToSpirv(shaderc_glsl_fragment_shader, kSmokeTestGlslSource, kSmokeTestShaderName);
	if (!compileResult.success) {
		LogLine("FAILED at shaderc GLSL -> SPIR-V: " + compileResult.errorMessage);
		return;
	}

	LogLine("shaderc produced " + std::to_string(compileResult.spirv.size()) + " SPIR-V words.");

	std::string hlslSource;
	try {
		spirv_cross::CompilerHLSL hlslCompiler(compileResult.spirv);

		// Shader Model 5.0, matching the feature-level-10.1 floor from
		// the existing D3D11 research notes (vs_5_0/ps_5_0). Milestone
		// 4.5 is what needs to confirm this value carefully against real
		// shaders; here it only needs to be *a* valid target so the
		// translation path can be exercised end to end.
		spirv_cross::CompilerHLSL::Options hlslOptions;
		hlslOptions.shader_model = 50;
		hlslCompiler.set_hlsl_options(hlslOptions);

		hlslSource = hlslCompiler.compile();
	} catch (const std::exception& error) {
		LogLine(std::string("FAILED at SPIRV-Cross SPIR-V -> HLSL: ") + error.what());
		return;
	}

	LogLine("SPIRV-Cross produced HLSL output:");
	LogLine(hlslSource);
	LogLine("Shader toolchain smoke test succeeded: GLSL -> SPIR-V -> HLSL round-trip completed inside directexp_native.");
}

jobject BuildIrCompilationResult(JNIEnv* env, const GlslToSpirvResult& result) {
	jclass resultClass = env->FindClass("dev/cubcraftv/directexp/bridge/NativeDxIrCompilationResult");
	if (resultClass == nullptr) {
		return nullptr;
	}

	jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(I[BLjava/lang/String;II)V");
	if (constructor == nullptr) {
		return nullptr;
	}

	// OUTCOME_READY / OUTCOME_COMPILE_ERROR below must match
	// NativeDxIrCompilationResult.java's own constants -- kept as bare
	// literals here (rather than a shared enum with directexp_common.h's
	// OUTCOME_* constants) because a shaderc compile error is a distinct,
	// expected outcome for *this* result type, not the same
	// "unimplemented stub vs. generic error" split that
	// NativeDxBridgeProbeResult/NativeDxBridgeSurfaceBootstrapResult
	// share; see that Java file's javadoc.
	jint outcomeCode = result.success ? 0 /* OUTCOME_READY */ : 1 /* OUTCOME_COMPILE_ERROR */;

	jbyteArray spirvArray = nullptr;
	if (result.success) {
		jsize byteLength = static_cast<jsize>(result.spirv.size() * sizeof(uint32_t));
		spirvArray = env->NewByteArray(byteLength);
		if (spirvArray != nullptr && byteLength > 0) {
			// The SPIR-V words are handed to Java as raw host-endian bytes,
			// not a portable serialization -- they only ever round-trip
			// back into this same native module later (Milestone 4.4's
			// reflection step, Milestone 4.5's HLSL emission), never
			// across machines or persisted to disk, so host endianness is
			// not a concern here.
			env->SetByteArrayRegion(spirvArray, 0, byteLength, reinterpret_cast<const jbyte*>(result.spirv.data()));
		}
	}

	jstring detail = ToJString(env, result.errorMessage);

	return env->NewObject(
		resultClass,
		constructor,
		outcomeCode,
		spirvArray,
		detail,
		static_cast<jint>(result.numErrors),
		static_cast<jint>(result.numWarnings)
	);
}

} // namespace directexp

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
	(void) vm;
	(void) reserved;
	directexp::RunShaderToolchainSmokeTest();
	return JNI_VERSION_1_8;
}

#else // !_WIN32

// Non-Windows build: still needs JNI_OnLoad to exist somewhere if this
// library is ever compiled on a non-Windows host (e.g. a CI lint/format
// pass -- see directexp_bridge_probe.cpp's file header for the same
// rationale on the probe entrypoint), but there is no shader toolchain to
// smoke-test off Windows.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
	(void) vm;
	(void) reserved;
	return JNI_VERSION_1_8;
}

#endif // _WIN32