package dev.cubcraftv.directexp.client.shader;

/**
 * Result of running Milestone 4.3's IR stage for one pipeline. Follows the
 * same record+status pair shape as {@code DxShaderSourceStageResult}:
 * {@code modules} is non-null if and only if {@code status == READY}.
 */
public record DxIrCompilationStageResult(
	DxIrCompilationStageStatus status,
	String pipelineDebugLabel,
	DxSpirvPipelineModules modules,
	String detail
) {
	public boolean isReady() {
		return this.status == DxIrCompilationStageStatus.READY && this.modules != null;
	}

	public static DxIrCompilationStageResult ready(String pipelineDebugLabel, DxSpirvPipelineModules modules) {
		return new DxIrCompilationStageResult(DxIrCompilationStageStatus.READY, pipelineDebugLabel, modules, null);
	}

	public static DxIrCompilationStageResult failed(DxIrCompilationStageStatus status, String pipelineDebugLabel, String detail) {
		if (status == DxIrCompilationStageStatus.READY) {
			throw new IllegalArgumentException("failed() cannot be used with READY; use ready() instead.");
		}

		return new DxIrCompilationStageResult(status, pipelineDebugLabel, null, detail);
	}
}
