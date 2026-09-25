package dev.cubcraftv.directexp.client.shader;

import java.util.Objects;

/**
 * Both shader stages of one {@code RenderPipeline}, preprocessed and
 * ready for the IR stage (Milestone 4.3: real GLSL -> SPIR-V through the
 * native bridge). This is what replaces 4.1's hardcoded smoke-test GLSL
 * string as real input once 4.3 lands.
 */
public record DxPreprocessedShaderPipelineSource(
	String pipelineDebugLabel,
	DxPreprocessedShaderSource vertex,
	DxPreprocessedShaderSource fragment
) {
	public DxPreprocessedShaderPipelineSource {
		Objects.requireNonNull(vertex, "vertex");
		Objects.requireNonNull(fragment, "fragment");
	}
}
