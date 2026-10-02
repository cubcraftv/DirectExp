package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import dev.cubcraftv.directexp.bridge.DxBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * CPU-side buffer storage (always authoritative and always correct) plus a
 * best-effort native {@code ID3D11Buffer} mirror (Milestone 4.6 prerequisite
 * scope), following the same shape as {@link DxTexture}: the native handle may
 * legitimately be absent -- no bootstrapped device yet, a buffer with no GPU
 * bind purpose, an oversized constant buffer, or the native call throwing --
 * and every consumer must treat {@link #hasNativeBufferHandle()} as the switch.
 *
 * <p>Sync policy: eager. Every {@link #write} and every write-mapped view's
 * close pushes exactly the touched range to the GPU (whole buffer for
 * constant buffers, which D3D11 cannot partially update). Eager sync keeps
 * this commit self-contained and exercises the native path during normal
 * startup; coalescing dirty ranges until bind time is a later optimisation.
 *
 * <p>The mirror storage is padded to a multiple of 16 bytes so a constant
 * buffer's rounded-up GPU ByteWidth never reads past it.
 */
final class DxBuffer extends GpuBuffer {
	private final DxBridge dxBridge;
	private final long logicalSize;
	private final int usageBits;
	private final ByteBuffer storage;
	private final long nativeBufferHandle;
	private boolean closed;

	DxBuffer(DxBridge dxBridge, int usage, long size) {
		super(usage, size);
		this.dxBridge = dxBridge;
		this.logicalSize = size;
		this.usageBits = usage;
		this.storage = ByteBuffer.allocateDirect(Math.toIntExact(DxBufferBinding.alignedSize(size))).order(ByteOrder.nativeOrder());
		this.nativeBufferHandle = createNativeBufferHandle(usage, size);

		// GPU contents are undefined after creation; push the (zeroed) mirror
		// once so the two copies start identical.
		// Logical size, not the padded capacity: a vertex/index buffer's native width is
		// exactly `size`, so pushing the padding would fail the native range check
		// (source of truth, 11.4). A constant buffer is re-sent whole natively regardless.
		syncRange(0L, size);
	}

	ByteBuffer sliceStorage(long offset, long length) {
		ByteBuffer duplicate = this.storage.duplicate().order(ByteOrder.nativeOrder());
		duplicate.position(Math.toIntExact(offset));
		duplicate.limit(Math.toIntExact(offset + length));
		return duplicate.slice().order(ByteOrder.nativeOrder());
	}

	/** The whole logical CPU mirror (excluding alignment padding), e.g. for CPU-side index expansion. */
	ByteBuffer logicalStorage() {
		return sliceStorage(0L, this.logicalSize);
	}

	void write(long offset, ByteBuffer source) {
		int length = source.remaining();
		ByteBuffer destination = sliceStorage(offset, length);
		destination.put(source.duplicate());
		syncRange(offset, length);
	}

	/**
	 * Pushes {@code [offset, offset + length)} of the CPU mirror to the GPU
	 * copy. No-op without a native handle; failures are non-fatal (logged
	 * once) because the CPU mirror stays correct.
	 */
	void syncRange(long offset, long length) {
		if (this.nativeBufferHandle == 0L || this.dxBridge == null || length <= 0L) {
			return;
		}

		try {
			this.dxBridge.updateBuffer(this.nativeBufferHandle, this.storage, Math.toIntExact(offset), Math.toIntExact(length));
		} catch (UnsupportedOperationException | IllegalStateException error) {
			DxNativeResourceLog.syncFailure("buffer", error.getMessage() + " [range " + offset + "+" + length + ", logical size " + this.logicalSize + ", bind mask " + DxBufferBinding.bindMask(this.usageBits) + "]");
		}
	}

	long nativeBufferHandle() {
		return this.nativeBufferHandle;
	}

	boolean hasNativeBufferHandle() {
		return this.nativeBufferHandle != 0L;
	}

	private long createNativeBufferHandle(int usage, long size) {
		int bindMask = DxBufferBinding.bindMask(usage);
		if (this.dxBridge == null || !DxBufferBinding.canMirrorNatively(bindMask, size)) {
			return 0L;
		}

		try {
			long handle = this.dxBridge.createBuffer(bindMask, Math.toIntExact(size));
			if (handle != 0L) {
				DxNativeResourceLog.nativeActive("buffer", size + " bytes, bind mask " + bindMask);
			}
			return handle;
		} catch (UnsupportedOperationException | IllegalStateException error) {
			DxNativeResourceLog.fallback("buffer", error.getMessage());
			return 0L;
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		if (this.nativeBufferHandle != 0L && this.dxBridge != null) {
			this.dxBridge.releaseBuffer(this.nativeBufferHandle);
		}
	}
}
