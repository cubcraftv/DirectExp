package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;

/**
 * Translates Blaze3D's {@code GpuBuffer.Usage} bit layout into
 * {@link DxBridge}'s D3D-agnostic {@code BUFFER_BIND_*} mask.
 *
 * <p>The bit values below are not from memory: they were read out of the
 * decompiled Vulkan backend's {@code VulkanConst.bufferUsageToVk} (8 =
 * copy-dst, 16 = copy-src, 32 = vertex, 64 = index, 128 = uniform, 256 =
 * uniform-texel-buffer) and cross-checked against the "must have
 * GpuBuffer.USAGE_UNIFORM" assertion in {@code VulkanRenderPass}, which
 * tests bit 128. Deliberately raw ints rather than {@code GpuBuffer.USAGE_*}
 * references so this class stays Minecraft-free and unit-testable, and so a
 * future rename of those constants cannot silently change behaviour.
 * Re-verify against the decompile on every Minecraft version retarget.
 */
final class DxBufferBinding {
	static final int USAGE_VERTEX = 32;
	static final int USAGE_INDEX = 64;
	static final int USAGE_UNIFORM = 128;

	/** D3D11_REQ_CONSTANT_BUFFER_ELEMENT_COUNT (4096) * 16 bytes; see the native side. */
	static final long MAX_CONSTANT_BUFFER_BYTES = 4096L * 16L;

	private DxBufferBinding() {
	}

	/** Bind purposes for {@code usage}; 0 means "nothing the GPU binds" (copy-only, texel buffers). */
	static int bindMask(int usage) {
		int mask = 0;
		if ((usage & USAGE_VERTEX) != 0) {
			mask |= DxBridge.BUFFER_BIND_VERTEX;
		}
		if ((usage & USAGE_INDEX) != 0) {
			mask |= DxBridge.BUFFER_BIND_INDEX;
		}
		if ((usage & USAGE_UNIFORM) != 0) {
			mask |= DxBridge.BUFFER_BIND_CONSTANT;
		}
		return mask;
	}

	/** Constant buffers must be a multiple of 16 bytes; the CPU mirror is padded the same way for every buffer. */
	static long alignedSize(long size) {
		return (size + 15L) & ~15L;
	}

	/**
	 * Whether a native mirror should even be attempted: needs a bind purpose,
	 * a non-zero size, and (for constant buffers) to fit the pre-D3D11.1 64 KiB
	 * limit -- larger uniform buffers stay CPU-only until the draw path decides
	 * how to bind slices of them (see the Milestone 4.6 source-of-truth doc).
	 */
	static boolean canMirrorNatively(int bindMask, long size) {
		if (bindMask == 0 || size <= 0L || size > Integer.MAX_VALUE) {
			return false;
		}
		return (bindMask & DxBridge.BUFFER_BIND_CONSTANT) == 0 || alignedSize(size) <= MAX_CONSTANT_BUFFER_BYTES;
	}
}
