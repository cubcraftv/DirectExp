package dev.cubcraftv.directexp.client.shader;

/**
 * Mirrors {@link DxIrCompilationStageStatus}/{@link DxReflectionStageStatus}'s
 * shape, one stage further down the pipeline. Has twice as many failure
 * statuses as those two: each stage (vertex/fragment) now has two
 * independent ways to fail (SPIRV-Cross emission, then
 * {@code D3DCompile}); fragment interface rewriting has its own failure
 * status. These align with
 * {@code dev.cubcraftv.directexp.bridge.DxHlslCompilationStatus}'s
 * {@code EMISSION_ERROR}/{@code COMPILE_ERROR} split.
 */
public enum DxHlslCompilationStageStatus {
	READY,
	VERTEX_EMISSION_FAILED,
	FRAGMENT_EMISSION_FAILED,
	VERTEX_COMPILE_FAILED,
	FRAGMENT_COMPILE_FAILED,
	// Pixel-stage input semantics could not be aligned to the vertex outputs.
	FRAGMENT_INTERFACE_FAILED,
	// The DxBridge itself could not emit/compile at all (native library
	// not loaded, entrypoint missing) -- distinct from either kind of
	// real toolchain failure above.
	BRIDGE_UNAVAILABLE
}
