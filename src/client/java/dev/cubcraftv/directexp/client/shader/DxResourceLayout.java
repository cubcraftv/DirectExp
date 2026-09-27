package dev.cubcraftv.directexp.client.shader;

import dev.cubcraftv.directexp.bridge.DxResourceKind;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Milestone 4.4's engine-owned resource layout model for one pipeline:
 * the flat, engine-managed slot map the design spec's Binding Model
 * section and AGENTS.md's binding-model divergence note call for, built
 * by {@link DxResourceLayoutBuilder} from a {@link DxPipelineReflection}
 * and a {@link DxPipelineResourceDeclarations}.
 * <p>
 * This is explicitly not raw SPIR-V descriptor-set/binding semantics
 * passed through untouched -- {@link DxResourceLayoutEntry#slot} is this
 * project's own deterministically-assigned register slot, independent of
 * whatever {@code set}/{@code binding} decorations the original GLSL/SPIR-V
 * happened to use. See {@link DxResourceLayoutBuilder}'s javadoc for the
 * exact slot-assignment algorithm.
 */
public record DxResourceLayout(
	String pipelineDebugLabel,
	List<DxResourceLayoutEntry> entries
) {
	public DxResourceLayout {
		Objects.requireNonNull(entries, "entries");
		entries = List.copyOf(entries);
	}

	/**
	 * Finds the first entry with this name, in no particular kind order.
	 * <b>Ambiguous for a combined GLSL {@code sampler2D}</b>: Milestone
	 * 4.4's reflection deliberately produces two entries under the same
	 * name for one combined sampler -- a {@link DxResourceKind#SAMPLED_TEXTURE}
	 * and a {@link DxResourceKind#SAMPLER} (see
	 * {@code directexp_shader_reflection.cpp}'s handling of SPIR-V's
	 * {@code sampled_images}) -- so this overload can silently return
	 * either one. Prefer {@link #find(String, DxResourceKind)} whenever
	 * the kind is known, which is true almost everywhere a lookup by name
	 * happens (Milestone 4.5's per-stage resource binding is exactly the
	 * case that needs the disambiguated form).
	 */
	public Optional<DxResourceLayoutEntry> find(String name) {
		return this.entries.stream().filter(entry -> entry.name().equals(name)).findFirst();
	}

	public Optional<DxResourceLayoutEntry> find(String name, DxResourceKind kind) {
		return this.entries.stream().filter(entry -> entry.kind() == kind && entry.name().equals(name)).findFirst();
	}

	public List<DxResourceLayoutEntry> constantBuffers() {
		return entriesOfKind(DxResourceKind.CONSTANT_BUFFER);
	}

	public List<DxResourceLayoutEntry> sampledTextures() {
		return entriesOfKind(DxResourceKind.SAMPLED_TEXTURE);
	}

	public List<DxResourceLayoutEntry> samplers() {
		return entriesOfKind(DxResourceKind.SAMPLER);
	}

	public List<DxResourceLayoutEntry> structuredBuffers() {
		return entriesOfKind(DxResourceKind.STRUCTURED_BUFFER);
	}

	private List<DxResourceLayoutEntry> entriesOfKind(DxResourceKind kind) {
		return this.entries.stream().filter(entry -> entry.kind() == kind).toList();
	}
}
