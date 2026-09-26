package dev.cubcraftv.directexp.client.shader;

import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxIrCompilationResult;
import dev.cubcraftv.directexp.bridge.DxShaderStageKind;

import java.util.Objects;

/**
 * Milestone 4 / Milestone 4.3 ("IR Stage: Real GLSL -&gt; SPIR-V"): compiles
 * Milestone 4.2's real preprocessed GLSL source to SPIR-V through the
 * native bridge's real, JNI-exposed {@link DxBridge#compileGlslToSpirv}
 * call, replacing Milestone 4.1's hardcoded smoke-test string as the
 * pipeline's actual input.
 * <p>
 * A {@code shaderc} compile error (bad syntax, an unsupported construct)
 * is surfaced here as a distinct, structured
 * {@link DxIrCompilationStageStatus#VERTEX_COMPILE_FAILED}/
 * {@link DxIrCompilationStageStatus#FRAGMENT_COMPILE_FAILED} result rather
 * than an exception -- per the Milestone 4.3 checklist, this is "the
 * first stage where 'the shader failed to compile' becomes a real,
 * expected runtime case rather than a can't-happen". A bridge-level
 * problem (native library not loaded, entrypoint missing) is kept
 * distinct as {@link DxIrCompilationStageStatus#BRIDGE_UNAVAILABLE}.
 * <p>
 * Successful compiles are cached in the supplied {@link DxSpirvCache} so
 * repeated calls for the same shader identity and preprocessed source
 * don't re-invoke {@code shaderc} -- see that class's javadoc for why
 * this is deliberately a stepping stone, not Milestone 4.6's final
 * pipeline cache design.
 */
public final class DxIrCompilationStage {
	private DxIrCompilationStage() {
	}

	/**
	 * Compiles both stages of {@code source} to SPIR-V. Stops at the
	 * vertex stage's failure without attempting the fragment stage if the
	 * vertex stage itself failed to compile, matching
	 * {@link DxShaderSourceStage#load}'s short-circuit shape for a
	 * missing vertex source.
	 */
	public static DxIrCompilationStageResult compile(DxPreprocessedShaderPipelineSource source, DxBridge bridge, DxSpirvCache cache) {
		Objects.requireNonNull(source, "source");
		Objects.requireNonNull(bridge, "bridge");
		Objects.requireNonNull(cache, "cache");

		CompileOutcome vertexOutcome = compileStage(source.pipelineDebugLabel(), source.vertex(), DxShaderStageKind.VERTEX, bridge, cache);
		if (vertexOutcome.module() == null) {
			return DxIrCompilationStageResult.failed(vertexOutcome.status(), source.pipelineDebugLabel(), vertexOutcome.detail());
		}

		CompileOutcome fragmentOutcome = compileStage(source.pipelineDebugLabel(), source.fragment(), DxShaderStageKind.FRAGMENT, bridge, cache);
		if (fragmentOutcome.module() == null) {
			return DxIrCompilationStageResult.failed(fragmentOutcome.status(), source.pipelineDebugLabel(), fragmentOutcome.detail());
		}

		DxSpirvPipelineModules modules = new DxSpirvPipelineModules(
			source.pipelineDebugLabel(), vertexOutcome.module(), fragmentOutcome.module()
		);
		return DxIrCompilationStageResult.ready(source.pipelineDebugLabel(), modules);
	}

	/**
	 * Handles exactly one shader stage (vertex or fragment), checking the
	 * cache first and only calling the bridge on a miss. Called
	 * identically for both stages from {@link #compile} -- the only thing
	 * that varies per call is {@code stageKind} and which
	 * {@link DxPreprocessedShaderSource} is passed in, mirroring
	 * {@link DxShaderSourceStage#loadStage}'s "normalize stage-specific
	 * input" shape from Milestone 4.2.
	 */
	private static CompileOutcome compileStage(
		String pipelineDebugLabel,
		DxPreprocessedShaderSource stageSource,
		DxShaderStageKind stageKind,
		DxBridge bridge,
		DxSpirvCache cache
	) {
		DxSpirvModule cached = cache.get(stageSource.id(), stageSource.type(), stageSource.preprocessedSource());
		if (cached != null) {
			return CompileOutcome.success(cached);
		}

		String shaderName = pipelineDebugLabel + "/" + stageSource.type() + "/" + stageSource.id();
		DxIrCompilationResult bridgeResult = bridge.compileGlslToSpirv(shaderName, stageSource.preprocessedSource(), stageKind);

		if (bridgeResult.isReady()) {
			DxSpirvModule module = new DxSpirvModule(stageSource.id(), stageSource.type(), bridgeResult.spirv());
			cache.put(stageSource.id(), stageSource.type(), stageSource.preprocessedSource(), module);
			return CompileOutcome.success(module);
		}

		DirectExpMod.LOGGER.error(
			"GLSL -> SPIR-V compilation failed for {} shader ({}) in pipeline {}: {}",
			stageSource.type(), stageSource.id(), pipelineDebugLabel, bridgeResult.detail()
		);

		DxIrCompilationStageStatus status = bridgeResult.isCompileError()
			? (stageKind == DxShaderStageKind.VERTEX ? DxIrCompilationStageStatus.VERTEX_COMPILE_FAILED : DxIrCompilationStageStatus.FRAGMENT_COMPILE_FAILED)
			: DxIrCompilationStageStatus.BRIDGE_UNAVAILABLE;

		return CompileOutcome.failure(status, bridgeResult.detail());
	}

	/**
	 * Outcome of {@link #compileStage} for one stage: exactly one of
	 * {@code module}/{@code status+detail} is populated. Kept private and
	 * local to this class -- unlike {@link DxIrCompilationStageResult},
	 * nothing outside this class needs a per-stage (as opposed to
	 * per-pipeline) result.
	 */
	private record CompileOutcome(DxSpirvModule module, DxIrCompilationStageStatus status, String detail) {
		static CompileOutcome success(DxSpirvModule module) {
			return new CompileOutcome(module, null, null);
		}

		static CompileOutcome failure(DxIrCompilationStageStatus status, String detail) {
			return new CompileOutcome(null, status, detail);
		}
	}
}
