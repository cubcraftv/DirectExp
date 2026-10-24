package dev.cubcraftv.directexp.client.shader;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxReflectedResource;
import dev.cubcraftv.directexp.bridge.DxSpirvReflectionResult;

import java.util.List;
import java.util.Objects;

/**
 * Milestone 4 / Milestone 4.4 ("Reflection and Resource Layout Model"),
 * first half: reflects both shader stages of Milestone 4.3's
 * {@link DxSpirvPipelineModules} through the native bridge's real,
 * JNI-exposed {@link DxBridge#reflectSpirv} call.
 * <p>
 * This class only reflects -- it does not yet combine the result with
 * Blaze3D's pipeline declarations into the engine-owned layout model
 * (that is {@link DxResourceLayoutBuilder}, the second half of Milestone
 * 4.4, kept separate so each half stays independently testable: this
 * class's own logic is "did SPIRV-Cross reflect this SPIR-V
 * successfully", while the layout builder's is "how do these reflected
 * resources map to concrete engine slots").
 * <p>
 * Mirrors {@link DxIrCompilationStage}'s shape closely: a SPIRV-Cross
 * reflection failure is surfaced here as a distinct, structured
 * {@link DxReflectionStageStatus#VERTEX_REFLECTION_FAILED}/
 * {@link DxReflectionStageStatus#FRAGMENT_REFLECTION_FAILED} result
 * rather than an exception, and a bridge-level problem (native library
 * not loaded, entrypoint missing) is kept distinct as
 * {@link DxReflectionStageStatus#BRIDGE_UNAVAILABLE}.
 */
public final class DxReflectionStage {
	private DxReflectionStage() {
	}

	/**
	 * Reflects both stages of {@code modules}. Stops at the vertex
	 * stage's failure without attempting the fragment stage if the
	 * vertex stage itself failed to reflect, matching
	 * {@link DxIrCompilationStage#compile}'s short-circuit shape.
	 */
	public static DxReflectionStageResult reflect(DxSpirvPipelineModules modules, DxBridge bridge) {
		Objects.requireNonNull(modules, "modules");
		Objects.requireNonNull(bridge, "bridge");

		ReflectOutcome vertexOutcome = reflectStage(modules.pipelineDebugLabel(), modules.vertex(), bridge);
		if (vertexOutcome.resources() == null) {
			return DxReflectionStageResult.failed(vertexOutcome.status(), modules.pipelineDebugLabel(), vertexOutcome.detail());
		}

		ReflectOutcome fragmentOutcome = reflectStage(modules.pipelineDebugLabel(), modules.fragment(), bridge);
		if (fragmentOutcome.resources() == null) {
			return DxReflectionStageResult.failed(fragmentOutcome.status(), modules.pipelineDebugLabel(), fragmentOutcome.detail());
		}

		DxReflectedShaderResources vertexReflection = new DxReflectedShaderResources(
			modules.vertex().id(), modules.vertex().type(), vertexOutcome.resources()
		);
		DxReflectedShaderResources fragmentReflection = new DxReflectedShaderResources(
			modules.fragment().id(), modules.fragment().type(), fragmentOutcome.resources()
		);

		DxPipelineReflection reflection = new DxPipelineReflection(modules.pipelineDebugLabel(), vertexReflection, fragmentReflection);
		return DxReflectionStageResult.ready(modules.pipelineDebugLabel(), reflection);
	}

	/**
	 * Handles exactly one shader stage's {@link DxSpirvModule}. Called
	 * identically for both stages from {@link #reflect} -- the only
	 * thing that varies per call is which module is passed in, mirroring
	 * {@link DxIrCompilationStage#compileStage}'s "normalize
	 * stage-specific input" shape.
	 */
	private static ReflectOutcome reflectStage(String pipelineDebugLabel, DxSpirvModule module, DxBridge bridge) {
		DxSpirvReflectionResult bridgeResult = bridge.reflectSpirv(module.spirv());

		if (bridgeResult.isReady()) {
			return ReflectOutcome.success(bridgeResult.resources());
		}

		DirectExpMod.LOGGER.error(
			"SPIR-V reflection failed for {} shader ({}) in pipeline {}: {}",
			module.type(), module.id(), pipelineDebugLabel, bridgeResult.detail()
		);

		DxReflectionStageStatus status = bridgeResult.isReflectionError()
			? (module.type() == ShaderType.VERTEX ? DxReflectionStageStatus.VERTEX_REFLECTION_FAILED : DxReflectionStageStatus.FRAGMENT_REFLECTION_FAILED)
			: DxReflectionStageStatus.BRIDGE_UNAVAILABLE;

		return ReflectOutcome.failure(status, bridgeResult.detail());
	}

	/**
	 * Outcome of {@link #reflectStage} for one stage: exactly one of
	 * {@code resources}/{@code status+detail} is populated. Kept private
	 * and local to this class, same as {@code DxIrCompilationStage}'s own
	 * {@code CompileOutcome}.
	 */
	private record ReflectOutcome(List<DxReflectedResource> resources, DxReflectionStageStatus status, String detail) {
		static ReflectOutcome success(List<DxReflectedResource> resources) {
			return new ReflectOutcome(resources, null, null);
		}

		static ReflectOutcome failure(DxReflectionStageStatus status, String detail) {
			return new ReflectOutcome(null, status, detail);
		}
	}
}
