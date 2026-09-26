package dev.cubcraftv.directexp.client.shader;

import java.util.Objects;

/**
 * Both shader stages of one {@code RenderPipeline}, compiled to SPIR-V and
 * ready for the Reflection and Binding Stage (Milestone 4.4). This is what
 * replaces Milestone 4.1's hardcoded smoke-test SPIR-V as real input once
 * 4.4 lands, the same way {@link DxPreprocessedShaderPipelineSource}
 * replaced the 4.1 smoke-test GLSL string for 4.3.
 */
public record DxSpirvPipelineModules(
	String pipelineDebugLabel,
	DxSpirvModule vertex,
	DxSpirvModule fragment
) {
	public DxSpirvPipelineModules {
		Objects.requireNonNull(vertex, "vertex");
		Objects.requireNonNull(fragment, "fragment");
	}
}
