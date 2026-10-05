package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;

import java.nio.ByteBuffer;
import java.util.Objects;

final class DxSurfaceLease implements AutoCloseable {
	private final DxBridge dxBridge;
	private final DxSurfaceDescriptor descriptor;
	private long pendingCommandContextHandle;
	private boolean closed;

	DxSurfaceLease(DxBridge dxBridge, DxSurfaceDescriptor descriptor) {
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
	}

	DxSurfaceDescriptor descriptor() {
		return this.descriptor;
	}

	DxBridge dxBridge() {
		return this.dxBridge;
	}

	long nativeSurfaceHandle() {
		return this.descriptor.nativeSurfaceHandle();
	}

	void configure(int width, int height, boolean vsync) {
		this.ensureOpen();
		this.dxBridge.configureSurface(this.descriptor.nativeSurfaceHandle(), width, height, vsync);
	}

	void acquire() {
		this.ensureOpen();
		this.dxBridge.acquireSurface(this.descriptor.nativeSurfaceHandle());
	}

	void blitRgba8(ByteBuffer rgbaPixels, int width, int height) {
		this.ensureOpen();
		// Fallback for textures whose native D3D11 allocation failed.
		this.dxBridge.blitSurfaceRgba8(this.descriptor.nativeSurfaceHandle(), rgbaPixels, width, height);
	}

	void blitTexture(long nativeTextureHandle) {
		this.ensureOpen();
		long nativeCommandContextHandle = this.dxBridge.createCommandContext();
		try {
			this.dxBridge.blitSurfaceTexture(nativeCommandContextHandle, this.descriptor.nativeSurfaceHandle(), nativeTextureHandle);
			this.dxBridge.submitCommandContext(nativeCommandContextHandle);
		} finally {
			this.dxBridge.releaseCommandContext(nativeCommandContextHandle);
		}
	}

	void blitTexture(long nativeCommandContextHandle, long nativeTextureHandle) {
		this.ensureOpen();
		this.dxBridge.blitSurfaceTexture(nativeCommandContextHandle, this.descriptor.nativeSurfaceHandle(), nativeTextureHandle);
	}

	long acquirePendingCommandContext() {
		this.ensureOpen();
		if (this.pendingCommandContextHandle == 0L) {
			this.pendingCommandContextHandle = this.dxBridge.createCommandContext();
		}

		return this.pendingCommandContextHandle;
	}

	void submitPendingCommands() {
		this.ensureOpen();
		if (this.pendingCommandContextHandle == 0L) {
			return;
		}

		try {
			this.dxBridge.submitCommandContext(this.pendingCommandContextHandle);
		} finally {
			this.dxBridge.releaseCommandContext(this.pendingCommandContextHandle);
			this.pendingCommandContextHandle = 0L;
		}
	}

	void present() {
		this.ensureOpen();
		this.dxBridge.presentSurface(this.descriptor.nativeSurfaceHandle());
	}

	boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		if (this.pendingCommandContextHandle != 0L) {
			this.dxBridge.releaseCommandContext(this.pendingCommandContextHandle);
			this.pendingCommandContextHandle = 0L;
		}
		this.closed = true;
		this.dxBridge.releaseSurface(this.descriptor.nativeSurfaceHandle());
	}

	private void ensureOpen() {
		if (this.closed) {
			throw new IllegalStateException("DirectX surface lease is closed.");
		}
	}
}
