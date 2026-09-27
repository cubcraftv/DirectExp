package dev.cubcraftv.directexp.client.shader;

/**
 * Result of running Milestone 4.5's HLSL compilation stage for one
 * pipeline. Follows the same record+status pair shape as
 * {@code DxIrCompilationStageResult}/{@code DxReflectionStageResult}:
 * {@code modules} is non-null if and only if {@code status == READY}.
 */
public record DxHlslCompilationStageResult(
	DxHlslCompilationStageStatus status,
	String pipelineDebugLabel,
	DxHlslPipelineModules modules,
	String detail
) {
	public boolean isReady() {
		return this.status == DxHlslCompilationStageStatus.READY && this.modules != null;
	}

	public static DxHlslCompilationStageResult ready(String pipelineDebugLabel, DxHlslPipelineModules modules) {
		return new DxHlslCompilationStageResult(DxHlslCompilationStageStatus.READY, pipelineDebugLabel, modules, null);
	}

	public static DxHlslCompilationStageResult failed(DxHlslCompilationStageStatus status, String pipelineDebugLabel, String detail) {
		if (status == DxHlslCompilationStageStatus.READY) {
			throw new IllegalArgumentException("failed() cannot be used with READY; use ready() instead.");
		}
		return new DxHlslCompilationStageResult(status, pipelineDebugLabel, null, detail);
	}
}
