package dev.cubcraftv.directexp.bridge;

/**
 * The four resource categories Milestone 4.4's engine-owned layout model
 * must distinguish, per the design spec's Binding Model section and
 * AGENTS.md's "D3D11 has no argument buffers ... only flat
 * constant-buffer/SRV/sampler/UAV slot binding" divergence note:
 *
 * <ul>
 *   <li>{@link #CONSTANT_BUFFER} -- {@code cbuffer} / {@code b#} slots,
 *   reflected from SPIR-V's uniform buffers.</li>
 *   <li>{@link #SAMPLED_TEXTURE} -- shader resource views for sampled
 *   textures, {@code t#} slots.</li>
 *   <li>{@link #SAMPLER} -- sampler state, {@code s#} slots, kept
 *   distinct from {@link #SAMPLED_TEXTURE} even for a single GLSL
 *   {@code sampler2D} (D3D11, unlike GL, has no combined sampler type;
 *   see {@code directexp_shader_reflection.cpp}'s handling of
 *   SPIRV-Cross's {@code sampled_images}).</li>
 *   <li>{@link #STRUCTURED_BUFFER} -- structured/typed buffer resources,
 *   reflected from SPIR-V's storage buffers.</li>
 * </ul>
 *
 * <p>Ordinal values are part of the native ABI: they must match
 * {@code directexp_shader_reflection.h}'s {@code SpirvResourceKind} enum
 * exactly, since {@code NativeDxSpirvReflectionResult}'s
 * {@code resourceKinds} array carries these as raw ints across JNI rather
 * than a richer type. Use {@link #fromNativeCode(int)} rather than
 * {@link #values()}{@code [code]} at that boundary so an out-of-range
 * code fails with a clear message instead of an
 * {@link ArrayIndexOutOfBoundsException}.
 */
public enum DxResourceKind {
	CONSTANT_BUFFER,
	SAMPLED_TEXTURE,
	SAMPLER,
	STRUCTURED_BUFFER;

	public static DxResourceKind fromNativeCode(int code) {
		return switch (code) {
			case 0 -> CONSTANT_BUFFER;
			case 1 -> SAMPLED_TEXTURE;
			case 2 -> SAMPLER;
			case 3 -> STRUCTURED_BUFFER;
			default -> throw new IllegalArgumentException("Unknown native DxResourceKind code: " + code);
		};
	}
}
