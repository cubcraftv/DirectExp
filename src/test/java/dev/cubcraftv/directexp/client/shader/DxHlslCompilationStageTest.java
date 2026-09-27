package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderType;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import dev.cubcraftv.directexp.bridge.DxHlslCompilationResult;
import dev.cubcraftv.directexp.bridge.DxHlslCompilationStatus;
import dev.cubcraftv.directexp.bridge.DxResourceKind;
import dev.cubcraftv.directexp.bridge.DxResourceSlotAssignment;
import dev.cubcraftv.directexp.bridge.DxShaderStageKind;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises Milestone 4.5's HLSL compilation stage entirely in pure
 * Java/JVM terms, the same way {@code DxReflectionStageTest} covers
 * Milestone 4.4: no native call, no real SPIRV-Cross/D3DCompile --
 * {@link FakeDxBridge} stands in for
 * {@link dev.cubcraftv.directexp.bridge.NativeDxBridge}.
 */
class DxHlslCompilationStageTest {

	private static final Identifier SHADER_ID = Identifier.fromNamespaceAndPath("directexp", "test/position");

	@Test
	void readyWhenBothStagesCompile() {
		DxSpirvPipelineModules spirvModules = modules("vertex-spirv", "fragment-spirv");
		DxResourceLayout layout = emptyLayout();
		FakeDxBridge bridge = new FakeDxBridge()
			.withResult("vertex-spirv", DxHlslCompilationResult.ready(new byte[]{1, 2, 3}, "vs hlsl"))
			.withResult("fragment-spirv", DxHlslCompilationResult.ready(new byte[]{4, 5}, "ps hlsl"));

		DxHlslCompilationStageResult result = DxHlslCompilationStage.compile(spirvModules, layout, bridge);

		assertTrue(result.isReady());
		assertEquals(DxHlslCompilationStageStatus.READY, result.status());
		assertArrayEquals(new byte[]{1, 2, 3}, result.modules().vertex().bytecode());
		assertEquals("vs hlsl", result.modules().vertex().hlsl());
		assertArrayEquals(new byte[]{4, 5}, result.modules().fragment().bytecode());
		assertEquals(ShaderType.VERTEX, result.modules().vertex().type());
		assertEquals(ShaderType.FRAGMENT, result.modules().fragment().type());
		assertEquals(2, bridge.callCount());
	}

	@Test
	void vertexEmissionErrorStopsBeforeFragmentIsAttempted() {
		DxSpirvPipelineModules spirvModules = modules("bad-vertex-spirv", "fragment-spirv");
		DxResourceLayout layout = emptyLayout();
		FakeDxBridge bridge = new FakeDxBridge()
			.withResult("bad-vertex-spirv", DxHlslCompilationResult.emissionError("malformed SPIR-V module"));

		DxHlslCompilationStageResult result = DxHlslCompilationStage.compile(spirvModules, layout, bridge);

		assertFalse(result.isReady());
		assertEquals(DxHlslCompilationStageStatus.VERTEX_EMISSION_FAILED, result.status());
		assertNull(result.modules());
		assertEquals("malformed SPIR-V module", result.detail());
		assertEquals(1, bridge.callCount());
	}

	@Test
	void fragmentCompileErrorIsReportedOnceVertexSucceeds() {
		DxSpirvPipelineModules spirvModules = modules("vertex-spirv", "bad-fragment-spirv");
		DxResourceLayout layout = emptyLayout();
		FakeDxBridge bridge = new FakeDxBridge()
			.withResult("vertex-spirv", DxHlslCompilationResult.ready(new byte[]{1}, "vs hlsl"))
			.withResult("bad-fragment-spirv", DxHlslCompilationResult.compileError("ps hlsl text", "D3DCompile failed"));

		DxHlslCompilationStageResult result = DxHlslCompilationStage.compile(spirvModules, layout, bridge);

		assertFalse(result.isReady());
		assertEquals(DxHlslCompilationStageStatus.FRAGMENT_COMPILE_FAILED, result.status());
		assertNull(result.modules());
		assertEquals("D3DCompile failed", result.detail());
		assertEquals(2, bridge.callCount());
	}

	@Test
	void bridgeUnavailableIsDistinctFromEmissionOrCompileError() {
		DxSpirvPipelineModules spirvModules = modules("vertex-spirv", "fragment-spirv");
		DxResourceLayout layout = emptyLayout();
		FakeDxBridge bridge = new FakeDxBridge()
			.withResult("vertex-spirv", DxHlslCompilationResult.bridgeUnavailable(DxHlslCompilationStatus.NATIVE_ERROR, "native library not loaded"));

		DxHlslCompilationStageResult result = DxHlslCompilationStage.compile(spirvModules, layout, bridge);

		assertFalse(result.isReady());
		assertEquals(DxHlslCompilationStageStatus.BRIDGE_UNAVAILABLE, result.status());
		assertEquals("native library not loaded", result.detail());
	}

	@Test
	void resourceSlotsPassedToBridgeAreFilteredPerStage() {
		DxSpirvPipelineModules spirvModules = modules("vertex-spirv", "fragment-spirv");
		DxResourceLayout layout = new DxResourceLayout("test:pipeline", List.of(
			new DxResourceLayoutEntry("SharedTransforms", DxResourceKind.CONSTANT_BUFFER, 0, 64, true, true),
			new DxResourceLayoutEntry("Sampler0", DxResourceKind.SAMPLED_TEXTURE, 0, 0, false, true)
		));
		FakeDxBridge bridge = new FakeDxBridge()
			.withResult("vertex-spirv", DxHlslCompilationResult.ready(new byte[]{1}, "vs"))
			.withResult("fragment-spirv", DxHlslCompilationResult.ready(new byte[]{2}, "ps"));

		DxHlslCompilationStage.compile(spirvModules, layout, bridge);

		List<DxResourceSlotAssignment> vertexAssignments = bridge.assignmentsFor("vertex-spirv");
		List<DxResourceSlotAssignment> fragmentAssignments = bridge.assignmentsFor("fragment-spirv");

		assertEquals(1, vertexAssignments.size(), "Vertex stage should only get the shared constant buffer, not the fragment-only sampler");
		assertEquals("SharedTransforms", vertexAssignments.get(0).name());
		assertEquals(2, fragmentAssignments.size(), "Fragment stage should get both the shared buffer and its own sampler");
	}

	@Test
	void readyStatusCannotBeConstructedWithFailed() {
		assertThrows(IllegalArgumentException.class, () ->
			DxHlslCompilationStageResult.failed(DxHlslCompilationStageStatus.READY, "test:pipeline", "detail"));
	}

	private static DxResourceLayout emptyLayout() {
		return new DxResourceLayout("test:pipeline", List.of());
	}

	private static DxSpirvPipelineModules modules(String vertexSpirv, String fragmentSpirv) {
		DxSpirvModule vertex = new DxSpirvModule(SHADER_ID, ShaderType.VERTEX, vertexSpirv.getBytes());
		DxSpirvModule fragment = new DxSpirvModule(SHADER_ID, ShaderType.FRAGMENT, fragmentSpirv.getBytes());
		return new DxSpirvPipelineModules("test:pipeline", vertex, fragment);
	}

	/**
	 * Stands in for {@link dev.cubcraftv.directexp.bridge.NativeDxBridge}:
	 * returns a preconfigured {@link DxHlslCompilationResult} keyed on the
	 * SPIR-V bytes' own string content, and records the resource-slot
	 * assignments it was called with for
	 * {@link #resourceSlotsPassedToBridgeAreFilteredPerStage} to inspect.
	 */
	private static final class FakeDxBridge implements DxBridge {
		private final Map<String, DxHlslCompilationResult> resultsBySource = new HashMap<>();
		private final Map<String, List<DxResourceSlotAssignment>> assignmentsBySource = new HashMap<>();
		private final List<String> calls = new ArrayList<>();

		@Override
		public DxBridgeProbe probe() {
			throw new UnsupportedOperationException("not needed by this test");
		}

		@Override
		public DxHlslCompilationResult emitAndCompileHlsl(String shaderName, byte[] spirv, DxShaderStageKind stageKind, List<DxResourceSlotAssignment> resourceSlots) {
			String source = new String(spirv);
			this.calls.add(source);
			this.assignmentsBySource.put(source, resourceSlots);

			DxHlslCompilationResult configured = this.resultsBySource.get(source);
			if (configured != null) {
				return configured;
			}
			return DxHlslCompilationResult.ready(new byte[]{0}, "default hlsl");
		}

		FakeDxBridge withResult(String spirvSource, DxHlslCompilationResult result) {
			this.resultsBySource.put(spirvSource, result);
			return this;
		}

		List<DxResourceSlotAssignment> assignmentsFor(String spirvSource) {
			return this.assignmentsBySource.getOrDefault(spirvSource, List.of());
		}

		int callCount() {
			return this.calls.size();
		}
	}
}
