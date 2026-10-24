package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTextureView;
import dev.cubcraftv.directexp.bridge.DxBridge;

/**
 * A mip-range view of a {@link DxTexture} plus (Milestone 4.6 prerequisite
 * scope) a best-effort native view object: an {@code ID3D11ShaderResourceView}
 * plus, per the texture's bind purposes, an {@code ID3D11RenderTargetView}
 * (color render attachment) or {@code ID3D11DepthStencilView} (depth
 * attachment) -- Milestone 4.6 commit 2. A view only gets a native handle
 * when its texture has one: RGBA8_UNORM and the D16/D24S8/D32/D32S8 depth
 * formats, single layer (see {@code DxTexture.nativeFormatCode}); everything
 * else stays CPU-only.
 *
 * <p>The native SRV holds its own reference on the underlying D3D11 texture,
 * so closing/releasing the texture before the view is not a use-after-free.
 */
final class DxTextureView extends BaseGpuTextureView {
	private final DxBridge dxBridge;
	private final DxTexture texture; // null when the texture isn't a DxTexture
	private final long nativeViewHandle;
	private boolean closed;

	DxTextureView(DxBridge dxBridge, GpuTexture gpuTexture, int baseMipLevel, int mipLevels) {
		super(gpuTexture, baseMipLevel, mipLevels);
		this.dxBridge = dxBridge;
		this.texture = gpuTexture instanceof DxTexture dxTexture ? dxTexture : null;
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
				if (texture.hasNativeRenderTarget()) {
					DxNativeResourceLog.nativeActive("render target view", "mip " + baseMipLevel);
				}
				if (texture.hasNativeDepthTarget()) {
					DxNativeResourceLog.nativeActive("depth-stencil view", "mip " + baseMipLevel);
				}
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

	/** Which native views the handle carries is decided by the texture's bind purposes. */
	boolean hasNativeRenderTargetView() {
		return this.nativeViewHandle != 0L && this.texture != null && this.texture.hasNativeRenderTarget();
	}

	boolean hasNativeDepthStencilView() {
		return this.nativeViewHandle != 0L && this.texture != null && this.texture.hasNativeDepthTarget();
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
