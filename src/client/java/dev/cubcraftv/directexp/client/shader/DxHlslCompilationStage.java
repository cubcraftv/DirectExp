package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderType;
import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxHlslCompilationResult;
import dev.cubcraftv.directexp.bridge.DxResourceSlotAssignment;
import dev.cubcraftv.directexp.bridge.DxShaderStageKind;

import java.util.List;
import java.util.Objects;

/**
 * Milestone 4 / Milestone 4.5 ("HLSL Emission and D3D Compilation"):
 * translates both stages of Milestone 4.3's {@link DxSpirvPipelineModules}
 * to HLSL and compiles them to D3D11 shader bytecode through the native
 * bridge's real, JNI-exposed {@link DxBridge#emitAndCompileHlsl} call,
 * forcing each resource to the exact register Milestone 4.4's
 * {@link DxResourceLayout} assigned it.
 * <p>
 * Mirrors {@link DxReflectionStage}'s shape closely, with one addition:
 * each stage now has two independent ways to fail (SPIRV-Cross emission,
 * then {@code D3DCompile}) rather than one, so
 * {@link DxHlslCompilationStageStatus} has both a
 * {@code VERTEX_EMISSION_FAILED}/{@code FRAGMENT_EMISSION_FAILED} pair and
 * a {@code VERTEX_COMPILE_FAILED}/{@code FRAGMENT_COMPILE_FAILED} pair,
 * matching {@code DxHlslCompilationResult}'s own
 * {@code isEmissionError()}/{@code isCompileError()} split. A bridge-level
 * problem (native library not loaded, entrypoint missing) is kept
 * distinct as {@link DxHlslCompilationStageStatus#BRIDGE_UNAVAILABLE}.
 */
public final class DxHlslCompilationStage {
	private DxHlslCompilationStage() {
	}

	/**
	 * Compiles both stages of {@code spirvModules} to D3D11 bytecode,
	 * using {@code layout} to force each stage's resources to their
	 * engine-assigned registers. Once both HLSL stages are emitted, the fragment
	 * input struct is reordered to match the vertex output signature and the
	 * fragment HLSL is recompiled. Stops at the vertex stage's failure without
	 * attempting the fragment stage if the vertex stage itself failed, matching
	 * {@link DxReflectionStage#reflect}'s short-circuit shape.
	 */
	public static DxHlslCompilationStageResult compile(DxSpirvPipelineModules spirvModules, DxResourceLayout layout, DxBridge bridge) {
		Objects.requireNonNull(spirvModules, "spirvModules");
		Objects.requireNonNull(layout, "layout");
		Objects.requireNonNull(bridge, "bridge");

		CompileOutcome vertexOutcome = compileStage(spirvModules.pipelineDebugLabel(), spirvModules.vertex(), layout, bridge);
		if (vertexOutcome.module() == null) {
			return DxHlslCompilationStageResult.failed(vertexOutcome.status(), spirvModules.pipelineDebugLabel(), vertexOutcome.detail());
		}

		CompileOutcome fragmentOutcome = compileStage(spirvModules.pipelineDebugLabel(), spirvModules.fragment(), layout, bridge);
		if (fragmentOutcome.module() == null) {
			return DxHlslCompilationStageResult.failed(fragmentOutcome.status(), spirvModules.pipelineDebugLabel(), fragmentOutcome.detail());
		}

		String fragmentHlsl;
		try {
			fragmentHlsl = DxHlslInputSignatureRewriter.rewrite(
				vertexOutcome.module().hlsl(),
				fragmentOutcome.module().hlsl()
			);
		} catch (DxShaderInterfaceException error) {
			return DxHlslCompilationStageResult.failed(
				DxHlslCompilationStageStatus.FRAGMENT_INTERFACE_FAILED,
				spirvModules.pipelineDebugLabel(),
				error.getMessage()
			);
		}

		if (fragmentHlsl.equals(fragmentOutcome.module().hlsl())) {
			DxHlslPipelineModules modules = new DxHlslPipelineModules(
				spirvModules.pipelineDebugLabel(), vertexOutcome.module(), fragmentOutcome.module()
			);
			return DxHlslCompilationStageResult.ready(spirvModules.pipelineDebugLabel(), modules);
		}

		String fragmentShaderName = spirvModules.pipelineDebugLabel() + "/FRAGMENT/" + spirvModules.fragment().id();
		DxHlslCompilationResult fragmentCompileResult = bridge.compileHlsl(
			fragmentShaderName,
			fragmentHlsl,
			DxShaderStageKind.FRAGMENT
		);
		if (!fragmentCompileResult.isReady()) {
			DirectExpMod.LOGGER.error(
				"HLSL compilation failed for fragment shader ({}) in pipeline {}: {}",
				spirvModules.fragment().id(), spirvModules.pipelineDebugLabel(), fragmentCompileResult.detail()
			);
			DxHlslCompilationStageStatus status = fragmentCompileResult.isCompileError()
				? DxHlslCompilationStageStatus.FRAGMENT_COMPILE_FAILED
				: DxHlslCompilationStageStatus.BRIDGE_UNAVAILABLE;
			return DxHlslCompilationStageResult.failed(status, spirvModules.pipelineDebugLabel(), fragmentCompileResult.detail());
		}
		DxHlslModule compiledFragment = new DxHlslModule(
			spirvModules.fragment().id(),
			spirvModules.fragment().type(),
			fragmentCompileResult.bytecode(),
			fragmentHlsl
		);

		DxHlslPipelineModules modules = new DxHlslPipelineModules(
			spirvModules.pipelineDebugLabel(), vertexOutcome.module(), compiledFragment
		);
		return DxHlslCompilationStageResult.ready(spirvModules.pipelineDebugLabel(), modules);
	}

	/**
	 * Handles exactly one shader stage's {@link DxSpirvModule}. Called
	 * identically for both stages from {@link #compile} -- the only
	 * thing that varies per call is which module is passed in, mirroring
	 * {@link DxReflectionStage#reflectStage}'s "normalize stage-specific
	 * input" shape.
	 */
	private static CompileOutcome compileStage(
		String pipelineDebugLabel,
		DxSpirvModule module,
		DxResourceLayout layout,
		DxBridge bridge
	) {
		boolean isVertexStage = module.type() == ShaderType.VERTEX;
		List<DxResourceSlotAssignment> assignments = resourceSlotsForStage(layout, isVertexStage);

		String shaderName = pipelineDebugLabel + "/" + module.type() + "/" + module.id();
		DxShaderStageKind stageKind = isVertexStage ? DxShaderStageKind.VERTEX : DxShaderStageKind.FRAGMENT;
		DxHlslCompilationResult bridgeResult = bridge.emitAndCompileHlsl(shaderName, module.spirv(), stageKind, assignments);

		if (bridgeResult.isReady()) {
			DxHlslModule hlslModule = new DxHlslModule(module.id(), module.type(), bridgeResult.bytecode(), bridgeResult.hlsl());
			return CompileOutcome.success(hlslModule);
		}

		DirectExpMod.LOGGER.error(
			"HLSL emission/compilation failed for {} shader ({}) in pipeline {}: {}",
			module.type(), module.id(), pipelineDebugLabel, bridgeResult.detail()
		);

		DxHlslCompilationStageStatus status;
		if (bridgeResult.isEmissionError()) {
			status = isVertexStage ? DxHlslCompilationStageStatus.VERTEX_EMISSION_FAILED : DxHlslCompilationStageStatus.FRAGMENT_EMISSION_FAILED;
		} else if (bridgeResult.isCompileError()) {
			status = isVertexStage ? DxHlslCompilationStageStatus.VERTEX_COMPILE_FAILED : DxHlslCompilationStageStatus.FRAGMENT_COMPILE_FAILED;
		} else {
			status = DxHlslCompilationStageStatus.BRIDGE_UNAVAILABLE;
		}

		return CompileOutcome.failure(status, bridgeResult.detail());
	}

	/**
	 * Narrows {@code layout} to just the resources this one stage
	 * actually uses ({@link DxResourceLayoutEntry#usedInVertexStage()}/
	 * {@link DxResourceLayoutEntry#usedInFragmentStage()}), converted to
	 * the bridge-level {@link DxResourceSlotAssignment} shape
	 * {@link DxBridge#emitAndCompileHlsl} needs. Forcing a register for a
	 * resource this stage's own SPIR-V doesn't actually declare is
	 * harmless (SPIRV-Cross's {@code add_hlsl_resource_binding} simply
	 * has nothing to match it against), but filtering here keeps each
	 * call's assignment list limited to what is actually relevant,
	 * rather than always sending the whole pipeline's resource list to
	 * both stages.
	 */
	private static List<DxResourceSlotAssignment> resourceSlotsForStage(DxResourceLayout layout, boolean isVertexStage) {
		return layout.entries().stream()
			.filter(entry -> isVertexStage ? entry.usedInVertexStage() : entry.usedInFragmentStage())
			.map(entry -> new DxResourceSlotAssignment(entry.name(), entry.kind(), entry.slot()))
			.toList();
	}

	/**
	 * Outcome of {@link #compileStage} for one stage: exactly one of
	 * {@code module}/{@code status+detail} is populated. Kept private and
	 * local to this class, same as {@code DxReflectionStage}'s own
	 * {@code ReflectOutcome}.
	 */
	private record CompileOutcome(DxHlslModule module, DxHlslCompilationStageStatus status, String detail) {
		static CompileOutcome success(DxHlslModule module) {
			return new CompileOutcome(module, null, null);
		}

		static CompileOutcome failure(DxHlslCompilationStageStatus status, String detail) {
			return new CompileOutcome(null, status, detail);
		}
	}
}
