package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;

/**
 * Translates Blaze3D's {@code GpuTexture.Usage} bits into
 * {@link DxBridge}'s {@code TEXTURE_BIND_*} mask. Bit meanings read from the
 * decompiled Vulkan backend's {@code VulkanConst.textureUsageToVk}: 1 = copy
 * destination, 2 = copy source, 4 = texture binding (sampled), 8 = render
 * attachment (color or depth depending on the format), 16 = cubemap
 * compatible. Raw ints for the same reasons as {@link DxBufferBinding};
 * re-verify on every snapshot bump.
 */
final class DxTextureBinding {
	static final int USAGE_TEXTURE_BINDING = 4;
	static final int USAGE_RENDER_ATTACHMENT = 8;

	private DxTextureBinding() {
	}

	/**
	 * Bind mask for a native texture, or 0 if nothing the GPU binds is wanted.
	 * Color textures are always shader-resource-capable (the present/blit path
	 * and every sampled texture expect it, and it costs nothing); depth
	 * textures get exactly what their usage asks for.
	 */
	static int bindMask(int usage, boolean depth) {
		int mask = 0;
		if (depth) {
			if ((usage & USAGE_TEXTURE_BINDING) != 0) {
				mask |= DxBridge.TEXTURE_BIND_SHADER_RESOURCE;
			}
			if ((usage & USAGE_RENDER_ATTACHMENT) != 0) {
				mask |= DxBridge.TEXTURE_BIND_DEPTH_STENCIL;
			}
		} else {
			mask |= DxBridge.TEXTURE_BIND_SHADER_RESOURCE;
			if ((usage & USAGE_RENDER_ATTACHMENT) != 0) {
				mask |= DxBridge.TEXTURE_BIND_RENDER_TARGET;
			}
		}
		return mask;
	}
}
