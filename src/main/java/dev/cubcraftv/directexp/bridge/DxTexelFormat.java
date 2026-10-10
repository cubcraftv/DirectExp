package dev.cubcraftv.directexp.bridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Element formats a texel buffer (a typed {@code Buffer<T>} shader resource
 * view, GLSL {@code samplerBuffer} / {@code isamplerBuffer} /
 * {@code usamplerBuffer}) can be viewed as, in the same D3D-agnostic style as
 * the {@code TEXTURE_FORMAT_*} and {@code BUFFER_BIND_*} codes: Java and the
 * native bridge agree on the {@link #code()} below and only the native side
 * knows the matching {@code DXGI_FORMAT}.
 *
 * <p>The constants are named after Blaze3D's {@code GpuFormat} entries so that
 * {@link #fromGpuFormatName(String)} can resolve a pipeline's declared texel
 * buffer format without this class depending on Minecraft (which keeps it
 * unit-testable). Formats D3D11 cannot read from a buffer, e.g. the
 * three-component 8/16-bit ones, are intentionally absent.
 *
 * <p>Keep the codes in sync with {@code TexelFormat} in
 * {@code directexp_common.h}; they are part of the native ABI.
 */
public enum DxTexelFormat {
	R8_UNORM(1, 1),
	R8_UINT(2, 1),
	R8_SINT(3, 1),
	RG8_UNORM(4, 2),
	RG8_UINT(5, 2),
	RG8_SINT(6, 2),
	RGBA8_UNORM(7, 4),
	RGBA8_UINT(8, 4),
	RGBA8_SINT(9, 4),
	R16_UINT(10, 2),
	R16_SINT(11, 2),
	R16_FLOAT(12, 2),
	RG16_UINT(13, 4),
	RG16_SINT(14, 4),
	RG16_FLOAT(15, 4),
	RGBA16_UINT(16, 8),
	RGBA16_SINT(17, 8),
	RGBA16_FLOAT(18, 8),
	R32_UINT(19, 4),
	R32_SINT(20, 4),
	R32_FLOAT(21, 4),
	RG32_UINT(22, 8),
	RG32_SINT(23, 8),
	RG32_FLOAT(24, 8),
	RGB32_UINT(25, 12),
	RGB32_SINT(26, 12),
	RGB32_FLOAT(27, 12),
	RGBA32_UINT(28, 16),
	RGBA32_SINT(29, 16),
	RGBA32_FLOAT(30, 16);

	private static final Map<String, DxTexelFormat> BY_NAME = new HashMap<>();

	static {
		for (DxTexelFormat format : values()) {
			BY_NAME.put(format.name(), format);
		}
	}

	private final int code;
	private final int bytesPerElement;

	DxTexelFormat(int code, int bytesPerElement) {
		this.code = code;
		this.bytesPerElement = bytesPerElement;
	}

	/** The value sent across the bridge; never 0. */
	public int code() {
		return this.code;
	}

	public int bytesPerElement() {
		return this.bytesPerElement;
	}

	/** The format for a {@code GpuFormat} constant name such as {@code "R32_SINT"}, or {@code null} when D3D11 cannot view a buffer as it. */
	public static DxTexelFormat fromGpuFormatName(String gpuFormatName) {
		return gpuFormatName == null ? null : BY_NAME.get(gpuFormatName);
	}

	/** Inverse of {@link #code()}; {@code null} for an unknown code. */
	public static DxTexelFormat fromCode(int code) {
		for (DxTexelFormat format : values()) {
			if (format.code == code) {
				return format;
			}
		}
		return null;
	}
}
