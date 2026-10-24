package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import dev.cubcraftv.directexp.bridge.DxBridge;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class DxTransientMemory implements TransientMemory {
	private final DxBridge dxBridge;
	private final List<DxBuffer> buffers = new ArrayList<>();
	private final List<ByteBuffer> cpuAllocations = new ArrayList<>();

	DxTransientMemory(DxBridge dxBridge) {
		this.dxBridge = dxBridge;
	}

	@Override
	public ByteBuffer allocateCpu(long size, long alignment, long minimumAllocation, long elementSize) {
		int allocationSize = allocationSize(size, alignment, minimumAllocation, elementSize);
		ByteBuffer storage = ByteBuffer.allocateDirect(Math.addExact(allocationSize, Math.toIntExact(alignment - 1L)))
			.order(ByteOrder.nativeOrder());
		long address = MemoryUtil.memAddress(storage);
		int padding = (int) ((alignment - address % alignment) % alignment);
		storage.position(padding);
		storage.limit(padding + Math.toIntExact(size));
		ByteBuffer allocation = storage.slice().order(ByteOrder.nativeOrder());
		this.cpuAllocations.add(storage);
		return allocation;
	}

	@Override
	public GpuBufferSlice.MappedView allocateStaging(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return allocateMapped(size, usage | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
	}

	@Override
	public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		DxBuffer buffer = createBuffer(size, usage | GpuBuffer.USAGE_COPY_DST);
		return buffer.slice(0L, size);
	}

	@Override
	public GpuBufferSlice.MappedView allocateGpuMapped(long size, long alignment, int usage, long minimumAllocation, long elementSize) {
		return allocateMapped(size, usage | GpuBuffer.USAGE_COPY_DST);
	}

	@Override
	public GpuBufferSlice uploadStaging(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		return upload(data, usage | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
	}

	@Override
	public GpuBufferSlice uploadGpu(List<ByteBuffer> data, long alignment, int usage, long minimumAllocation, long elementSize) {
		return upload(data, usage | GpuBuffer.USAGE_COPY_DST);
	}

	@Override
	public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
		return multiUpload(data, usage | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST);
	}

	@Override
	public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
		return multiUpload(data, usage | GpuBuffer.USAGE_COPY_DST);
	}

	void reset() {
		for (DxBuffer buffer : this.buffers) {
			buffer.close();
		}
		this.buffers.clear();
		this.cpuAllocations.clear();
	}

	private GpuBufferSlice.MappedView allocateMapped(long size, int usage) {
		DxBuffer buffer = createBuffer(size, usage | GpuBuffer.USAGE_MAP_WRITE);
		GpuBufferSlice slice = buffer.slice(0L, size);
		ByteBuffer data = buffer.sliceStorage(0L, size);
		AtomicBoolean closedView = new AtomicBoolean(false);
		return new GpuBufferSlice.MappedView(
			slice,
			data,
			() -> {
				if (closedView.compareAndSet(false, true)) {
					buffer.syncRange(0L, size);
				}
			}
		);
	}

	private GpuBufferSlice upload(List<ByteBuffer> data, int usage) {
		long totalSize = totalSize(data);
		DxBuffer buffer = createBuffer(totalSize, usage);
		long offset = 0L;
		for (ByteBuffer part : data) {
			ByteBuffer copy = part.duplicate();
			int length = copy.remaining();
			buffer.write(offset, copy);
			offset += length;
		}
		return buffer.slice(0L, totalSize);
	}

	private List<GpuBufferSlice> multiUpload(List<ByteBuffer> data, int usage) {
		long totalSize = totalSize(data);
		DxBuffer buffer = createBuffer(totalSize, usage);
		List<GpuBufferSlice> slices = new ArrayList<>(data.size());
		long offset = 0L;
		for (ByteBuffer part : data) {
			ByteBuffer copy = part.duplicate();
			int length = copy.remaining();
			buffer.write(offset, copy);
			slices.add(buffer.slice(offset, length));
			offset += length;
		}
		return List.copyOf(slices);
	}

	private DxBuffer createBuffer(long size, int usage) {
		if (size < 0L || size > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Transient buffer size is outside the supported range: " + size);
		}
		// Per-frame memory is written through sliceStorage and synced by its owner, so it keeps the CPU mirror.
		DxBuffer buffer = new DxBuffer(this.dxBridge, usage, size, false);
		this.buffers.add(buffer);
		return buffer;
	}

	private static long totalSize(List<ByteBuffer> data) {
		long totalSize = 0L;
		for (ByteBuffer part : data) {
			totalSize = Math.addExact(totalSize, part.remaining());
		}
		return totalSize;
	}

	private static int allocationSize(long size, long alignment, long minimumAllocation, long elementSize) {
		if (size < 0L || minimumAllocation < 0L || elementSize <= 0L || alignment <= 0L) {
			throw new IllegalArgumentException("Transient allocation sizes and alignments must be non-negative, with positive alignment and element size.");
		}
		long required = Math.max(size, minimumAllocation);
		long alignedElements = Math.multiplyExact((required + elementSize - 1L) / elementSize, elementSize);
		long alignedBytes = Math.multiplyExact((alignedElements + alignment - 1L) / alignment, alignment);
		return Math.toIntExact(Math.max(size, alignedBytes));
	}
}
