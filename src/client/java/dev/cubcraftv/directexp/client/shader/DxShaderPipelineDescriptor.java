package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * The subset of a Blaze3D {@link RenderPipeline} that the Milestone 4.2
 * source/preprocessing stage actually needs: the vertex and fragment
 * shader identifiers ({@code pipeline.getVertexShader()} /
 * {@code pipeline.getFragmentShader()}) and the shader defines
 * ({@code pipeline.getShaderDefines()}), which is exactly what the
 * existing Vulkan path keys its own shader-compilation cache on -- see
 * {@code VulkanDevice.ShaderCompilationKey} in the decompiled
 * {@code com.mojang.blaze3d.vulkan} package this milestone was verified
 * against.
 * <p>
 * Kept separate from {@link RenderPipeline} itself (rather than having
 * {@link DxShaderSourceStage} take a {@code RenderPipeline} directly) so
 * this stage's core logic stays testable with plain constructed values in
 * a JVM-only unit test, instead of requiring a fully registry-backed
 * {@code RenderPipeline} instance -- see
 * docs/plans/2026-09-25-directexp-milestone4-breakdown.md's Milestone 4.2
 * "Unit-testable in pure Java/JVM terms" requirement. {@link #of} is the
 * real, RenderPipeline-backed adapter used once this stage is wired into
 * the actual pipeline-compilation path (Milestone 4.6).
 */
public record DxShaderPipelineDescriptor(
	Identifier vertexShader,
	Identifier fragmentShader,
	ShaderDefines shaderDefines,
	String debugLabel
) {
	public DxShaderPipelineDescriptor {
		Objects.requireNonNull(vertexShader, "vertexShader");
		Objects.requireNonNull(fragmentShader, "fragmentShader");
	}

	/**
	 * Adapts a real Blaze3D {@link RenderPipeline} to this descriptor.
	 * {@code debugLabel} comes from {@code pipeline.getLocation()} via
	 * {@link String#valueOf}, matching how the Vulkan path identifies a
	 * pipeline in its own log/crash messages (e.g.
	 * {@code "Can't compile pipeline " + pipeline.getLocation()}).
	 */
	public static DxShaderPipelineDescriptor of(RenderPipeline pipeline) {
		Objects.requireNonNull(pipeline, "pipeline");
		return new DxShaderPipelineDescriptor(
			pipeline.getVertexShader(),
			pipeline.getFragmentShader(),
			pipeline.getShaderDefines(),
			String.valueOf(pipeline.getLocation())
		);
	}
}
