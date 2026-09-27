package dev.cubcraftv.directexp.bridge;

import java.util.Objects;

/**
 * One resource's engine-assigned D3D11 register slot, passed down to
 * {@link DxBridge#emitAndCompileHlsl} so SPIRV-Cross's HLSL emission can
 * force that exact register (via {@code add_hlsl_resource_binding}, see
 * {@code directexp_shader_hlsl.cpp}) rather than choosing one on its own.
 * The client layer ({@code DxHlslCompilationStage} in
 * {@code client.shader}) builds these from Milestone 4.4's
 * {@code DxResourceLayoutEntry} -- kept as this project's own bridge-level
 * type, the same reason {@link DxShaderStageKind} exists instead of using
 * a client-only Blaze3D type directly, since {@link DxBridge} lives in
 * this project's {@code main} source set.
 */
public record DxResourceSlotAssignment(
	String name,
	DxResourceKind kind,
	int slot
) {
	public DxResourceSlotAssignment {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(kind, "kind");
		if (slot < 0) {
			throw new IllegalArgumentException("slot must be >= 0, was " + slot);
		}
	}
}
