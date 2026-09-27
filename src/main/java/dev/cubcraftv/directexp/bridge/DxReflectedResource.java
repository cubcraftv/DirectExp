package dev.cubcraftv.directexp.bridge;

import java.util.Objects;

/**
 * One resource as reflected directly from a single shader stage's SPIR-V
 * by {@link DxBridge#reflectSpirv}, before Milestone 4.4's
 * {@code DxResourceLayoutBuilder} (in {@code client.shader}) combines it
 * with Blaze3D's own pipeline declarations and assigns a concrete D3D11
 * register slot. {@code name} is SPIR-V's debug name for the resource
 * (see {@code directexp_shader_reflection.cpp}'s {@code ResourceName} for
 * the fallback when that debug info is missing). {@code sizeBytes} is
 * only meaningful for {@link DxResourceKind#CONSTANT_BUFFER}/
 * {@link DxResourceKind#STRUCTURED_BUFFER} and is {@code 0} otherwise.
 */
public record DxReflectedResource(
	String name,
	DxResourceKind kind,
	int sizeBytes
) {
	public DxReflectedResource {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(kind, "kind");
	}
}
