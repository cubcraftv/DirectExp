package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class DxBuffer extends GpuBuffer {
	private final ByteBuffer storage;
	private boolean closed;

	DxBuffer(int usage, long size) {
		super(usage, size);
		this.storage = ByteBuffer.allocateDirect(Math.toIntExact(size)).order(ByteOrder.nativeOrder());
	}

	ByteBuffer sliceStorage(long offset, long length) {
		ByteBuffer duplicate = this.storage.duplicate().order(ByteOrder.nativeOrder());
		duplicate.position(Math.toIntExact(offset));
		duplicate.limit(Math.toIntExact(offset + length));
		return duplicate.slice().order(ByteOrder.nativeOrder());
	}

	void write(long offset, ByteBuffer source) {
		ByteBuffer destination = sliceStorage(offset, source.remaining());
		destination.put(source.duplicate());
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		this.closed = true;
	}
}
