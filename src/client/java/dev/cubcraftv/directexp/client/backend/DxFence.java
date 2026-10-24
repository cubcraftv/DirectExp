package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.commands.GpuFence;

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
