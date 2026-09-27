package dev.cubcraftv.directexp.client.shader;

import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxReflectedResource;
import dev.cubcraftv.directexp.bridge.DxResourceKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Milestone 4 / Milestone 4.4 ("Reflection and Resource Layout Model"),
 * second half: combines {@link DxReflectionStage}'s raw, per-stage SPIR-V
 * reflection output with a {@link DxPipelineResourceDeclarations} into a
 * single, engine-owned {@link DxResourceLayout} -- purely in Java, with no
 * native call, so this half of Milestone 4.4 (unlike the reflection half)
 * is unit-testable without a native library the same way Milestone 4.2's
 * source stage is.
 * <p>
 * Per the design spec's Binding Model section and AGENTS.md's "Binding
 * model" divergence note, the chosen model is a <b>flat, engine-managed
 * slot map</b>: one slot number per distinct (kind, name) resource,
 * shared across whichever stage(s) actually use it, rather than per-stage
 * slot numbering or anything derived from SPIR-V's own descriptor-set/
 * binding decorations. This is a deliberate simplification: D3D11's
 * {@code VSSetConstantBuffers}/{@code PSSetConstantBuffers} (and the
 * {@code t#}/{@code s#} equivalents) are independent per-stage register
 * files, so reusing the same slot index across stages for the same named
 * resource never collides -- it is simply the simplest scheme that is
 * still deterministic and easy to reason about in diagnostics, matching
 * MetalExp's own first-version choice per AGENTS.md.
 *
 * <h2>The concrete slot-assignment algorithm</h2>
 * <ol>
 *   <li>Merge the vertex and fragment stage's reflected resources by
 *   {@code (kind, name)} identity. A resource used in both stages becomes
 *   one merged entry with both {@code usedInVertexStage} and
 *   {@code usedInFragmentStage} set, rather than two separate entries.</li>
 *   <li>Within each {@link DxResourceKind}, sort the merged resources by
 *   name using {@link String#compareTo} (ordinary lexicographic,
 *   case-sensitive ordering). This -- not SPIR-V's own resource
 *   enumeration order, which is not a stable contract -- is what makes
 *   slot assignment deterministic across recompiles of the same shader
 *   source.</li>
 *   <li>Assign slot numbers within each kind as the resource's index in
 *   that sorted order, starting at {@code 0}. Slot numbers restart at
 *   {@code 0} for each of the four kinds independently (constant buffers,
 *   sampled textures, samplers, and structured buffers each have their
 *   own {@code 0}-based numbering), matching how D3D11 itself keeps
 *   {@code b#}/{@code t#}/{@code s#}/{@code u#} as separate register
 *   spaces.</li>
 * </ol>
 * <p>
 * A resource's {@code sizeBytes} is taken from whichever stage reflected
 * it first (vertex, since it is merged before fragment); if both stages
 * declare the same constant/structured buffer with different sizes --
 * which should not happen for a single shared {@code cbuffer} declaration
 * but is not structurally impossible -- the mismatch is logged and the
 * vertex stage's size wins, rather than either stage's compile failing
 * over it.
 *
 * <h2>Combining with pipeline declarations</h2>
 * {@link DxPipelineResourceDeclarations} is used only for diagnostics
 * here, not to filter or gate the layout: a sampler or uniform block
 * Blaze3D declares but SPIR-V reflection does not find is logged (most
 * likely `shaderc`/SPIRV-Cross dead-code-eliminated a genuinely unused
 * declared resource, not a bug), and a resource reflection finds but the
 * pipeline never declared is also logged (worth a human's attention, but
 * not by itself proof of a broken pipeline -- Blaze3D's own declared
 * sampler/uniform lists are not guaranteed by this project's research to
 * be perfectly exhaustive of everything a shader might reference). Being
 * lenient here rather than failing the whole stage on a mismatch matches
 * this milestone's own "manually cross-checked, not yet exercised through
 * an actual draw call" success criterion -- an automated hard failure
 * would be premature before a live shader has actually been run through
 * this path.
 */
public final class DxResourceLayoutBuilder {
	private DxResourceLayoutBuilder() {
	}

	public static DxResourceLayout build(DxPipelineReflection reflection, DxPipelineResourceDeclarations declarations) {
		Objects.requireNonNull(reflection, "reflection");
		Objects.requireNonNull(declarations, "declarations");

		Map<ResourceKey, MergedResource> merged = new LinkedHashMap<>();
		mergeStage(merged, reflection.vertex().resources(), true, reflection.pipelineDebugLabel());
		mergeStage(merged, reflection.fragment().resources(), false, reflection.pipelineDebugLabel());

		logDeclarationMismatches(reflection.pipelineDebugLabel(), merged, declarations);

		List<DxResourceLayoutEntry> entries = new ArrayList<>();
		for (DxResourceKind kind : DxResourceKind.values()) {
			List<MergedResource> ofKind = merged.values().stream()
				.filter(resource -> resource.kind == kind)
				.sorted(Comparator.comparing(resource -> resource.name))
				.toList();

			int slot = 0;
			for (MergedResource resource : ofKind) {
				entries.add(new DxResourceLayoutEntry(
					resource.name, resource.kind, slot, resource.sizeBytes,
					resource.usedInVertexStage, resource.usedInFragmentStage
				));
				slot++;
			}
		}

		return new DxResourceLayout(reflection.pipelineDebugLabel(), entries);
	}

	private static void mergeStage(
		Map<ResourceKey, MergedResource> merged,
		List<DxReflectedResource> stageResources,
		boolean isVertexStage,
		String pipelineDebugLabel
	) {
		for (DxReflectedResource resource : stageResources) {
			ResourceKey key = new ResourceKey(resource.kind(), resource.name());
			MergedResource existing = merged.get(key);

			if (existing == null) {
				merged.put(key, new MergedResource(resource.name(), resource.kind(), resource.sizeBytes(), isVertexStage, !isVertexStage));
				continue;
			}

			if (existing.sizeBytes != resource.sizeBytes()) {
				DirectExpMod.LOGGER.warn(
					"Resource '{}' ({}) in pipeline {} was reflected with size {} in one stage and {} in the other; keeping {}.",
					resource.name(), resource.kind(), pipelineDebugLabel, existing.sizeBytes, resource.sizeBytes(), existing.sizeBytes
				);
			}

			if (isVertexStage) {
				existing.usedInVertexStage = true;
			} else {
				existing.usedInFragmentStage = true;
			}
		}
	}

	private static void logDeclarationMismatches(
		String pipelineDebugLabel,
		Map<ResourceKey, MergedResource> merged,
		DxPipelineResourceDeclarations declarations
	) {
		Set<String> reflectedSamplerNames = namesOfKind(merged, DxResourceKind.SAMPLED_TEXTURE);
		Set<String> reflectedUniformBlockNames = namesOfKind(merged, DxResourceKind.CONSTANT_BUFFER);

		for (String declaredSampler : declarations.samplers()) {
			if (!reflectedSamplerNames.contains(declaredSampler)) {
				DirectExpMod.LOGGER.debug(
					"Pipeline {} declares sampler '{}' but SPIR-V reflection did not find it -- likely unused/dead-code-eliminated in this shader.",
					pipelineDebugLabel, declaredSampler
				);
			}
		}
		for (String reflectedSampler : reflectedSamplerNames) {
			if (!declarations.samplers().contains(reflectedSampler)) {
				DirectExpMod.LOGGER.debug(
					"Pipeline {} did not declare sampler '{}' but SPIR-V reflection found it.",
					pipelineDebugLabel, reflectedSampler
				);
			}
		}
		for (String declaredUniformBlock : declarations.uniformBlocks()) {
			if (!reflectedUniformBlockNames.contains(declaredUniformBlock)) {
				DirectExpMod.LOGGER.debug(
					"Pipeline {} declares uniform block '{}' but SPIR-V reflection did not find it -- likely unused/dead-code-eliminated in this shader.",
					pipelineDebugLabel, declaredUniformBlock
				);
			}
		}
		for (String reflectedUniformBlock : reflectedUniformBlockNames) {
			if (!declarations.uniformBlocks().contains(reflectedUniformBlock)) {
				DirectExpMod.LOGGER.debug(
					"Pipeline {} did not declare uniform block '{}' but SPIR-V reflection found it.",
					pipelineDebugLabel, reflectedUniformBlock
				);
			}
		}
	}

	private static Set<String> namesOfKind(Map<ResourceKey, MergedResource> merged, DxResourceKind kind) {
		Set<String> names = new HashSet<>();
		for (MergedResource resource : merged.values()) {
			if (resource.kind == kind) {
				names.add(resource.name);
			}
		}
		return names;
	}

	private record ResourceKey(DxResourceKind kind, String name) {
	}

	/**
	 * Mutable accumulator for one (kind, name) resource while merging the
	 * two stages -- intentionally not {@link DxResourceLayoutEntry}
	 * itself, since that record requires a final slot number this class
	 * has not assigned yet at merge time.
	 */
	private static final class MergedResource {
		private final String name;
		private final DxResourceKind kind;
		private final int sizeBytes;
		private boolean usedInVertexStage;
		private boolean usedInFragmentStage;

		private MergedResource(String name, DxResourceKind kind, int sizeBytes, boolean usedInVertexStage, boolean usedInFragmentStage) {
			this.name = name;
			this.kind = kind;
			this.sizeBytes = sizeBytes;
			this.usedInVertexStage = usedInVertexStage;
			this.usedInFragmentStage = usedInFragmentStage;
		}
	}
}
