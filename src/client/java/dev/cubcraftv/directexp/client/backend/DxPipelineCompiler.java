package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.SpvModule;
import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxPipelineStateDescriptor;
import dev.cubcraftv.directexp.client.shader.DxHlslCompilationStage;
import dev.cubcraftv.directexp.client.shader.DxHlslCompilationStageResult;
import dev.cubcraftv.directexp.client.shader.DxPipelineResourceDeclarations;
import dev.cubcraftv.directexp.client.shader.DxReflectionStage;
import dev.cubcraftv.directexp.client.shader.DxReflectionStageResult;
import dev.cubcraftv.directexp.client.shader.DxResourceLayout;
import dev.cubcraftv.directexp.client.shader.DxResourceLayoutBuilder;
import dev.cubcraftv.directexp.client.shader.DxSpirvModule;
import dev.cubcraftv.directexp.client.shader.DxSpirvPipelineModules;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Turns a Renderpearl {@link BackendRenderPipeline.CreateInfo} into a native
 * D3D11 pipeline. Since 26.3 the frontend has already done the GLSL work
 * (Shaderc to SPIR-V, location/binding assignment, vertex/varying checks), so
 * the chain here starts at SPIR-V:
 * reflection (4.4) -> resource layout (4.4) -> HLSL + bytecode (4.5) ->
 * {@link DxBridge#createPipeline}.
 *
 * <p>Never throws: every failure is logged and returns {@code null}, which is
 * what {@code BackendRenderPipeline.Pending.finishCompile} reports to the
 * frontend for a pipeline that cannot be built.
 */
final class DxPipelineCompiler {
	private DxPipelineCompiler() {
	}

	/**
	 * Copies the SPIR-V out of the create info. Must run before the frontend
	 * closes its modules (it does so right after {@code finishCompile}), and is
	 * cheap, so the device backend calls it on the compile thread.
	 */
	static DxSpirvPipelineModules copySpirv(BackendRenderPipeline.CreateInfo info) {
		DxSpirvModule vertex = null;
		DxSpirvModule fragment = null;
		for (BackendRenderPipeline.CreateInfo.Shader shader : info.shaders()) {
			SpvModule module = shader.module();
			DxSpirvModule copy = new DxSpirvModule(identifierOf(shader.name()), module.type(), toBytes(module.spv()));
			if (module.type() == ShaderType.VERTEX) {
				vertex = copy;
			} else if (module.type() == ShaderType.FRAGMENT) {
				fragment = copy;
			}
		}
		if (vertex == null || fragment == null) {
			throw new UnsupportedOperationException("a pipeline needs a vertex and a fragment shader");
		}
		return new DxSpirvPipelineModules(info.name(), vertex, fragment);
	}

	static DxRenderPipeline compile(BackendRenderPipeline.CreateInfo info, DxSpirvPipelineModules spirv, DxBridge bridge) {
		String name = info.name();
		try {
			DxReflectionStageResult reflection = DxReflectionStage.reflect(spirv, bridge);
			if (!reflection.isReady()) {
				return fail(name, "reflection: " + reflection.status() + " (" + reflection.detail() + ")");
			}

			DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection.reflection(), declarationsOf(info));

			DxHlslCompilationStageResult hlsl = DxHlslCompilationStage.compile(spirv, layout, bridge);
			if (!hlsl.isReady()) {
				return fail(name, "HLSL: " + hlsl.status() + " (" + hlsl.detail() + ")");
			}

			dumpHlslForDiagnostics(name, hlsl);

			DxPipelineStateDescriptor state;
			try {
				state = DxPipelineStateMapping.describe(info);
			} catch (UnsupportedOperationException error) {
				// describe() only throws this for state D3D11 cannot express yet
				// (multiple vertex-buffer bindings, multiple color targets, ...).
				// 26.3's compilation contract turns a null finishCompile into a
				// failed reload for the engine's *required* pipelines, so an
				// unsupported pipeline must stay a valid pipeline object that the
				// draw path skips -- see DxRenderPipeline.unsupported. Layout
				// reflection and HLSL have already succeeded here, so the object
				// carries the real layout/uniforms.
				DirectExpMod.LOGGER.warn(
					"DirectExp: pipeline {} uses state this backend does not support ({}); draws using it are skipped.",
					name,
					error.getMessage()
				);
				return DxRenderPipeline.unsupported(name, bridge, error.getMessage(), layout, info.primitiveTopology(), info.uniforms());
			}

			long handle = bridge.createPipeline(hlsl.modules().vertex().bytecode(), hlsl.modules().fragment().bytecode(), state);
			return new DxRenderPipeline(name, bridge, handle, layout, info.primitiveTopology(), info.uniforms());
		} catch (UnsupportedOperationException | IllegalStateException error) {
			return fail(name, "pipeline objects: " + error.getMessage());
		} catch (RuntimeException | LinkageError error) {
			return fail(name, "unexpected " + error.getClass().getSimpleName() + ": " + error.getMessage());
		}
	}

	private static DxPipelineResourceDeclarations declarationsOf(BackendRenderPipeline.CreateInfo info) {
		List<String> samplers = info.uniforms().stream()
			.filter(uniform -> uniform.type() == com.mojang.renderpearl.api.pipeline.UniformType.COMBINED_IMAGE_SAMPLER
				|| uniform.type() == com.mojang.renderpearl.api.pipeline.UniformType.TEXEL_BUFFER)
			.map(com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription::name)
			.toList();
		List<String> uniformBlocks = info.uniforms().stream()
			.filter(uniform -> uniform.type() == com.mojang.renderpearl.api.pipeline.UniformType.UNIFORM_BUFFER)
			.map(com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription::name)
			.toList();
		return new DxPipelineResourceDeclarations(samplers, uniformBlocks);
	}

	private static Identifier identifierOf(String name) {
		Identifier parsed = Identifier.tryParse(name);
		return parsed != null ? parsed : Identifier.fromNamespaceAndPath("directexp", "shader");
	}

	private static byte[] toBytes(ByteBuffer spv) {
		ByteBuffer copy = spv.duplicate();
		byte[] bytes = new byte[copy.remaining()];
		copy.get(bytes);
		return bytes;
	}

	private static DxRenderPipeline fail(String pipelineName, String reason) {
		DirectExpMod.LOGGER.warn("DirectExp: pipeline {} is invalid: {}", pipelineName, reason);
		return null;
	}

	/** Diagnostics: writes the generated HLSL of the sprite-atlas blit and terrain pipelines to ./directexp-hlsl/. */
	private static void dumpHlslForDiagnostics(String name, DxHlslCompilationStageResult hlsl) {
		if (!(name.contains("animate_sprite") || name.contains("solid_terrain"))) {
			return;
		}
		if (name.contains("animate_sprite_blit")) {
			DirectExpMod.LOGGER.info("DirectExp HLSL DUMP {} VERTEX:\n{}", name, hlsl.modules().vertex().hlsl());
			DirectExpMod.LOGGER.info("DirectExp HLSL DUMP {} FRAGMENT:\n{}", name, hlsl.modules().fragment().hlsl());
		}
		try {
			java.nio.file.Path directory = java.nio.file.Path.of("directexp-hlsl");
			java.nio.file.Files.createDirectories(directory);
			String base = name.replaceAll("[^A-Za-z0-9_.-]", "_");
			java.nio.file.Files.writeString(directory.resolve(base + ".vs.hlsl"), hlsl.modules().vertex().hlsl());
			java.nio.file.Files.writeString(directory.resolve(base + ".ps.hlsl"), hlsl.modules().fragment().hlsl());
		} catch (java.io.IOException | RuntimeException error) {
			DirectExpMod.LOGGER.warn("DirectExp: could not dump diagnostic HLSL for pipeline {}.", name, error);
		}
	}
}
