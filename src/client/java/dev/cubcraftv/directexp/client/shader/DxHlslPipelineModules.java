package dev.cubcraftv.directexp.client.shader;

import java.util.Objects;

/**
 * Both shader stages of one {@code RenderPipeline}, translated to HLSL and
 * compiled to D3D11 shader bytecode -- Milestone 4.5's end product. This
 * is what Milestone 4.6's pipeline object assembly consumes to create the
 * actual {@code ID3D11VertexShader}/{@code ID3D11PixelShader} objects,
 * the same way {@link DxSpirvPipelineModules} fed Milestone 4.4's
 * reflection stage.
 */
public record DxHlslPipelineModules(
	String pipelineDebugLabel,
	DxHlslModule vertex,
	DxHlslModule fragment
) {
	public DxHlslPipelineModules {
		Objects.requireNonNull(vertex, "vertex");
		Objects.requireNonNull(fragment, "fragment");
	}
}
