package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;

/**
 * Decides whether a buffer keeps a CPU mirror or lives only on the GPU.
 *
 * <p>Every {@link DxBuffer} used to keep a full CPU copy and push each write
 * to the GPU. That is right for small, frequently re-read buffers (uniforms,
 * per-frame vertex data, the fan expander's index source) but wasteful for the
 * large arenas a terrain renderer such as Sodium allocates: it doubles the
 * memory and adds a copy to every upload. A buffer qualifies for GPU-only
 * storage when it:
 * <ul>
 *   <li>has a native handle (otherwise the CPU copy is all there is),</li>
 *   <li>is used only as a vertex and/or index buffer (a constant buffer is
 *       re-staged from the CPU every draw; a texel buffer is tiny and its
 *       owner may read it back),</li>
 *   <li>is at least {@link #minimumBytes()} large.</li>
 * </ul>
 * Anything that later needs the bytes of a GPU-only buffer (map-for-read,
 * triangle-fan index expansion, buffer-to-texture copy) gets them through a
 * native readback, so GPU-only storage changes cost, never behaviour.
 *
 * <p>{@code -Ddirectexp.gpuOnlyBufferMinBytes=N} overrides the threshold
 * ({@code 0} = every eligible buffer, negative = never). Kept free of
 * Minecraft types so it can be unit tested.
 */
final class DxBufferStoragePolicy {
	static final String MINIMUM_BYTES_PROPERTY = "directexp.gpuOnlyBufferMinBytes";

	/** Smaller buffers stay mirrored: they are cheap to keep and often rewritten whole. */
	static final long DEFAULT_MINIMUM_BYTES = 1L << 20;

	private static final long CONFIGURED_MINIMUM_BYTES = readMinimumBytes(System.getProperty(MINIMUM_BYTES_PROPERTY));

	private DxBufferStoragePolicy() {
	}

	static long minimumBytes() {
		return CONFIGURED_MINIMUM_BYTES;
	}

	static boolean gpuOnly(int bindMask, long size, boolean hasNativeHandle, boolean allowed) {
		return gpuOnly(bindMask, size, hasNativeHandle, allowed, CONFIGURED_MINIMUM_BYTES);
	}

	static boolean gpuOnly(int bindMask, long size, boolean hasNativeHandle, boolean allowed, long minimumBytes) {
		if (!allowed || !hasNativeHandle || minimumBytes < 0L) {
			return false;
		}
		int geometryBits = DxBridge.BUFFER_BIND_VERTEX | DxBridge.BUFFER_BIND_INDEX;
		if ((bindMask & geometryBits) == 0 || (bindMask & ~geometryBits) != 0) {
			return false;
		}
		return size >= minimumBytes;
	}

	static long readMinimumBytes(String property) {
		if (property == null || property.isBlank()) {
			return DEFAULT_MINIMUM_BYTES;
		}
		try {
			return Long.parseLong(property.trim());
		} catch (NumberFormatException error) {
			return DEFAULT_MINIMUM_BYTES;
		}
	}
}
