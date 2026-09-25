package dev.cubcraftv.directexp.client.shader;

import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises Milestone 4.2's source/preprocessing stage entirely in pure
 * Java/JVM terms, per
 * docs/plans/2026-09-25-directexp-milestone4-breakdown.md's Milestone 4.2
 * requirement -- no native call, and no real {@code ShaderDefines}/
 * {@code GlslPreprocessor} instance needed, since {@link FakeInjector}
 * stands in for {@link DxGlslDefinesInjector#mojangDefault()} the same
 * way a fake {@link ShaderSource} stands in for the real,
 * resource-pack-backed implementation.
 */
class DxShaderSourceStageTest {

	private static final Identifier VERTEX_ID = Identifier.fromNamespaceAndPath("directexp", "test/position");
	private static final Identifier FRAGMENT_ID = Identifier.fromNamespaceAndPath("directexp", "test/position");

	@Test
	void readyWhenBothStagesHaveSource() {
		FakeShaderSource shaderSource = new FakeShaderSource()
			.withSource(VERTEX_ID, ShaderType.VERTEX, "vertex-raw")
			.withSource(FRAGMENT_ID, ShaderType.FRAGMENT, "fragment-raw");
		DxShaderPipelineDescriptor descriptor = new DxShaderPipelineDescriptor(VERTEX_ID, FRAGMENT_ID, null, "test:pipeline");

		DxShaderSourceStageResult result = DxShaderSourceStage.load(descriptor, shaderSource, new FakeInjector());

		assertTrue(result.isReady(), "Both stages had source, so the result should be ready");
		assertEquals(DxShaderSourceStatus.READY, result.status());
		assertEquals("vertex-raw+defines", result.source().vertex().preprocessedSource());
		assertEquals("fragment-raw+defines", result.source().fragment().preprocessedSource());
		assertEquals("vertex-raw", result.source().vertex().rawSource());
		assertEquals(ShaderType.VERTEX, result.source().vertex().type());
		assertEquals(ShaderType.FRAGMENT, result.source().fragment().type());
		assertEquals("test:pipeline", result.pipelineDebugLabel());
	}

	@Test
	void missingVertexSourceProducesVertexSourceMissingStatus() {
		FakeShaderSource shaderSource = new FakeShaderSource()
			.withSource(FRAGMENT_ID, ShaderType.FRAGMENT, "fragment-raw");
		DxShaderPipelineDescriptor descriptor = new DxShaderPipelineDescriptor(VERTEX_ID, FRAGMENT_ID, null, "test:pipeline");

		DxShaderSourceStageResult result = DxShaderSourceStage.load(descriptor, shaderSource, new FakeInjector());

		assertFalse(result.isReady());
		assertEquals(DxShaderSourceStatus.VERTEX_SOURCE_MISSING, result.status());
		assertNull(result.source());
	}

	@Test
	void missingFragmentSourceProducesFragmentSourceMissingStatus() {
		FakeShaderSource shaderSource = new FakeShaderSource()
			.withSource(VERTEX_ID, ShaderType.VERTEX, "vertex-raw");
		DxShaderPipelineDescriptor descriptor = new DxShaderPipelineDescriptor(VERTEX_ID, FRAGMENT_ID, null, "test:pipeline");

		DxShaderSourceStageResult result = DxShaderSourceStage.load(descriptor, shaderSource, new FakeInjector());

		assertFalse(result.isReady());
		assertEquals(DxShaderSourceStatus.FRAGMENT_SOURCE_MISSING, result.status());
		assertNull(result.source());
	}

	@Test
	void bothStagesAreNormalizedThroughTheSamePreprocessingPath() {
		FakeShaderSource shaderSource = new FakeShaderSource()
			.withSource(VERTEX_ID, ShaderType.VERTEX, "shared-raw")
			.withSource(FRAGMENT_ID, ShaderType.FRAGMENT, "shared-raw");
		FakeInjector injector = new FakeInjector();
		DxShaderPipelineDescriptor descriptor = new DxShaderPipelineDescriptor(VERTEX_ID, FRAGMENT_ID, null, "test:pipeline");

		DxShaderSourceStageResult result = DxShaderSourceStage.load(descriptor, shaderSource, injector);

		assertTrue(result.isReady());
		// Same injector, same raw input -> the only difference between the
		// two stages should be their tagged ShaderType, not divergent
		// preprocessing logic.
		assertEquals(2, injector.invocationCount());
		assertEquals("shared-raw+defines", result.source().vertex().preprocessedSource());
		assertEquals("shared-raw+defines", result.source().fragment().preprocessedSource());
	}

	@Test
	void missingResultCannotBeConstructedWithReadyStatus() {
		assertThrows(IllegalArgumentException.class, () ->
			DxShaderSourceStageResult.missing(DxShaderSourceStatus.READY, "test:pipeline", "detail"));
	}

	private static final class FakeShaderSource implements ShaderSource {
		private final Map<Key, String> sources = new HashMap<>();

		FakeShaderSource withSource(Identifier id, ShaderType type, String source) {
			this.sources.put(new Key(id, type), source);
			return this;
		}

		@Override
		public String get(Identifier id, ShaderType type) {
			return this.sources.get(new Key(id, type));
		}

		private record Key(Identifier id, ShaderType type) {
		}
	}

	private static final class FakeInjector implements DxGlslDefinesInjector {
		private int invocationCount;

		@Override
		public String injectDefines(String source, net.minecraft.client.renderer.ShaderDefines defines) {
			this.invocationCount++;
			return source + "+defines";
		}

		int invocationCount() {
			return this.invocationCount;
		}
	}
}
