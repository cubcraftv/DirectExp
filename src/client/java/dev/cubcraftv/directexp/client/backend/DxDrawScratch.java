package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Native scratch buffers the draw path re-fills at bind time, owned by the
 * device backend and shared by every render pass:
 *
 * <ul>
 *   <li>one constant buffer per layout slot. Uniform slices reach a D3D11
 *       constant-buffer slot by being copied into it (the 2026-09-28
 *       "staging buffer + copy" decision): plain D3D11.0, no 11.1 ranged
 *       binding. A slot's buffer only ever grows, because binding a buffer
 *       larger than the shader's cbuffer is legal;</li>
 *   <li>one index buffer for expanded triangle fans (D3D11 has no fan topology,
 *       see {@link DxTriangleFanExpander}), 32-bit indices, grown on demand.</li>
 * </ul>
 *
 * Every method returns 0 when the native side cannot do it (no device,
 * oversized uniform, native failure); the caller skips the draw. Render thread only.
 */
final class DxDrawScratch implements AutoCloseable {
	private final DxBridge dxBridge;
	private final DxDrawDiagnostics diagnostics;
	private final Map<Integer, ConstantSlot> constantSlots = new HashMap<>();
	private long indexBufferHandle;
	private int indexBufferBytes;
	private ByteBuffer indexMirror;
	private boolean closed;

	DxDrawScratch(DxBridge dxBridge, DxDrawDiagnostics diagnostics) {
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
	}

	/**
	 * Copies {@code data} ({@code position..limit}, untouched) into the scratch
	 * constant buffer for {@code slot} and returns its native handle, or 0.
	 */
	long stageConstants(int slot, ByteBuffer data) {
		if (this.closed) {
			return 0L;
		}

		int length = data.remaining();
		long width = DxBufferBinding.alignedSize(Math.max(length, 16));
		if (width > DxBufferBinding.MAX_CONSTANT_BUFFER_BYTES) {
			this.diagnostics.reportOnce("uniform-too-large:" + slot, "uniform for slot b" + slot + " is " + length + " bytes, over the 64 KiB constant buffer limit");
			return 0L;
		}

		try {
			ConstantSlot constantSlot = this.constantSlots.get(slot);
			if (constantSlot == null || constantSlot.width < width) {
				if (constantSlot != null) {
					// Drop it from the map first so a failing createBuffer below cannot leave a released handle behind.
					this.constantSlots.remove(slot);
					this.dxBridge.releaseBuffer(constantSlot.handle);
				}
				int newWidth = (int) width;
				long handle = this.dxBridge.createBuffer(DxBridge.BUFFER_BIND_CONSTANT, newWidth);
				if (handle == 0L) {
					this.constantSlots.remove(slot);
					return 0L;
				}
				constantSlot = new ConstantSlot(handle, newWidth, ByteBuffer.allocateDirect(newWidth).order(ByteOrder.nativeOrder()));
				this.constantSlots.put(slot, constantSlot);
			}

			constantSlot.mirror.clear();
			constantSlot.mirror.put(data.duplicate());
			// A constant buffer is always re-sent whole; offset/length are ignored natively.
			this.dxBridge.updateBuffer(constantSlot.handle, constantSlot.mirror, 0, constantSlot.width);
			return constantSlot.handle;
		} catch (UnsupportedOperationException | IllegalStateException error) {
			this.diagnostics.reportOnce("uniform-stage:" + slot, "could not stage constants for slot b" + slot + ": " + error.getMessage());
			return 0L;
		}
	}

	/** Uploads {@code indices[0, count)} as 32-bit indices and returns the scratch index buffer's handle, or 0. */
	long stageIndices(int[] indices, int count) {
		if (this.closed || count <= 0) {
			return 0L;
		}

		try {
			long bytes = (long) count * Integer.BYTES;
			if (bytes > Integer.MAX_VALUE) {
				return 0L;
			}
			if (this.indexBufferHandle == 0L || this.indexBufferBytes < bytes) {
				if (this.indexBufferHandle != 0L) {
					this.dxBridge.releaseBuffer(this.indexBufferHandle);
					this.indexBufferHandle = 0L;
				}
				int newBytes = (int) Math.min(Integer.MAX_VALUE & ~15L, Math.max(DxBufferBinding.alignedSize(bytes), (long) this.indexBufferBytes * 2L));
				long handle = this.dxBridge.createBuffer(DxBridge.BUFFER_BIND_INDEX, newBytes);
				if (handle == 0L) {
					return 0L;
				}
				this.indexBufferHandle = handle;
				this.indexBufferBytes = newBytes;
				this.indexMirror = ByteBuffer.allocateDirect(newBytes).order(ByteOrder.nativeOrder());
			}

			this.indexMirror.clear();
			this.indexMirror.asIntBuffer().put(indices, 0, count);
			this.dxBridge.updateBuffer(this.indexBufferHandle, this.indexMirror, 0, (int) bytes);
			return this.indexBufferHandle;
		} catch (UnsupportedOperationException | IllegalStateException error) {
			this.diagnostics.reportOnce("fan-index-stage", "could not stage expanded fan indices: " + error.getMessage());
			return 0L;
		}
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		for (ConstantSlot slot : this.constantSlots.values()) {
			this.dxBridge.releaseBuffer(slot.handle);
		}
		this.constantSlots.clear();
		if (this.indexBufferHandle != 0L) {
			this.dxBridge.releaseBuffer(this.indexBufferHandle);
			this.indexBufferHandle = 0L;
		}
	}

	private record ConstantSlot(long handle, int width, ByteBuffer mirror) {
	}
}
