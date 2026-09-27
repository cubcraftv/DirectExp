package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderType;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import dev.cubcraftv.directexp.bridge.DxReflectedResource;
import dev.cubcraftv.directexp.bridge.DxResourceKind;
import dev.cubcraftv.directexp.bridge.DxSpirvReflectionResult;
import dev.cubcraftv.directexp.bridge.DxSpirvReflectionStatus;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises Milestone 4.4's reflection stage entirely in pure Java/JVM
 * terms, the same way {@code DxIrCompilationStageTest} covers Milestone
 * 4.3: no native call, no real SPIRV-Cross -- {@link FakeDxBridge} stands
 * in for {@link dev.cubcraftv.directexp.bridge.NativeDxBridge}.
 */
class DxReflectionStageTest {

	private static final Identifier SHADER_ID = Identifier.fromNamespaceAndPath("directexp", "test/position");

	@Test
	void readyWhenBothStagesReflect() {
		DxSpirvPipelineModules modules = modules("vertex-spirv", "fragment-spirv");
		FakeDxBridge bridge = new FakeDxBridge()
			.withResources("vertex-spirv", List.of(new DxReflectedResource("DynamicTransforms", DxResourceKind.CONSTANT_BUFFER, 64)))
			.withResources("fragment-spirv", List.of(new DxReflectedResource("Sampler0", DxResourceKind.SAMPLED_TEXTURE, 0)));

		DxReflectionStageResult result = DxReflectionStage.reflect(modules, bridge);

		assertTrue(result.isReady(), "Both stages reflected, so the result should be ready");
		assertEquals(DxReflectionStageStatus.READY, result.status());
		assertEquals(ShaderType.VERTEX, result.reflection().vertex().type());
		assertEquals(ShaderType.FRAGMENT, result.reflection().fragment().type());
		assertEquals(1, result.reflection().vertex().resources().size());
		assertEquals("DynamicTransforms", result.reflection().vertex().resources().get(0).name());
		assertEquals("Sampler0", result.reflection().fragment().resources().get(0).name());
		assertEquals("test:pipeline", result.pipelineDebugLabel());
		assertEquals(2, bridge.reflectCallCount());
	}

	@Test
	void vertexReflectionErrorStopsBeforeFragmentIsAttempted() {
		DxSpirvPipelineModules modules = modules("bad-vertex-spirv", "fragment-spirv");
		FakeDxBridge bridge = new FakeDxBridge().failReflectionFor("bad-vertex-spirv", "malformed SPIR-V module");

		DxReflectionStageResult result = DxReflectionStage.reflect(modules, bridge);

		assertFalse(result.isReady());
		assertEquals(DxReflectionStageStatus.VERTEX_REFLECTION_FAILED, result.status());
		assertNull(result.reflection());
		assertEquals("malformed SPIR-V module", result.detail());
		assertEquals(1, bridge.reflectCallCount());
	}

	@Test
	void fragmentReflectionErrorIsReportedOnceVertexSucceeds() {
		DxSpirvPipelineModules modules = modules("vertex-spirv", "bad-fragment-spirv");
		FakeDxBridge bridge = new FakeDxBridge().failReflectionFor("bad-fragment-spirv", "malformed SPIR-V module");

		DxReflectionStageResult result = DxReflectionStage.reflect(modules, bridge);

		assertFalse(result.isReady());
		assertEquals(DxReflectionStageStatus.FRAGMENT_REFLECTION_FAILED, result.status());
		assertNull(result.reflection());
		assertEquals(2, bridge.reflectCallCount());
	}

	@Test
	void bridgeUnavailableIsDistinctFromAReflectionError() {
		DxSpirvPipelineModules modules = modules("vertex-spirv", "fragment-spirv");
		FakeDxBridge bridge = new FakeDxBridge().makeBridgeUnavailable("native library not loaded");

		DxReflectionStageResult result = DxReflectionStage.reflect(modules, bridge);

		assertFalse(result.isReady());
		assertEquals(DxReflectionStageStatus.BRIDGE_UNAVAILABLE, result.status());
		assertEquals("native library not loaded", result.detail());
	}

	@Test
	void readyStatusCannotBeConstructedWithFailed() {
		assertThrows(IllegalArgumentException.class, () ->
			DxReflectionStageResult.failed(DxReflectionStageStatus.READY, "test:pipeline", "detail"));
	}

	private static DxSpirvPipelineModules modules(String vertexSpirv, String fragmentSpirv) {
		DxSpirvModule vertex = new DxSpirvModule(SHADER_ID, ShaderType.VERTEX, vertexSpirv.getBytes());
		DxSpirvModule fragment = new DxSpirvModule(SHADER_ID, ShaderType.FRAGMENT, fragmentSpirv.getBytes());
		return new DxSpirvPipelineModules("test:pipeline", vertex, fragment);
	}

	/**
	 * Stands in for {@link dev.cubcraftv.directexp.bridge.NativeDxBridge}:
	 * "reflects" by returning a preconfigured resource list keyed on the
	 * SPIR-V bytes' own string content, unless configured to fail for a
	 * specific module or to be entirely unavailable.
	 */
	private static final class FakeDxBridge implements DxBridge {
		private final List<String> reflectedSources = new ArrayList<>();
		private final Map<String, List<DxReflectedResource>> resourcesBySource = new HashMap<>();
		private String failingSource;
		private String failingDetail;
		private String unavailableDetail;

		@Override
		public DxBridgeProbe probe() {
			throw new UnsupportedOperationException("not needed by this test");
		}

		@Override
		public DxSpirvReflectionResult reflectSpirv(byte[] spirv) {
			String source = new String(spirv);
			this.reflectedSources.add(source);

			if (this.unavailableDetail != null) {
				return DxSpirvReflectionResult.bridgeUnavailable(DxSpirvReflectionStatus.NATIVE_ERROR, this.unavailableDetail);
			}

			if (source.equals(this.failingSource)) {
				return DxSpirvReflectionResult.reflectionError(this.failingDetail);
			}

			return DxSpirvReflectionResult.ready(this.resourcesBySource.getOrDefault(source, List.of()));
		}

		FakeDxBridge withResources(String spirvSource, List<DxReflectedResource> resources) {
			this.resourcesBySource.put(spirvSource, resources);
			return this;
		}

		FakeDxBridge failReflectionFor(String spirvSource, String detail) {
			this.failingSource = spirvSource;
			this.failingDetail = detail;
			return this;
		}

		FakeDxBridge makeBridgeUnavailable(String detail) {
			this.unavailableDetail = detail;
			return this;
		}

		int reflectCallCount() {
			return this.reflectedSources.size();
		}
	}
}
