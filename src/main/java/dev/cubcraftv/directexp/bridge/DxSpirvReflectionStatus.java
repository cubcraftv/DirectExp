package dev.cubcraftv.directexp.bridge;

/**
 * Mirrors {@link DxIrCompilationStatus}'s shape exactly, one level up the
 * shader toolchain: a distinct status per known outcome of one
 * {@link DxBridge#reflectSpirv} call.
 */
public enum DxSpirvReflectionStatus {
	READY,
	// SPIRV-Cross ran but threw reflecting this SPIR-V module (malformed
	// input) -- should not happen for SPIR-V this project's own
	// compileGlslToSpirv produced, but kept as a distinct, structured
	// outcome rather than a native exception, the same "expected runtime
	// case" treatment DxIrCompilationStatus.COMPILE_ERROR gives a
	// shaderc failure.
	REFLECTION_ERROR,
	// Mirrors DxIrCompilationStatus/DxBridgeProbeStatus's load-failure
	// statuses: the native bridge library itself could not be loaded, so
	// SPIRV-Cross was never reached at all.
	UNSUPPORTED_OS,
	LIBRARY_MISSING,
	LIBRARY_LOAD_FAILED,
	RUNTIME_DEPENDENCY_MISSING,
	NATIVE_ERROR
}
