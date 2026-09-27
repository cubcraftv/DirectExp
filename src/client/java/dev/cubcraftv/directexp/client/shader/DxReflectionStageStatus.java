package dev.cubcraftv.directexp.client.shader;

/**
 * Mirrors {@link DxIrCompilationStageStatus}'s shape exactly, one stage
 * further down the pipeline: a distinct status per known failure reason,
 * distinguishing which stage (vertex or fragment) failed to reflect the
 * same way {@code DxIrCompilationStageStatus} distinguishes which stage
 * failed to compile.
 *
 * <p>Deliberately a distinct type from
 * {@code dev.cubcraftv.directexp.bridge.DxSpirvReflectionStatus} (the
 * bridge-level result status for one {@code reflectSpirv} call) for the
 * same reason {@code DxIrCompilationStageStatus} is kept distinct from
 * {@code DxIrCompilationStatus}: this one is per-pipeline, the bridge-level
 * one is per-call. {@link DxReflectionStage} maps the latter into this
 * one.
 */
public enum DxReflectionStageStatus {
	READY,
	// SPIRV-Cross rejected the vertex stage's SPIR-V -- see the pipeline
	// result's detail() for the structured SPIRV-Cross diagnostic. Should
	// not happen for SPIR-V this project's own IR stage just produced,
	// but reported distinctly rather than silently treated the same as a
	// bridge-availability problem.
	VERTEX_REFLECTION_FAILED,
	// Same as above, for the fragment stage.
	FRAGMENT_REFLECTION_FAILED,
	// The DxBridge itself could not reflect at all (native library not
	// loaded, entrypoint missing) -- distinct from a real SPIRV-Cross
	// reflection error, mirroring DxIrCompilationStageStatus's own split.
	BRIDGE_UNAVAILABLE
}
