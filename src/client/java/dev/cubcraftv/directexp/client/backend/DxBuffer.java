package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxTexelFormat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A Blaze3D buffer backed by an {@code ID3D11Buffer} where one can be created,
 * following the same shape as {@link DxTexture}: the native handle may
 * legitimately be absent -- no bootstrapped device yet, a buffer with no GPU
 * bind purpose, an oversized constant buffer, or the native call throwing --
 * and every consumer must treat {@link #hasNativeBufferHandle()} as the switch.
 *
 * <h2>Two storage modes</h2>
 * <ul>
 *   <li><b>Mirrored</b> (the default): a CPU-side copy is always authoritative
 *       and always correct, and the native buffer is a GPU copy of it.
 *       Eager sync: every {@link #write} and every write-mapped view's close
 *       pushes exactly the touched range to the GPU (whole buffer for constant
 *       buffers, which D3D11 cannot partially update).</li>
 *   <li><b>GPU-only</b> ({@link DxBufferStoragePolicy}): large vertex/index
 *       arenas keep <em>no</em> CPU copy. Writes go straight to the GPU from
 *       the caller's buffer, {@link #copy} is a native buffer-to-buffer copy,
 *       and the rare consumer that needs the bytes (read-mapping, triangle-fan
 *       index expansion, buffer-to-texture copy) gets a native readback. The
 *       contents are undefined after creation, exactly as on the OpenGL and
 *       Vulkan backends.</li>
 * </ul>
 *
 * <p>The mirror storage is padded to a multiple of 16 bytes so a constant
 * buffer's rounded-up GPU ByteWidth never reads past it.
 *
 * <p>A buffer created with {@code USAGE_UNIFORM_TEXEL_BUFFER} can also hand out
 * typed-buffer shader resource views ({@link #texelBufferShaderResourceView}),
 * cached per (format, range) and released with the buffer.
 */
final class DxBuffer extends GpuBuffer {
	/** More distinct (format, range) views than this on one buffer are not expected; the cache is flushed instead of growing. */
	private static final int MAX_CACHED_TEXEL_VIEWS = 8;
	/** Heap sources are staged through a per-thread direct buffer up to this size; larger one-off uploads allocate. */
	private static final int MAX_UPLOAD_SCRATCH_BYTES = 8 << 20;
	private static final ThreadLocal<ByteBuffer> UPLOAD_SCRATCH = new ThreadLocal<>();

	private final DxBridge dxBridge;
	private final long logicalSize;
	private final int usageBits;
	private final int bindMask;
	/** The CPU mirror, or null for a GPU-only buffer. */
	private final ByteBuffer storage;
	private final long nativeBufferHandle;
	private final Map<TexelViewKey, Long> texelViews = new HashMap<>();
	private boolean closed;

	DxBuffer(DxBridge dxBridge, int usage, long size) {
		this(dxBridge, usage, size, true);
	}

	/**
	 * @param allowGpuOnly false for buffers whose owner writes through
	 *                     {@link #sliceStorage} and calls {@link #syncRange}
	 *                     itself (per-frame transient memory), which need the mirror
	 */
	DxBuffer(DxBridge dxBridge, int usage, long size, boolean allowGpuOnly) {
		super(usage, size);
		this.dxBridge = dxBridge;
		this.logicalSize = size;
		this.usageBits = usage;
		this.bindMask = DxBufferBinding.bindMask(usage);
		this.nativeBufferHandle = createNativeBufferHandle(usage, size);

		boolean gpuOnly = DxBufferStoragePolicy.gpuOnly(this.bindMask, size, this.nativeBufferHandle != 0L, allowGpuOnly);
		if (gpuOnly) {
			this.storage = null;
			DxNativeResourceLog.nativeActive("GPU-only buffer", size + " bytes, bind mask " + this.bindMask);
			return;
		}

		this.storage = ByteBuffer.allocateDirect(Math.toIntExact(DxBufferBinding.alignedSize(size))).order(ByteOrder.nativeOrder());
		// GPU contents are undefined after creation; push the (zeroed) mirror
		// once so the two copies start identical.
		// Logical size, not the padded capacity: a vertex/index buffer's native width is
		// exactly `size`, so pushing the padding would fail the native range check
		// (source of truth, 11.4). A constant buffer is re-sent whole natively regardless.
		syncRange(0L, size);
	}

	/** True when this buffer has no CPU mirror and lives only on the GPU. */
	boolean isGpuOnly() {
		return this.storage == null;
	}

	long logicalSize() {
		return this.logicalSize;
	}

	int bindMask() {
		return this.bindMask;
	}

	/**
	 * The bytes of {@code [offset, offset + length)}. For a mirrored buffer this is
	 * a live slice of the mirror; for a GPU-only buffer it is a <em>snapshot</em>
	 * read back from the GPU (slow: it waits for the GPU), so writes to it are not
	 * persisted -- use {@link #write} for that.
	 *
	 * @throws IllegalStateException if a GPU-only buffer cannot be read back
	 */
	ByteBuffer sliceStorage(long offset, long length) {
		if (this.storage == null) {
			return readBack(offset, length);
		}

		ByteBuffer duplicate = this.storage.duplicate().order(ByteOrder.nativeOrder());
		duplicate.position(Math.toIntExact(offset));
		duplicate.limit(Math.toIntExact(offset + length));
		return duplicate.slice().order(ByteOrder.nativeOrder());
	}

	/** The whole logical contents (excluding alignment padding); a GPU readback for a GPU-only buffer, see {@link #sliceStorage}. */
	ByteBuffer logicalStorage() {
		return sliceStorage(0L, this.logicalSize);
	}

	void write(long offset, ByteBuffer source) {
		int length = source.remaining();
		if (this.storage == null) {
			uploadRange(offset, source.duplicate());
			return;
		}

		ByteBuffer destination = sliceStorage(offset, length);
		destination.put(source.duplicate());
		syncRange(offset, length);
	}

	/**
	 * Copies {@code length} bytes between two buffers. When both have a native
	 * handle (and neither is a constant buffer) the copy happens on the GPU
	 * ({@code CopySubresourceRegion}) and no CPU data moves; a mirror on the
	 * destination is kept in step from the source's mirror. Every other
	 * combination falls back to reading the source bytes and writing them to the
	 * destination, which is always correct but moves the data through the CPU.
	 *
	 * <p>Source and destination may be the same buffer, including overlapping ranges.
	 */
	static void copy(DxBuffer source, long sourceOffset, DxBuffer destination, long destinationOffset, long length) {
		if (length <= 0L) {
			return;
		}
		source.checkRange(sourceOffset, length);
		destination.checkRange(destinationOffset, length);

		// A mirrored destination fed from a GPU-only source needs the bytes on the CPU
		// anyway, so the read-and-write fallback below is the cheapest correct path.
		boolean mirrorWouldGoStale = destination.storage != null && source.storage == null;
		if (source.canCopyNatively() && destination.canCopyNatively() && !mirrorWouldGoStale) {
			try {
				source.dxBridge.copyBuffer(
					source.nativeBufferHandle, Math.toIntExact(sourceOffset),
					destination.nativeBufferHandle, Math.toIntExact(destinationOffset), Math.toIntExact(length)
				);
				if (destination.storage != null) {
					destination.copyMirrorFrom(source, sourceOffset, destinationOffset, length);
				}
				return;
			} catch (UnsupportedOperationException | IllegalStateException error) {
				DxNativeResourceLog.syncFailure("buffer copy", error.getMessage() + " [src " + sourceOffset + "+" + length + " -> dst " + destinationOffset + "]; falling back to a CPU copy");
			}
		}

		ByteBuffer bytes = source.sliceStorage(sourceOffset, length);
		if (source == destination) {
			// A live slice of one mirror overlapping its own destination: snapshot first.
			ByteBuffer snapshot = ByteBuffer.allocateDirect(Math.toIntExact(length)).order(ByteOrder.nativeOrder());
			snapshot.put(bytes.duplicate());
			snapshot.flip();
			bytes = snapshot;
		}
		destination.write(destinationOffset, bytes);
	}

	private boolean canCopyNatively() {
		return this.nativeBufferHandle != 0L && this.dxBridge != null && (this.bindMask & DxBridge.BUFFER_BIND_CONSTANT) == 0;
	}

	/** Mirrors a GPU-side copy into this buffer's CPU mirror; both buffers must be mirrored. */
	private void copyMirrorFrom(DxBuffer source, long sourceOffset, long destinationOffset, long length) {
		ByteBuffer from = source.sliceStorage(sourceOffset, length);
		if (source == this) {
			ByteBuffer snapshot = ByteBuffer.allocateDirect(Math.toIntExact(length)).order(ByteOrder.nativeOrder());
			snapshot.put(from.duplicate());
			snapshot.flip();
			from = snapshot;
		}
		sliceStorage(destinationOffset, length).put(from.duplicate());
	}

	/**
	 * Pushes {@code [offset, offset + length)} of the CPU mirror to the GPU
	 * copy. No-op without a native handle or, for a GPU-only buffer, without a
	 * mirror; failures are non-fatal (logged once) because the CPU mirror stays correct.
	 */
	void syncRange(long offset, long length) {
		if (this.storage == null || this.nativeBufferHandle == 0L || this.dxBridge == null || length <= 0L) {
			return;
		}

		try {
			this.dxBridge.updateBuffer(this.nativeBufferHandle, this.storage, Math.toIntExact(offset), Math.toIntExact(length));
		} catch (UnsupportedOperationException | IllegalStateException error) {
			DxNativeResourceLog.syncFailure("buffer", error.getMessage() + " [range " + offset + "+" + length + ", logical size " + this.logicalSize + ", bind mask " + this.bindMask + "]");
		}
	}

	/** GPU-only write: uploads {@code source}'s remaining bytes to {@code offset}; failures are non-fatal (logged once), like {@link #syncRange}. */
	private void uploadRange(long offset, ByteBuffer source) {
		int length = source.remaining();
		if (length <= 0) {
			return;
		}
		checkRange(offset, length);

		ByteBuffer direct = source;
		int sourceOffset = source.position();
		if (!source.isDirect()) {
			direct = uploadScratch(length);
			direct.put(source);
			sourceOffset = 0;
		}

		try {
			this.dxBridge.uploadBufferRange(this.nativeBufferHandle, Math.toIntExact(offset), direct, sourceOffset, length);
		} catch (UnsupportedOperationException | IllegalStateException error) {
			DxNativeResourceLog.syncFailure("buffer", error.getMessage() + " [GPU-only range " + offset + "+" + length + ", logical size " + this.logicalSize + ", bind mask " + this.bindMask + "]");
		}
	}

	private static ByteBuffer uploadScratch(int length) {
		if (length > MAX_UPLOAD_SCRATCH_BYTES) {
			return ByteBuffer.allocateDirect(length).order(ByteOrder.nativeOrder());
		}
		ByteBuffer scratch = UPLOAD_SCRATCH.get();
		if (scratch == null || scratch.capacity() < length) {
			scratch = ByteBuffer.allocateDirect(Math.max(length, 64 * 1024)).order(ByteOrder.nativeOrder());
			UPLOAD_SCRATCH.set(scratch);
		}
		scratch.clear();
		return scratch;
	}

	private ByteBuffer readBack(long offset, long length) {
		checkRange(offset, length);
		ByteBuffer out = ByteBuffer.allocateDirect(Math.toIntExact(length)).order(ByteOrder.nativeOrder());
		if (length == 0L) {
			return out;
		}

		try {
			this.dxBridge.readBuffer(this.nativeBufferHandle, Math.toIntExact(offset), out, 0, Math.toIntExact(length));
		} catch (UnsupportedOperationException | IllegalStateException error) {
			throw new IllegalStateException("Could not read back a GPU-only buffer [range " + offset + "+" + length + "]: " + error.getMessage(), error);
		}
		return out;
	}

	private void checkRange(long offset, long length) {
		if (offset < 0L || length < 0L || offset + length > this.logicalSize) {
			throw new IllegalArgumentException("Range " + offset + "+" + length + " is outside a buffer of " + this.logicalSize + " bytes.");
		}
	}

	long nativeBufferHandle() {
		return this.nativeBufferHandle;
	}

	boolean hasNativeBufferHandle() {
		return this.nativeBufferHandle != 0L;
	}

	/**
	 * A typed-buffer shader resource view of {@code [offset, offset + length)} as
	 * {@code format}, for binding a texel buffer ({@code samplerBuffer}), or 0 when
	 * none can be made: no native handle, the buffer was not created with the texel
	 * buffer usage, the range is not element-aligned, or the native call failed
	 * (reported once; the failure is remembered so it is not retried every draw).
	 * The view belongs to this buffer and is released with it.
	 */
	long texelBufferShaderResourceView(DxTexelFormat format, long offset, long length) {
		if (this.closed || this.nativeBufferHandle == 0L || this.dxBridge == null || format == null
			|| (this.bindMask & DxBridge.BUFFER_BIND_SHADER_RESOURCE) == 0) {
			return 0L;
		}

		int elementBytes = format.bytesPerElement();
		if (offset < 0L || length <= 0L || offset + length > this.logicalSize || offset % elementBytes != 0L) {
			return 0L;
		}
		long elementCount = length / elementBytes;
		if (elementCount <= 0L || elementCount > Integer.MAX_VALUE) {
			return 0L;
		}

		TexelViewKey key = new TexelViewKey(format.code(), (int) (offset / elementBytes), (int) elementCount);
		Long cached = this.texelViews.get(key);
		if (cached != null) {
			return cached;
		}

		if (this.texelViews.size() >= MAX_CACHED_TEXEL_VIEWS) {
			releaseTexelViews();
		}
		long handle = 0L;
		try {
			handle = this.dxBridge.createBufferShaderResourceView(this.nativeBufferHandle, key.formatCode(), key.firstElement(), key.elementCount());
		} catch (UnsupportedOperationException | IllegalStateException error) {
			DxNativeResourceLog.drawProblem("texel-buffer-view", "could not create a texel buffer view (" + format + ", " + length + " bytes at " + offset + "): " + error.getMessage());
		}
		this.texelViews.put(key, handle);
		return handle;
	}

	private void releaseTexelViews() {
		for (long handle : this.texelViews.values()) {
			if (handle != 0L && this.dxBridge != null) {
				this.dxBridge.releaseBufferShaderResourceView(handle);
			}
		}
		this.texelViews.clear();
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
	public GpuBufferSlice.MappedView map(long offset, long length, boolean read, boolean write) {
		if (!read && !write) {
			throw new IllegalArgumentException("A mapped buffer view must be readable or writable.");
		}

		if (this.storage == null) {
			return mapGpuOnly(offset, length, read, write);
		}

		ByteBuffer data = sliceStorage(offset, length);
		if (!write) {
			data = data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
		}
		AtomicBoolean closedView = new AtomicBoolean(false);
		Runnable onClose = write
			? () -> {
				if (closedView.compareAndSet(false, true)) {
					syncRange(offset, length);
				}
			}
			: () -> {};
		return new GpuBufferSlice.MappedView(slice(offset, length), data, onClose);
	}

	/**
	 * A GPU-only buffer has nothing to hand out a window onto, so a mapping is a
	 * temporary CPU buffer: pre-filled by a readback when the caller asked to read,
	 * uploaded when a write mapping closes.
	 */
	private GpuBufferSlice.MappedView mapGpuOnly(long offset, long length, boolean read, boolean write) {
		checkRange(offset, length);
		ByteBuffer data = read
			? readBack(offset, length)
			: ByteBuffer.allocateDirect(Math.toIntExact(length)).order(ByteOrder.nativeOrder());
		ByteBuffer exposed = write ? data : data.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
		AtomicBoolean closedView = new AtomicBoolean(false);
		Runnable onClose = write
			? () -> {
				if (closedView.compareAndSet(false, true) && !this.closed) {
					ByteBuffer source = data.duplicate();
					source.clear();
					uploadRange(offset, source);
				}
			}
			: () -> {};
		return new GpuBufferSlice.MappedView(slice(offset, length), exposed, onClose);
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		releaseTexelViews();
		if (this.nativeBufferHandle != 0L && this.dxBridge != null) {
			this.dxBridge.releaseBuffer(this.nativeBufferHandle);
		}
	}

	private record TexelViewKey(int formatCode, int firstElement, int elementCount) {
	}
}
