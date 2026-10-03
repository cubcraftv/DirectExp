package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.BindGroupLayout;

import java.util.List;
import java.util.Objects;

/**
 * The resource names declared by the bind-group layouts attached to a
 * Blaze3D {@link RenderPipeline}.
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
 * Samplers and uniforms are flattened using the 26.2 release's
 * {@link BindGroupLayout} helpers.
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
		List<BindGroupLayout> layouts = pipeline.getBindGroupLayouts();
		List<String> uniformBlocks = BindGroupLayout.flattenUniforms(layouts).stream()
			.map(BindGroupLayout.UniformDescription::name)
			.toList();
		return new DxPipelineResourceDeclarations(BindGroupLayout.flattenSamplers(layouts), uniformBlocks);
	}

	public static DxPipelineResourceDeclarations none() {
		return new DxPipelineResourceDeclarations(List.of(), List.of());
	}
}
