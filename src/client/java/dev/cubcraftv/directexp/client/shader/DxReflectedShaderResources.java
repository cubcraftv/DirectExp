package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderType;
import dev.cubcraftv.directexp.bridge.DxReflectedResource;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Objects;

/**
 * One shader stage's SPIR-V-reflected resources, produced by
 * {@link DxReflectionStage} from a {@link DxSpirvModule}. Mirrors
 * {@link DxSpirvModule}'s own shape ({@code id}/{@code type} carried
 * alongside the payload) so {@link DxResourceLayoutBuilder} can report
 * which stage(s) a given engine resource is used in, the same tagging
 * convention {@link DxPreprocessedShaderSource} and {@link DxSpirvModule}
 * already use for their own stages.
 */
public record DxReflectedShaderResources(
	Identifier id,
	ShaderType type,
	List<DxReflectedResource> resources
) {
	public DxReflectedShaderResources {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(resources, "resources");
		resources = List.copyOf(resources);
	}
}
