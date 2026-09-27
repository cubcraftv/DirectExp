package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import java.util.List;
import java.util.Objects;

/**
 * The resource names a Blaze3D {@link RenderPipeline} itself declares --
 * {@code pipeline.getSamplers()} (a {@code List<String>} of declared
 * sampler/texture names) and {@code pipeline.getUniforms()} (a
 * {@code List<RenderPipeline.UniformDescription>}, reduced here to just
 * the declared uniform block names via {@code UniformDescription.name()}).
 * These are the names {@link com.mojang.blaze3d.systems.RenderPassBackend#bindTexture}
 * and {@code setUniform} are called with at draw time -- see
 * {@code DxRenderPassBackend.bindTexture}/{@code setUniform}, which
 * already key their own bound-resource maps on these same {@code String}
 * names.
 * <p>
 * {@link DxResourceLayoutBuilder} cross-checks these declared names
 * against what SPIR-V reflection actually found ({@link DxPipelineReflection})
 * as a diagnostic-only step (see that class's javadoc for why a mismatch
 * is logged, not treated as a hard failure) -- this record does not
 * itself validate anything.
 * <p>
 * <b>Verification status:</b> {@code getSamplers()}/{@code getUniforms()}
 * are used here on the same confidence basis as
 * {@link DxShaderPipelineDescriptor#of}'s existing
 * {@code getVertexShader()}/{@code getFragmentShader()}/
 * {@code getShaderDefines()}/{@code getLocation()} calls, but -- unlike
 * Milestone 4.2's {@code GlslPreprocessor.injectDefines} call, which was
 * confirmed against a real decompile of this project's own Minecraft
 * version (see {@link DxShaderSourceStage}'s javadoc) -- this specific
 * pair of accessors and {@code UniformDescription}'s shape have not been
 * checked against a decompile for {@code 26.2-snapshot-1} in this
 * session. Confirm both against the decompiled
 * {@code com.mojang.blaze3d.pipeline.RenderPipeline} source in a live
 * session before leaning on this for anything beyond the diagnostic
 * cross-check {@link DxResourceLayoutBuilder} uses it for today.
 */
public record DxPipelineResourceDeclarations(
	List<String> samplers,
	List<String> uniformBlocks
) {
	public DxPipelineResourceDeclarations {
		Objects.requireNonNull(samplers, "samplers");
		Objects.requireNonNull(uniformBlocks, "uniformBlocks");
		samplers = List.copyOf(samplers);
		uniformBlocks = List.copyOf(uniformBlocks);
	}

	public static DxPipelineResourceDeclarations of(RenderPipeline pipeline) {
		Objects.requireNonNull(pipeline, "pipeline");
		List<String> uniformBlocks = pipeline.getUniforms().stream()
			.map(RenderPipeline.UniformDescription::name)
			.toList();
		return new DxPipelineResourceDeclarations(pipeline.getSamplers(), uniformBlocks);
	}

	public static DxPipelineResourceDeclarations none() {
		return new DxPipelineResourceDeclarations(List.of(), List.of());
	}
}
