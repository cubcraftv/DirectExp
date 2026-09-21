package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

final class DxTextureView extends GpuTextureView {
	private boolean closed;

	DxTextureView(GpuTexture gpuTexture, int baseMipLevel, int mipLevels) {
		super(gpuTexture, baseMipLevel, mipLevels);
	}

	@Override
	public void close() {
		this.closed = true;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}
}
