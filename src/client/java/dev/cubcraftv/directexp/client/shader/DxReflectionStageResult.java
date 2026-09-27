package dev.cubcraftv.directexp.client.shader;

/**
 * Result of running Milestone 4.4's reflection stage for one pipeline.
 * Follows the same record+status pair shape as
 * {@code DxIrCompilationStageResult}: {@code reflection} is non-null if
 * and only if {@code status == READY}.
 */
public record DxReflectionStageResult(
	DxReflectionStageStatus status,
	String pipelineDebugLabel,
	DxPipelineReflection reflection,
	String detail
) {
	public boolean isReady() {
		return this.status == DxReflectionStageStatus.READY && this.reflection != null;
	}

	public static DxReflectionStageResult ready(String pipelineDebugLabel, DxPipelineReflection reflection) {
		return new DxReflectionStageResult(DxReflectionStageStatus.READY, pipelineDebugLabel, reflection, null);
	}

	public static DxReflectionStageResult failed(DxReflectionStageStatus status, String pipelineDebugLabel, String detail) {
		if (status == DxReflectionStageStatus.READY) {
			throw new IllegalArgumentException("failed() cannot be used with READY; use ready() instead.");
		}
		return new DxReflectionStageResult(status, pipelineDebugLabel, null, detail);
	}
}
