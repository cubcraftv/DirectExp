package dev.cubcraftv.directexp.client.shader;

/**
 * Mirrors the {@code DxShaderSourceStatus} result-status pattern used by
 * Milestone 4.2's stage: a distinct status per known failure reason,
 * distinguishing which stage (vertex or fragment) failed to compile the
 * same way {@code DxShaderSourceStatus} distinguishes a missing vertex
 * source from a missing fragment source.
 * <p>
 * Deliberately a distinct type from {@code dev.cubcraftv.directexp.bridge.DxIrCompilationStatus}
 * (the bridge-level result status for one {@code compileGlslToSpirv} call)
 * even though the two share a naming prefix and a similar shape: this one
 * is per-pipeline (which of the two stages failed) while the bridge-level
 * one is per-call (did the compile itself succeed, or did the bridge never
 * get reached at all). {@link DxIrCompilationStage} maps the latter into
 * this one.
 */
public enum DxIrCompilationStageStatus {
	READY,
	// shaderc rejected the vertex stage's preprocessed GLSL -- see the
	// pipeline result's detail() for the structured shaderc diagnostic.
	VERTEX_COMPILE_FAILED,
	// Same as above, for the fragment stage.
	FRAGMENT_COMPILE_FAILED,
	// The DxBridge itself could not compile at all (native library not
	// loaded, entrypoint missing) -- distinct from a real shaderc compile
	// error, mirroring the bridge-level DxIrCompilationStatus's own split
	// between COMPILE_ERROR and its other, bridge-availability statuses.
	BRIDGE_UNAVAILABLE
}
