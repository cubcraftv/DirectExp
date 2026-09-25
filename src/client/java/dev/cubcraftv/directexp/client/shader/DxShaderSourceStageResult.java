package dev.cubcraftv.directexp.client.shader;

/**
 * Result of running Milestone 4.2's source/preprocessing stage for one
 * pipeline. Follows the same record+status pair shape as
 * {@code DxBridgeLoadResult}/{@code DxBridgeLoadStatus}: {@code source} is
 * non-null if and only if {@code status == READY}.
 */
public record DxShaderSourceStageResult(
	DxShaderSourceStatus status,
	String pipelineDebugLabel,
	DxPreprocessedShaderPipelineSource source,
	String detail
) {
	public boolean isReady() {
		return this.status == DxShaderSourceStatus.READY && this.source != null;
	}

	public static DxShaderSourceStageResult ready(String pipelineDebugLabel, DxPreprocessedShaderPipelineSource source) {
		return new DxShaderSourceStageResult(DxShaderSourceStatus.READY, pipelineDebugLabel, source, null);
	}

	public static DxShaderSourceStageResult missing(DxShaderSourceStatus status, String pipelineDebugLabel, String detail) {
		if (status == DxShaderSourceStatus.READY) {
			throw new IllegalArgumentException("missing() cannot be used with READY; use ready() instead.");
		}

		return new DxShaderSourceStageResult(status, pipelineDebugLabel, null, detail);
	}
}
