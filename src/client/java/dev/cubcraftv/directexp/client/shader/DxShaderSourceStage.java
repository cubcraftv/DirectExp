package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import dev.cubcraftv.directexp.DirectExpMod;
import net.minecraft.resources.Identifier;

import java.util.Objects;

/**
 * Milestone 4 / Milestone 4.2 ("Source and Preprocessing Stage"): loads
 * real Mojang GLSL shader source for a render pipeline and applies the
 * same preprocessing/define-injection step the modern Vulkan path already
 * uses, so later Milestone 4 stages (4.3+) have real preprocessed GLSL
 * input instead of Milestone 4.1's hardcoded smoke-test string.
 * <p>
 * This stage is deliberately Java-only and native-independent: it never
 * touches {@code DxBridge} or any native call. It stops at preprocessed
 * GLSL text -- turning that into SPIR-V is Milestone 4.3's job.
 * <p>
 * Confirmed against the decompiled {@code com.mojang.blaze3d.vulkan}
 * package for Minecraft {@code 26.2} release, specifically
 * {@code VulkanDevice}:
 * <pre>
 * private IntermediaryShaderModule compileShader(ShaderCompilationKey key, ShaderSource shaderSource) {
 *    String source = shaderSource.get(key.id, key.type);
 *    if (source == null) {
 *       LOGGER.error("Couldn't find source for {} shader ({})", key.type, key.id);
 *       return IntermediaryShaderModule.INVALID;
 *    }
 *    String sourceWithDefines = GlslPreprocessor.injectDefines(source, key.defines);
 *    ...
 * }
 * </pre>
 * This stage mirrors that exactly: {@link ShaderSource#get(Identifier, ShaderType)}
 * for lookup, then the same {@code GlslPreprocessor.injectDefines} call
 * (via {@link DxGlslDefinesInjector#mojangDefault()}) for preprocessing --
 * per docs/research/2026-09-25-directexp-milestone4-shader-toolchain-research.md
 * section 4, this was previously "still genuinely unknown" without the
 * actual Mojang/Vulkan source to check against, so it is not assumed here.
 * <p>
 * "Normalize stage-specific input (vertex vs. fragment) before it's
 * handed to compilation" (the design spec's Source Stage requirement) is
 * satisfied by {@link #loadStage}: the same code path handles both
 * {@link ShaderType#VERTEX} and {@link ShaderType#FRAGMENT}, and the
 * result is explicitly tagged with which one it is, rather than the two
 * stages being handled by separate, potentially-diverging code.
 */
public final class DxShaderSourceStage {
	private static final Identifier EARLY_TEXTURED_GUI_SHADER = Identifier.fromNamespaceAndPath("minecraft", "core/position_tex_color");
	private static final String EARLY_TEXTURED_GUI_VERTEX = """
		#version 330
		layout(std140) uniform DynamicTransforms {
		    mat4 ModelViewMat;
		    vec4 ColorModulator;
		    vec3 ModelOffset;
		    mat4 TextureMat;
		};
		layout(std140) uniform Projection {
		    mat4 ProjMat;
		};
		in vec3 Position;
		in vec2 UV0;
		in vec4 Color;
		out vec2 texCoord0;
		out vec4 vertexColor;
		void main() {
		    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
		    texCoord0 = UV0;
		    vertexColor = Color;
		}
		""";
	private static final String EARLY_TEXTURED_GUI_FRAGMENT = """
		#version 330
		layout(std140) uniform DynamicTransforms {
		    mat4 ModelViewMat;
		    vec4 ColorModulator;
		    vec3 ModelOffset;
		    mat4 TextureMat;
		};
		uniform sampler2D Sampler0;
		in vec2 texCoord0;
		in vec4 vertexColor;
		out vec4 fragColor;
		void main() {
		    vec4 color = texture(Sampler0, texCoord0) * vertexColor;
		    if (color.a == 0.0) {
		        discard;
		    }
		    fragColor = color * ColorModulator;
		}
		""";

	private DxShaderSourceStage() {
	}

	/**
	 * Real entry point: adapts {@code pipeline} via
	 * {@link DxShaderPipelineDescriptor#of(RenderPipeline)} and uses the
	 * real Mojang {@code GlslPreprocessor.injectDefines} call.
	 */
	public static DxShaderSourceStageResult load(RenderPipeline pipeline, ShaderSource shaderSource) {
		Objects.requireNonNull(pipeline, "pipeline");
		return load(DxShaderPipelineDescriptor.of(pipeline), shaderSource, DxGlslDefinesInjector.mojangDefault());
	}

	/**
	 * Core entry point. Takes an already-extracted
	 * {@link DxShaderPipelineDescriptor} and an injectable
	 * {@link DxGlslDefinesInjector} so this stage's actual logic --
	 * source lookup, missing-source handling, and per-stage
	 * normalization -- is unit-testable in pure Java/JVM terms, without a
	 * fully registry-backed {@code RenderPipeline} or a real
	 * {@code ShaderDefines}/{@code GlslPreprocessor} call.
	 */
	public static DxShaderSourceStageResult load(DxShaderPipelineDescriptor descriptor, ShaderSource shaderSource, DxGlslDefinesInjector injector) {
		Objects.requireNonNull(descriptor, "descriptor");
		Objects.requireNonNull(shaderSource, "shaderSource");
		Objects.requireNonNull(injector, "injector");

		DxPreprocessedShaderSource vertex = loadStage(descriptor, ShaderType.VERTEX, descriptor.vertexShader(), shaderSource, injector);
		if (vertex == null) {
			return DxShaderSourceStageResult.missing(
				DxShaderSourceStatus.VERTEX_SOURCE_MISSING,
				descriptor.debugLabel(),
				"No vertex shader source for " + descriptor.vertexShader()
			);
		}

		DxPreprocessedShaderSource fragment = loadStage(descriptor, ShaderType.FRAGMENT, descriptor.fragmentShader(), shaderSource, injector);
		if (fragment == null) {
			return DxShaderSourceStageResult.missing(
				DxShaderSourceStatus.FRAGMENT_SOURCE_MISSING,
				descriptor.debugLabel(),
				"No fragment shader source for " + descriptor.fragmentShader()
			);
		}

		DxPreprocessedShaderPipelineSource pipelineSource = new DxPreprocessedShaderPipelineSource(descriptor.debugLabel(), vertex, fragment);
		return DxShaderSourceStageResult.ready(descriptor.debugLabel(), pipelineSource);
	}

	/**
	 * Handles exactly one shader stage (vertex or fragment). Called
	 * identically for both from {@link #load} -- this is the
	 * "normalize stage-specific input" step: the same lookup and
	 * preprocessing logic runs regardless of which stage it is, and the
	 * only thing that varies per call is the {@link ShaderType} and
	 * {@link Identifier} passed in, with the output explicitly tagged by
	 * {@code type} for downstream stages to dispatch on.
	 */
	private static DxPreprocessedShaderSource loadStage(
		DxShaderPipelineDescriptor descriptor,
		ShaderType type,
		Identifier id,
		ShaderSource shaderSource,
		DxGlslDefinesInjector injector
	) {
		String rawSource = shaderSource.get(id, type);
		if (rawSource == null) {
			rawSource = earlyShaderSource(id, type);
			if (rawSource == null) {
				DirectExpMod.LOGGER.error(
					"Couldn't find source for {} shader ({}) in pipeline {}",
					type, id, descriptor.debugLabel()
				);
				return null;
			}
			DirectExpMod.LOGGER.warn("Using bundled startup fallback for {} shader ({}) in pipeline {}.", type, id, descriptor.debugLabel());
		}

		String preprocessedSource = injector.injectDefines(rawSource, descriptor.shaderDefines());
		return new DxPreprocessedShaderSource(id, type, rawSource, preprocessedSource);
	}

	private static String earlyShaderSource(Identifier id, ShaderType type) {
		if (!EARLY_TEXTURED_GUI_SHADER.equals(id)) {
			return null;
		}
		return switch (type) {
			case VERTEX -> EARLY_TEXTURED_GUI_VERTEX;
			case FRAGMENT -> EARLY_TEXTURED_GUI_FRAGMENT;
			default -> null;
		};
	}
}
