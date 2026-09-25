package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * One shader stage's source after Milestone 4.2's Source and
 * Preprocessing Stage has run: the raw GLSL text {@link ShaderSource}
 * returned, and the same text after define injection
 * ({@link DxGlslDefinesInjector}). {@code type} is always one of
 * {@link ShaderType#VERTEX} or {@link ShaderType#FRAGMENT} here --
 * {@link DxShaderSourceStage} normalizes both stages through the same
 * loading path and tags the result with which stage it came from, the
 * same distinction the existing Vulkan path keys its shader cache on
 * ({@code VulkanDevice.ShaderCompilationKey}).
 */
public record DxPreprocessedShaderSource(
	Identifier id,
	ShaderType type,
	String rawSource,
	String preprocessedSource
) {
	public DxPreprocessedShaderSource {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(rawSource, "rawSource");
		Objects.requireNonNull(preprocessedSource, "preprocessedSource");
	}
}
