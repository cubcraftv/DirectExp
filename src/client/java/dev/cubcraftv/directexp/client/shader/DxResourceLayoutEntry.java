package dev.cubcraftv.directexp.client.shader;

import dev.cubcraftv.directexp.bridge.DxResourceKind;

import java.util.Objects;

/**
 * One resource in Milestone 4.4's engine-owned resource layout model, as
 * produced by {@link DxResourceLayoutBuilder}: an engine resource name
 * (the same {@code String} Blaze3D's {@code bindTexture}/{@code setUniform}
 * calls use), which of the four {@link DxResourceKind} categories it
 * belongs to, and the concrete, deterministically-assigned D3D11 register
 * slot for that category ({@code b#} for {@link DxResourceKind#CONSTANT_BUFFER},
 * {@code t#} for {@link DxResourceKind#SAMPLED_TEXTURE}, {@code s#} for
 * {@link DxResourceKind#SAMPLER}; {@link DxResourceKind#STRUCTURED_BUFFER}
 * shares the {@code t#}/{@code u#} space depending on read/write access,
 * which this milestone does not yet distinguish -- see
 * {@link DxResourceLayoutBuilder}'s javadoc).
 * <p>
 * {@code usedInVertexStage}/{@code usedInFragmentStage} record which of
 * the pipeline's two shader stages actually reference this resource --
 * Milestone 4.6's pipeline assembly needs this to know whether to bind a
 * given slot via the D3D11 immediate context's {@code VSSet*} family,
 * {@code PSSet*} family, or both.
 */
public record DxResourceLayoutEntry(
	String name,
	DxResourceKind kind,
	int slot,
	int sizeBytes,
	boolean usedInVertexStage,
	boolean usedInFragmentStage
) {
	public DxResourceLayoutEntry {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(kind, "kind");
		if (slot < 0) {
			throw new IllegalArgumentException("slot must be >= 0, was " + slot);
		}
		if (!usedInVertexStage && !usedInFragmentStage) {
			throw new IllegalArgumentException("Resource '" + name + "' must be used in at least one stage.");
		}
	}
}
