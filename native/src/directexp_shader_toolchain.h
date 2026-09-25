// Milestone 4.1: Native Shader Toolchain Bring-Up.
//
// Scope (see docs/plans/2026-09-25-directexp-milestone4-breakdown.md's
// "Milestone 4.1" section): prove that `shaderc` and `SPIRV-Cross` actually
// link and interoperate inside `directexp_native`, and that `d3dcompiler`
// links (see native/CMakeLists.txt), before any real shader content
// (Mojang GLSL, a JNI surface, an engine layout model) touches them. This
// file is intentionally *not* exposed through JNI yet -- that starts with
// Milestone 4.3 (IR Stage), once Milestone 4.2 has produced real
// preprocessed shader source to feed it. There is no D3DCompile call here
// either; that is Milestone 4.5 (HLSL Emission and D3D Compilation).
#pragma once

#ifdef _WIN32

namespace directexp {

// Compiles one small hardcoded GLSL fragment shader (not loaded from
// Minecraft, not representative of Mojang's real shaders -- that's
// Milestone 4.2) through `shaderc` to SPIR-V, then through SPIRV-Cross's
// `CompilerHLSL` back to HLSL text, and logs the GLSL input and HLSL
// output. Run automatically once per process from `JNI_OnLoad` (see this
// file's .cpp) rather than from a dedicated JNI entrypoint, since
// Milestone 4.1 deliberately has no JNI surface of its own yet.
//
// Failures (a `shaderc` compile error, a SPIRV-Cross translation
// exception) are logged and swallowed here rather than propagated -- there
// is no JNI call frame for this to throw back into yet, and a broken
// toolchain smoke test should not prevent `directexp_native.dll` from
// finishing its load and serving the existing probe/surface/texture
// entrypoints.
void RunShaderToolchainSmokeTest();

} // namespace directexp

#endif // _WIN32
