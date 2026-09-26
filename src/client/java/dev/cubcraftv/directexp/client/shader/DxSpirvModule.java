package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * One shader stage's compiled SPIR-V, produced by Milestone 4.3's IR
 * stage ({@link DxIrCompilationStage}) from Milestone 4.2's preprocessed
 * GLSL source ({@link DxPreprocessedShaderSource}). {@code spirv} is raw
 * SPIR-V words as bytes (host-endian; see
 * directexp_shader_toolchain.cpp's {@code BuildIrCompilationResult}) --
 * opaque from this project's Java side, only ever handed back to the
 * native bridge for later stages (Milestone 4.4's reflection, Milestone
 * 4.5's HLSL emission).
 * <p>
 * Mirrors {@link DxPreprocessedShaderSource}'s shape ({@code id}/
 * {@code type} carried alongside the payload) so downstream stages can
 * keep tagging output by which stage it came from, the same distinction
 * the existing Vulkan path keys its shader cache on.
 */
public record DxSpirvModule(
	Identifier id,
	ShaderType type,
	byte[] spirv
) {
	public DxSpirvModule {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(spirv, "spirv");
	}
}
