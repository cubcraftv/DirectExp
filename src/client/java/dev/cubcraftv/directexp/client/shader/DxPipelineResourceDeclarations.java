package dev.cubcraftv.directexp.client.shader;


import java.util.List;
import java.util.Objects;

/**
 * The resource names declared by the bind-group layouts attached to a
 * Renderpearl pipeline.
 * These are the names {@link com.mojang.renderpearl.backend.api.RenderPassBackend#bindTexture}
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
 * Built by DxPipelineCompiler from the pipeline's uniform list.
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

	public static DxPipelineResourceDeclarations none() {
		return new DxPipelineResourceDeclarations(List.of(), List.of());
	}
}
