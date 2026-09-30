package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import dev.cubcraftv.directexp.bridge.DxBridge;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.cubcraftv.directexp.bridge.DxHlslCompilationResult;
import dev.cubcraftv.directexp.bridge.DxPipelineStateDescriptor;
import dev.cubcraftv.directexp.bridge.DxShaderStageKind;
import dev.cubcraftv.directexp.client.shader.DxHlslCompilationStage;
import dev.cubcraftv.directexp.client.shader.DxHlslCompilationStageResult;
import dev.cubcraftv.directexp.client.shader.DxIrCompilationStage;
import dev.cubcraftv.directexp.client.shader.DxIrCompilationStageResult;
import dev.cubcraftv.directexp.client.shader.DxPipelineResourceDeclarations;
import dev.cubcraftv.directexp.client.shader.DxPointSizeRewriter;
import dev.cubcraftv.directexp.client.shader.DxPointSpriteGeometryShader;
import dev.cubcraftv.directexp.client.shader.DxPreprocessedShaderPipelineSource;
import dev.cubcraftv.directexp.client.shader.DxPreprocessedShaderSource;
import dev.cubcraftv.directexp.client.shader.DxReflectionStage;
import dev.cubcraftv.directexp.client.shader.DxReflectionStageResult;
import dev.cubcraftv.directexp.client.shader.DxResourceLayout;
import dev.cubcraftv.directexp.client.shader.DxResourceLayoutBuilder;
import dev.cubcraftv.directexp.client.shader.DxShaderInterfaceException;
import dev.cubcraftv.directexp.client.shader.DxShaderInterfaceLayout;
import dev.cubcraftv.directexp.client.shader.DxShaderSourceStage;
import dev.cubcraftv.directexp.client.shader.DxShaderSourceStageResult;
import dev.cubcraftv.directexp.client.shader.DxSpirvCache;

/**
 * Chains Milestone 4.1-4.5's stages into one pipeline compile and finishes
 * with 4.6's native object assembly:
 * source (4.2) -> interface layout (4.6) -> SPIR-V (4.3) -> reflection (4.4)
 * -> resource layout (4.4) -> HLSL + bytecode (4.5) -> {@link DxBridge#createPipeline}.
 *
 * <p>The interface-layout step ({@link DxShaderInterfaceLayout}) gives every
 * vertex input / varying / uniform block / sampler an explicit location or
 * binding before {@code shaderc} sees the text, the equivalent of what the
 * Vulkan backend's {@code GlslCompiler} does; without it every vanilla
 * pipeline fails to compile.
 *
 * <p>{@code gl_PointSize}: SPIR-V's PointSize builtin has no HLSL output, so
 * {@link DxPointSizeRewriter} turns it into an ordinary vertex output before
 * shaderc, and for {@code POINTS} pipelines {@link DxPointSpriteGeometryShader}
 * generates a geometry shader that expands each point to a quad of that many
 * pixels. {@code TRIANGLE_FAN} needs nothing here: the pipeline is a plain
 * triangle list and the draw path expands the indices
 * ({@link DxTriangleFanExpander}).
 *
 * <p>Never throws: every failure (a stage reporting non-READY, a mapping
 * that D3D11 can't express, a native exception, or anything unexpected)
 * becomes an invalid {@link DxRenderPipeline} whose reason names the stage,
 * so one bad pipeline can't take the whole device down.
 */
final class DxPipelineCompiler {
	private DxPipelineCompiler() {
	}

	static DxRenderPipeline compile(RenderPipeline pipeline, ShaderSource shaderSource, DxBridge bridge, DxSpirvCache spirvCache) {
		if (bridge == null) {
			return DxRenderPipeline.invalid(pipeline, "no DirectX bridge");
		}

		try {
			DxShaderSourceStageResult source = DxShaderSourceStage.load(pipeline, shaderSource);
			if (!source.isReady()) {
				return DxRenderPipeline.invalid(pipeline, "shader source: " + source.status() + " (" + source.detail() + ")");
			}

			// Vertex input location = element index in the vertex format (as the
			// input layout in createPipeline and the Vulkan backend both assume).
			DxPointSizeRewriter.Result pointSize = DxPointSizeRewriter.rewrite(source.source().vertex().preprocessedSource());
			DxPreprocessedShaderPipelineSource layoutInput = source.source();
			if (pointSize.usesPointSize()) {
				DxPreprocessedShaderSource vertex = layoutInput.vertex();
				layoutInput = new DxPreprocessedShaderPipelineSource(
					layoutInput.pipelineDebugLabel(),
					new DxPreprocessedShaderSource(vertex.id(), vertex.type(), vertex.rawSource(), pointSize.source()),
					layoutInput.fragment()
				);
			}

			DxPreprocessedShaderPipelineSource laidOut;
			try {
				laidOut = DxShaderInterfaceLayout.apply(layoutInput, pipeline.getVertexFormat().getElementAttributeNames());
			} catch (DxShaderInterfaceException error) {
				return DxRenderPipeline.invalid(pipeline, "shader interface: " + error.getMessage());
			}

			DxIrCompilationStageResult spirv = DxIrCompilationStage.compile(laidOut, bridge, spirvCache);
			if (!spirv.isReady()) {
				return DxRenderPipeline.invalid(pipeline, "GLSL->SPIR-V: " + spirv.status() + " (" + spirv.detail() + ")");
			}

			DxReflectionStageResult reflection = DxReflectionStage.reflect(spirv.modules(), bridge);
			if (!reflection.isReady()) {
				return DxRenderPipeline.invalid(pipeline, "reflection: " + reflection.status() + " (" + reflection.detail() + ")");
			}

			DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection.reflection(), DxPipelineResourceDeclarations.of(pipeline));

			DxHlslCompilationStageResult hlsl = DxHlslCompilationStage.compile(spirv.modules(), layout, bridge);
			if (!hlsl.isReady()) {
				return DxRenderPipeline.invalid(pipeline, "HLSL: " + hlsl.status() + " (" + hlsl.detail() + ")");
			}

			byte[] geometryBytecode = null;
			if (pointSize.usesPointSize() && pipeline.getVertexFormatMode() == VertexFormat.Mode.POINTS) {
				String geometryHlsl;
				try {
					geometryHlsl = DxPointSpriteGeometryShader.generate(hlsl.modules().vertex().hlsl(), DxBridge.GEOMETRY_VIEWPORT_CBUFFER_SLOT);
				} catch (DxShaderInterfaceException error) {
					return DxRenderPipeline.invalid(pipeline, "point size geometry shader: " + error.getMessage());
				}

				DxHlslCompilationResult geometry = bridge.compileHlsl(pipeline.getLocation() + "/GEOMETRY/point_sprite", geometryHlsl, DxShaderStageKind.GEOMETRY);
				if (!geometry.isReady()) {
					return DxRenderPipeline.invalid(pipeline, "point size geometry shader: " + geometry.status() + " (" + geometry.detail() + ")");
				}
				geometryBytecode = geometry.bytecode();
			}

			DxPipelineStateDescriptor state = DxPipelineStateMapping.describe(pipeline);
			long handle = bridge.createPipeline(hlsl.modules().vertex().bytecode(), hlsl.modules().fragment().bytecode(), geometryBytecode, state);
			return DxRenderPipeline.valid(pipeline, bridge, handle, layout, geometryBytecode != null);
		} catch (UnsupportedOperationException | IllegalStateException error) {
			return DxRenderPipeline.invalid(pipeline, "pipeline objects: " + error.getMessage());
		} catch (RuntimeException | LinkageError error) {
			// A stage misbehaving must not escape into Blaze3D's pipeline precompile.
			return DxRenderPipeline.invalid(pipeline, "unexpected " + error.getClass().getSimpleName() + ": " + error.getMessage());
		}
	}
}
