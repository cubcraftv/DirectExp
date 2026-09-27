package dev.cubcraftv.directexp.client.shader;

import java.util.Objects;

/**
 * Both shader stages of one {@code RenderPipeline}, reflected via
 * SPIRV-Cross and ready for {@link DxResourceLayoutBuilder} to combine
 * with Blaze3D's pipeline declarations ({@link DxPipelineResourceDeclarations})
 * into Milestone 4.4's engine-owned {@link DxResourceLayout}. This is what
 * replaces the "one flat resource list" a naive implementation might
 * produce -- keeping vertex and fragment reflections separate here is
 * what lets the layout builder record, per engine resource, whether it is
 * used in the vertex stage, the fragment stage, or both.
 */
public record DxPipelineReflection(
	String pipelineDebugLabel,
	DxReflectedShaderResources vertex,
	DxReflectedShaderResources fragment
) {
	public DxPipelineReflection {
		Objects.requireNonNull(vertex, "vertex");
		Objects.requireNonNull(fragment, "fragment");
	}
}
