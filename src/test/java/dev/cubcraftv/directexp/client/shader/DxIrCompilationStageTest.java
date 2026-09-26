package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderType;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import dev.cubcraftv.directexp.bridge.DxIrCompilationResult;
import dev.cubcraftv.directexp.bridge.DxIrCompilationStatus;
import dev.cubcraftv.directexp.bridge.DxShaderStageKind;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises Milestone 4.3's IR stage entirely in pure Java/JVM terms, the
 * same way {@code DxShaderSourceStageTest} covers Milestone 4.2: no native
 * call, no real {@code shaderc}/{@code SPIRV-Cross} -- {@link FakeDxBridge}
 * stands in for {@link dev.cubcraftv.directexp.bridge.NativeDxBridge} the
 * same way a fake {@code ShaderSource} stood in for the real one in
 * Milestone 4.2's test.
 */
class DxIrCompilationStageTest {

	private static final Identifier SHADER_ID = Identifier.fromNamespaceAndPath("directexp", "test/position");

	@Test
	void readyWhenBothStagesCompile() {
		DxPreprocessedShaderPipelineSource source = pipelineSource("vertex-glsl", "fragment-glsl");
		FakeDxBridge bridge = new FakeDxBridge();

		DxIrCompilationStageResult result = DxIrCompilationStage.compile(source, bridge, new DxSpirvCache());

		assertTrue(result.isReady(), "Both stages compiled, so the result should be ready");
		assertEquals(DxIrCompilationStageStatus.READY, result.status());
		assertArrayEquals(bytesFor("vertex-glsl"), result.modules().vertex().spirv());
		assertArrayEquals(bytesFor("fragment-glsl"), result.modules().fragment().spirv());
		assertEquals(ShaderType.VERTEX, result.modules().vertex().type());
		assertEquals(ShaderType.FRAGMENT, result.modules().fragment().type());
		assertEquals("test:pipeline", result.pipelineDebugLabel());
		// Both stages went through the bridge -- neither was a cache hit.
		assertEquals(2, bridge.compileCallCount());
	}

	@Test
	void vertexCompileErrorStopsBeforeFragmentIsAttempted() {
		DxPreprocessedShaderPipelineSource source = pipelineSource("bad-vertex-glsl", "fragment-glsl");
		FakeDxBridge bridge = new FakeDxBridge().failCompileFor("bad-vertex-glsl", "syntax error at line 3", 1, 0);

		DxIrCompilationStageResult result = DxIrCompilationStage.compile(source, bridge, new DxSpirvCache());

		assertFalse(result.isReady());
		assertEquals(DxIrCompilationStageStatus.VERTEX_COMPILE_FAILED, result.status());
		assertNull(result.modules());
		assertEquals("syntax error at line 3", result.detail());
		// Fragment stage should never have been attempted once the vertex
		// stage failed, matching DxShaderSourceStage's own short-circuit.
		assertEquals(1, bridge.compileCallCount());
	}

	@Test
	void fragmentCompileErrorIsReportedOnceVertexSucceeds() {
		DxPreprocessedShaderPipelineSource source = pipelineSource("vertex-glsl", "bad-fragment-glsl");
		FakeDxBridge bridge = new FakeDxBridge().failCompileFor("bad-fragment-glsl", "unsupported construct", 2, 1);

		DxIrCompilationStageResult result = DxIrCompilationStage.compile(source, bridge, new DxSpirvCache());

		assertFalse(result.isReady());
		assertEquals(DxIrCompilationStageStatus.FRAGMENT_COMPILE_FAILED, result.status());
		assertNull(result.modules());
		assertEquals("unsupported construct", result.detail());
		assertEquals(2, bridge.compileCallCount());
	}

	@Test
	void bridgeUnavailableIsDistinctFromACompileError() {
		DxPreprocessedShaderPipelineSource source = pipelineSource("vertex-glsl", "fragment-glsl");
		FakeDxBridge bridge = new FakeDxBridge().makeBridgeUnavailable("native library not loaded");

		DxIrCompilationStageResult result = DxIrCompilationStage.compile(source, bridge, new DxSpirvCache());

		assertFalse(result.isReady());
		assertEquals(DxIrCompilationStageStatus.BRIDGE_UNAVAILABLE, result.status());
		assertEquals("native library not loaded", result.detail());
	}

	@Test
	void secondCompileOfIdenticalSourceIsServedFromCache() {
		DxPreprocessedShaderPipelineSource source = pipelineSource("vertex-glsl", "fragment-glsl");
		FakeDxBridge bridge = new FakeDxBridge();
		DxSpirvCache cache = new DxSpirvCache();

		DxIrCompilationStageResult first = DxIrCompilationStage.compile(source, bridge, cache);
		DxIrCompilationStageResult second = DxIrCompilationStage.compile(source, bridge, cache);

		assertTrue(first.isReady());
		assertTrue(second.isReady());
		// Two stages compiled once each on the first call; the second call
		// should be served entirely from the cache with no further bridge
		// calls at all.
		assertEquals(2, bridge.compileCallCount());
		assertEquals(2, cache.size());
		assertArrayEquals(first.modules().vertex().spirv(), second.modules().vertex().spirv());
	}

	@Test
	void differentPreprocessedSourceForTheSameIdentityIsNotServedFromTheOtherEntrysCache() {
		DxSpirvCache cache = new DxSpirvCache();
		FakeDxBridge bridge = new FakeDxBridge();

		DxIrCompilationStage.compile(pipelineSource("vertex-glsl-v1", "fragment-glsl"), bridge, cache);
		DxIrCompilationStage.compile(pipelineSource("vertex-glsl-v2", "fragment-glsl"), bridge, cache);

		// Fragment source was identical both times (one cache hit there),
		// but the vertex source differed, so it must have been compiled
		// twice rather than the second call reusing the first's module.
		assertEquals(3, bridge.compileCallCount());
	}

	@Test
	void readyStatusCannotBeConstructedWithFailed() {
		assertThrows(IllegalArgumentException.class, () ->
			DxIrCompilationStageResult.failed(DxIrCompilationStageStatus.READY, "test:pipeline", "detail"));
	}

	private static DxPreprocessedShaderPipelineSource pipelineSource(String vertexPreprocessed, String fragmentPreprocessed) {
		DxPreprocessedShaderSource vertex = new DxPreprocessedShaderSource(SHADER_ID, ShaderType.VERTEX, "raw-vertex", vertexPreprocessed);
		DxPreprocessedShaderSource fragment = new DxPreprocessedShaderSource(SHADER_ID, ShaderType.FRAGMENT, "raw-fragment", fragmentPreprocessed);
		return new DxPreprocessedShaderPipelineSource("test:pipeline", vertex, fragment);
	}

	private static byte[] bytesFor(String preprocessedSource) {
		return preprocessedSource.getBytes();
	}

	/**
	 * Stands in for {@link dev.cubcraftv.directexp.bridge.NativeDxBridge}:
	 * "compiles" by turning the GLSL source's own bytes into fake SPIR-V,
	 * unless configured to fail for a specific source or to be entirely
	 * unavailable.
	 */
	private static final class FakeDxBridge implements DxBridge {
		private final List<String> compiledSources = new ArrayList<>();
		private String failingSource;
		private String failingDetail;
		private int failingNumErrors;
		private int failingNumWarnings;
		private String unavailableDetail;

		@Override
		public DxBridgeProbe probe() {
			throw new UnsupportedOperationException("not needed by this test");
		}

		@Override
		public DxIrCompilationResult compileGlslToSpirv(String shaderName, String glslSource, DxShaderStageKind stageKind) {
			this.compiledSources.add(glslSource);

			if (this.unavailableDetail != null) {
				return new DxIrCompilationResult(
					DxIrCompilationStatus.NATIVE_ERROR, null, this.unavailableDetail, 0, 0
				);
			}

			if (glslSource.equals(this.failingSource)) {
				return DxIrCompilationResult.compileError(this.failingDetail, this.failingNumErrors, this.failingNumWarnings);
			}

			return DxIrCompilationResult.ready(bytesFor(glslSource));
		}

		FakeDxBridge failCompileFor(String glslSource, String detail, int numErrors, int numWarnings) {
			this.failingSource = glslSource;
			this.failingDetail = detail;
			this.failingNumErrors = numErrors;
			this.failingNumWarnings = numWarnings;
			return this;
		}

		FakeDxBridge makeBridgeUnavailable(String detail) {
			this.unavailableDetail = detail;
			return this;
		}

		int compileCallCount() {
			return this.compiledSources.size();
		}
	}
}
