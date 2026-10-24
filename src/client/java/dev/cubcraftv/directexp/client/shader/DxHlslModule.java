package dev.cubcraftv.directexp.client.shader;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * One shader stage's D3D11 shader bytecode, produced by
 * {@link DxHlslCompilationStage} from a {@link DxSpirvModule} plus a
 * {@link DxResourceLayout}. Mirrors {@link DxSpirvModule}'s shape
 * ({@code id}/{@code type} carried alongside the payload). {@code hlsl}
 * is kept alongside the compiled {@code bytecode} rather than discarded
 * once compilation succeeds -- it is the exact HLSL text
 * {@code D3DCompile} was given, useful for diagnosing anything downstream
 * even on a stage that otherwise succeeded.
 */
public record DxHlslModule(
	Identifier id,
	ShaderType type,
	byte[] bytecode,
	String hlsl
) {
	public DxHlslModule {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(bytecode, "bytecode");
	}
}
