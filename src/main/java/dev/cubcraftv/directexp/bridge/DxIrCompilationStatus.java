package dev.cubcraftv.directexp.bridge;

/**
 * Mirrors the {@code DxBridgeProbeStatus} / {@code DxShaderSourceStatus}
 * result-status pattern already used elsewhere in this project: a
 * distinct status per known outcome instead of a single boolean.
 */
public enum DxIrCompilationStatus {
	READY,
	// shaderc successfully ran but reported a compile error (syntax error,
	// unsupported construct) -- a normal, expected outcome for real shader
	// source, not a native-side failure. See NativeDxIrCompilationResult's
	// javadoc.
	COMPILE_ERROR,
	// Mirrors DxBridgeProbeStatus's load-failure statuses: the native
	// bridge library itself could not be loaded, so shaderc was never
	// reached at all.
	UNSUPPORTED_OS,
	LIBRARY_MISSING,
	LIBRARY_LOAD_FAILED,
	RUNTIME_DEPENDENCY_MISSING,
	NATIVE_ERROR
}
