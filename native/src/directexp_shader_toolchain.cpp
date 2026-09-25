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
// NOTE: like the rest of this native bridge, this file has not been
// compiled by the authoring agent (a Linux sandbox without an MSYS2/MSVC
// Windows toolchain, shaderc, or SPIRV-Cross available). Check
// docs/plans/2026-09-20-directexp-bootstrap-plan.md's Progress Log for
// whether a later session has build/runtime-verified it since.

#include "directexp_shader_toolchain.h"

#include <jni.h>

#ifdef _WIN32

#include <shaderc/shaderc.hpp>
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

void RunShaderToolchainSmokeTest() {
	LogLine("Shader toolchain smoke test starting (shaderc + SPIRV-Cross, no Minecraft/JNI involvement).");
	LogLine("GLSL input:");
	LogLine(kSmokeTestGlslSource);

	shaderc::Compiler compiler;
	shaderc::CompileOptions options;

	// GLSL -> SPIR-V only, never HLSL -> SPIR-V: shaderc's HLSL-as-input
	// path is upstream-deprecated (see the toolchain research notes'
	// shaderc section), but that's irrelevant here since this pipeline
	// only ever asks shaderc to compile GLSL.
	shaderc::SpvCompilationResult spirvResult = compiler.CompileGlslToSpv(
		kSmokeTestGlslSource,
		shaderc_glsl_fragment_shader,
		kSmokeTestShaderName,
		options
	);

	if (spirvResult.GetCompilationStatus() != shaderc_compilation_status_success) {
		LogLine("FAILED at shaderc GLSL -> SPIR-V: " + spirvResult.GetErrorMessage());
		return;
	}

	std::vector<uint32_t> spirv(spirvResult.cbegin(), spirvResult.cend());
	LogLine("shaderc produced " + std::to_string(spirv.size()) + " SPIR-V words.");

	std::string hlslSource;
	try {
		spirv_cross::CompilerHLSL hlslCompiler(spirv);

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