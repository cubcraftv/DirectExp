package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.nio.ByteBuffer;

/**
 * A view over a slice of a {@link DxBuffer}'s CPU mirror. For a write-mapped
 * view, {@code onClose} pushes the slice to the GPU copy -- closing is the
 * "unmap", the only point at which the caller is done writing.
 */
final class DxMappedBufferView implements GpuBuffer.MappedView {
	private final ByteBuffer data;
	private final Runnable onClose;
	private boolean closed;

	DxMappedBufferView(ByteBuffer data) {
		this(data, null);
	}

	DxMappedBufferView(ByteBuffer data, Runnable onClose) {
		this.data = data;
		this.onClose = onClose;
	}

	@Override
	public ByteBuffer data() {
		return this.data;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		if (this.onClose != null) {
			this.onClose.run();
		}
	}
}
