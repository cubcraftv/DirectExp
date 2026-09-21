package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuFence;

final class DxFence implements GpuFence {
	private boolean closed;

	@Override
	public void close() {
		this.closed = true;
	}

	@Override
	public boolean awaitCompletion(long timeoutNanos) {
		return !this.closed;
	}
}
