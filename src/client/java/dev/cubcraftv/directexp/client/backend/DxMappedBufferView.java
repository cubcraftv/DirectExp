package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.nio.ByteBuffer;

final class DxMappedBufferView implements GpuBuffer.MappedView {
	private final ByteBuffer data;

	DxMappedBufferView(ByteBuffer data) {
		this.data = data;
	}

	@Override
	public ByteBuffer data() {
		return this.data;
	}

	@Override
	public void close() {
	}
}
