package dev.cubcraftv.directexp.bridge;

/**
 * Mirrors {@link DxSpirvReflectionStatus}'s shape, one step further down
 * the shader toolchain: a distinct status per known outcome of one
 * {@link DxBridge#emitAndCompileHlsl} call. Split into two separate error
 * statuses ({@link #EMISSION_ERROR}, {@link #COMPILE_ERROR}) rather than
 * one, since this call has two independent steps -- SPIRV-Cross's SPIR-V
 * -&gt; HLSL translation, then {@code D3DCompile}'s HLSL -&gt; bytecode
 * compilation -- that can each fail on their own, and a caller needs to
 * be able to tell which one did.
 */
public enum DxHlslCompilationStatus {
	READY,
	// SPIRV-Cross rejected this SPIR-V during HLSL emission -- see
	// DxHlslCompilationResult#detail() for the diagnostic. Should not
	// happen for SPIR-V this project's own IR stage just produced, but
	// reported distinctly rather than silently treated as a bridge
	// availability problem.
	EMISSION_ERROR,
	// SPIRV-Cross emitted HLSL successfully, but D3DCompile rejected it.
	// DxHlslCompilationResult#hlsl() still carries the HLSL text
	// D3DCompile was given, for diagnosing exactly what it rejected.
	COMPILE_ERROR,
	// Mirrors DxSpirvReflectionStatus/DxIrCompilationStatus's
	// load-failure statuses: the native bridge library itself could not
	// be loaded, so neither SPIRV-Cross nor D3DCompile was ever reached.
	UNSUPPORTED_OS,
	LIBRARY_MISSING,
	LIBRARY_LOAD_FAILED,
	RUNTIME_DEPENDENCY_MISSING,
	NATIVE_ERROR
}
