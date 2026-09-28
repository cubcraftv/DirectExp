package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cubcraftv.directexp.bridge.DxBridge;

/**
 * A mip-range view of a {@link DxTexture} plus (Milestone 4.6 prerequisite
 * scope) a best-effort native {@code ID3D11ShaderResourceView}. A view only
 * gets a native handle when its texture has one -- today that means
 * RGBA8_UNORM single-layer textures only (see
 * {@code DxTexture.createNativeTextureHandle}); depth and other formats stay
 * CPU-only until the native texture path is widened.
 *
 * <p>The native SRV holds its own reference on the underlying D3D11 texture,
 * so closing/releasing the texture before the view is not a use-after-free.
 */
final class DxTextureView extends GpuTextureView {
	private final DxBridge dxBridge;
	private final long nativeViewHandle;
	private boolean closed;

	DxTextureView(DxBridge dxBridge, GpuTexture gpuTexture, int baseMipLevel, int mipLevels) {
		super(gpuTexture, baseMipLevel, mipLevels);
		this.dxBridge = dxBridge;
		this.nativeViewHandle = createNativeViewHandle(gpuTexture, baseMipLevel, mipLevels);
	}

	private long createNativeViewHandle(GpuTexture gpuTexture, int baseMipLevel, int mipLevels) {
		if (this.dxBridge == null || !(gpuTexture instanceof DxTexture texture) || !texture.hasNativeTextureHandle()) {
			return 0L;
		}

		try {
			long handle = this.dxBridge.createTextureView(texture.nativeTextureHandle(), baseMipLevel, mipLevels);
			if (handle != 0L) {
				DxNativeResourceLog.nativeActive("texture view", "mips " + baseMipLevel + ".." + (baseMipLevel + mipLevels - 1));
			}
			return handle;
		} catch (UnsupportedOperationException | IllegalStateException error) {
			DxNativeResourceLog.fallback("texture view", error.getMessage());
			return 0L;
		}
	}

	long nativeViewHandle() {
		return this.nativeViewHandle;
	}

	boolean hasNativeViewHandle() {
		return this.nativeViewHandle != 0L;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		if (this.nativeViewHandle != 0L && this.dxBridge != null) {
			this.dxBridge.releaseTextureView(this.nativeViewHandle);
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}
}
