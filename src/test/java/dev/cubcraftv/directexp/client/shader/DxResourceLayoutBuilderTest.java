package dev.cubcraftv.directexp.client.shader;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import dev.cubcraftv.directexp.bridge.DxReflectedResource;
import dev.cubcraftv.directexp.bridge.DxResourceKind;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises Milestone 4.4's engine-owned layout builder entirely in pure
 * Java/JVM terms -- no native call, no real SPIR-V, since this half of
 * Milestone 4.4 only ever consumes already-reflected
 * {@link DxReflectedResource} values.
 */
class DxResourceLayoutBuilderTest {

	private static final Identifier SHADER_ID = Identifier.fromNamespaceAndPath("directexp", "test/position");

	@Test
	void slotsAreAssignedInAlphabeticalOrderPerKindStartingAtZero() {
		DxPipelineReflection reflection = reflection(
			List.of(
				new DxReflectedResource("Zeta", DxResourceKind.SAMPLED_TEXTURE, 0),
				new DxReflectedResource("Alpha", DxResourceKind.SAMPLED_TEXTURE, 0)
			),
			List.of()
		);

		DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection, DxPipelineResourceDeclarations.none());

		assertEquals(0, layout.find("Alpha").orElseThrow().slot());
		assertEquals(1, layout.find("Zeta").orElseThrow().slot());
	}

	@Test
	void eachResourceKindHasItsOwnIndependentSlotNumbering() {
		DxPipelineReflection reflection = reflection(
			List.of(new DxReflectedResource("SamplerA", DxResourceKind.SAMPLED_TEXTURE, 0)),
			List.of(new DxReflectedResource("BufferA", DxResourceKind.CONSTANT_BUFFER, 32))
		);

		DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection, DxPipelineResourceDeclarations.none());

		// Both should be slot 0 -- b#/t#/s#/structured-buffer numbering
		// is independent per kind, not one shared counter.
		assertEquals(0, layout.find("SamplerA").orElseThrow().slot());
		assertEquals(0, layout.find("BufferA").orElseThrow().slot());
	}

	@Test
	void resourceUsedInBothStagesIsMergedIntoOneEntry() {
		DxReflectedResource sharedBuffer = new DxReflectedResource("DynamicTransforms", DxResourceKind.CONSTANT_BUFFER, 64);
		DxPipelineReflection reflection = reflection(List.of(sharedBuffer), List.of(sharedBuffer));

		DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection, DxPipelineResourceDeclarations.none());

		assertEquals(1, layout.constantBuffers().size(), "Should be one merged entry, not two");
		DxResourceLayoutEntry entry = layout.find("DynamicTransforms").orElseThrow();
		assertTrue(entry.usedInVertexStage());
		assertTrue(entry.usedInFragmentStage());
		assertEquals(64, entry.sizeBytes());
	}

	@Test
	void resourceUsedInOnlyOneStageRecordsThatStageAlone() {
		DxPipelineReflection reflection = reflection(
			List.of(),
			List.of(new DxReflectedResource("Sampler0", DxResourceKind.SAMPLED_TEXTURE, 0))
		);

		DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection, DxPipelineResourceDeclarations.none());

		DxResourceLayoutEntry entry = layout.find("Sampler0").orElseThrow();
		assertFalse(entry.usedInVertexStage());
		assertTrue(entry.usedInFragmentStage());
	}

	@Test
	void combinedSamplerReflectsAsBothATextureAndASamplerEntry() {
		// Mirrors what directexp_shader_reflection.cpp produces for one
		// GLSL `sampler2D`: one SampledTexture entry and one Sampler
		// entry sharing the same engine name.
		DxPipelineReflection reflection = reflection(
			List.of(),
			List.of(
				new DxReflectedResource("Sampler0", DxResourceKind.SAMPLED_TEXTURE, 0),
				new DxReflectedResource("Sampler0", DxResourceKind.SAMPLER, 0)
			)
		);

		DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection, DxPipelineResourceDeclarations.none());

		assertEquals(1, layout.sampledTextures().size());
		assertEquals(1, layout.samplers().size());
		assertEquals("Sampler0", layout.sampledTextures().get(0).name());
		assertEquals("Sampler0", layout.samplers().get(0).name());
	}

	@Test
	void allFourResourceKindsAreDistinguished() {
		DxPipelineReflection reflection = reflection(
			List.of(
				new DxReflectedResource("Cbuf", DxResourceKind.CONSTANT_BUFFER, 16),
				new DxReflectedResource("Tex", DxResourceKind.SAMPLED_TEXTURE, 0),
				new DxReflectedResource("Samp", DxResourceKind.SAMPLER, 0),
				new DxReflectedResource("SBuf", DxResourceKind.STRUCTURED_BUFFER, 128)
			),
			List.of()
		);

		DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection, DxPipelineResourceDeclarations.none());

		assertEquals(1, layout.constantBuffers().size());
		assertEquals(1, layout.sampledTextures().size());
		assertEquals(1, layout.samplers().size());
		assertEquals(1, layout.structuredBuffers().size());
	}

	@Test
	void unknownResourceNameIsNotFound() {
		DxPipelineReflection reflection = reflection(List.of(), List.of());
		DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection, DxPipelineResourceDeclarations.none());

		Optional<DxResourceLayoutEntry> found = layout.find("DoesNotExist");

		assertTrue(found.isEmpty());
	}

	@Test
	void declarationMismatchesDoNotPreventLayoutFromBeingBuilt() {
		// A declared sampler the shader doesn't actually use (dead-code
		// eliminated) and a reflected sampler the pipeline never
		// declared should both be tolerated -- this is a diagnostic
		// concern, not a hard failure, per DxResourceLayoutBuilder's
		// javadoc.
		DxPipelineReflection reflection = reflection(
			List.of(),
			List.of(new DxReflectedResource("ActuallyUsedSampler", DxResourceKind.SAMPLED_TEXTURE, 0))
		);
		DxPipelineResourceDeclarations declarations = new DxPipelineResourceDeclarations(
			List.of("ActuallyUsedSampler", "DeclaredButUnusedSampler"), List.of()
		);

		DxResourceLayout layout = DxResourceLayoutBuilder.build(reflection, declarations);

		assertEquals(1, layout.sampledTextures().size());
		assertTrue(layout.find("ActuallyUsedSampler").isPresent());
	}

	private static DxPipelineReflection reflection(List<DxReflectedResource> vertexResources, List<DxReflectedResource> fragmentResources) {
		DxReflectedShaderResources vertex = new DxReflectedShaderResources(SHADER_ID, ShaderType.VERTEX, vertexResources);
		DxReflectedShaderResources fragment = new DxReflectedShaderResources(SHADER_ID, ShaderType.FRAGMENT, fragmentResources);
		return new DxPipelineReflection("test:pipeline", vertex, fragment);
	}
}
