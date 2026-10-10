package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxTexelFormat;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G1 (GPU-only large buffers), G2 (native buffer copies) and G3 (texel-buffer views) of DxBuffer, against the byte-accurate recording bridge. */
class DxBufferGpuOnlyTest {
	private static final int VERTEX = 32;
	private static final int INDEX = 64;
	private static final int UNIFORM = 128;
	private static final int TEXEL = 256;
	private static final int COPY_DST = 8;
	private static final int ARENA = 2 << 20; // above the 1 MiB default threshold

	private static ByteBuffer bytes(int count, int seed) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(count);
		for (int i = 0; i < count; i++) {
			buffer.put((byte) (seed + i));
		}
		buffer.flip();
		return buffer;
	}

	// ---- G1 ----

	@Test
	void largeVertexArenaKeepsNoCpuMirrorAndSkipsTheInitialSync() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(bridge, VERTEX | COPY_DST, ARENA);

		assertTrue(arena.isGpuOnly());
		assertTrue(arena.hasNativeBufferHandle());
		assertEquals(List.of("createBuffer(mask=" + DxBridge.BUFFER_BIND_VERTEX + ",width=" + ARENA + ")"), bridge.calls);
	}

	@Test
	void smallBuffersConstantBuffersAndTexelBuffersStayMirrored() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		assertFalse(new DxBuffer(bridge, VERTEX, 4096).isGpuOnly());
		assertFalse(new DxBuffer(bridge, UNIFORM, 1024).isGpuOnly());
		assertFalse(new DxBuffer(bridge, TEXEL, ARENA).isGpuOnly());
		assertFalse(new DxBuffer(bridge, VERTEX | UNIFORM, ARENA).isGpuOnly());
		assertFalse(new DxBuffer(bridge, VERTEX, ARENA, false).isGpuOnly()); // transient memory opts out
	}

	@Test
	void bufferWithoutNativeHandleNeverGoesGpuOnly() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		bridge.failCreates = true;
		DxBuffer arena = new DxBuffer(bridge, VERTEX, ARENA);

		assertFalse(arena.hasNativeBufferHandle());
		assertFalse(arena.isGpuOnly());
	}

	@Test
	void writeUploadsOnlyTheWrittenRangeFromTheCallersBuffer() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(bridge, INDEX, ARENA);
		bridge.calls.clear();

		arena.write(1000, bytes(16, 1));

		assertEquals(List.of("uploadBufferRange(100,dst=1000,src=0,len=16,direct=true)"), bridge.calls);
		byte[] gpu = bridge.gpuBuffers.get(100L);
		assertEquals(1, gpu[1000]);
		assertEquals(16, gpu[1015]);
		assertEquals(0, gpu[1016]);
	}

	@Test
	void heapSourcesAreStagedThroughADirectBufferAndHonourPosition() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(bridge, VERTEX, ARENA);
		bridge.calls.clear();

		ByteBuffer heap = ByteBuffer.wrap(new byte[] {9, 9, 5, 6, 7, 9});
		heap.position(2).limit(5);
		arena.write(8, heap);

		assertEquals(List.of("uploadBufferRange(100,dst=8,src=0,len=3,direct=true)"), bridge.calls);
		byte[] gpu = bridge.gpuBuffers.get(100L);
		assertArrayEquals(new byte[] {5, 6, 7}, new byte[] {gpu[8], gpu[9], gpu[10]});
		assertEquals(2, heap.position()); // caller's buffer untouched
	}

	@Test
	void directSourceWithPositionUploadsFromThatPosition() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(bridge, VERTEX, ARENA);
		bridge.calls.clear();

		ByteBuffer source = bytes(10, 0);
		source.position(4);
		arena.write(0, source);

		assertEquals(List.of("uploadBufferRange(100,dst=0,src=4,len=6,direct=true)"), bridge.calls);
	}

	@Test
	void readingBytesBackComesFromTheGpu() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(bridge, INDEX, ARENA);
		arena.write(64, bytes(8, 40));

		ByteBuffer back = arena.sliceStorage(64, 8);

		assertEquals(8, back.remaining());
		assertEquals(40, back.get(0));
		assertEquals(47, back.get(7));
	}

	@Test
	void writeMappingUploadsOnCloseAndReadMappingStartsFromGpuContents() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(bridge, VERTEX, ARENA);
		arena.write(0, bytes(4, 10));
		bridge.calls.clear();

		try (GpuBufferSlice.MappedView view = arena.map(0, 8, true, true)) {
			assertEquals(10, view.data().get(0)); // pre-filled by readback
			view.data().put(4, (byte) 77);
		}

		assertEquals(77, bridge.gpuBuffers.get(100L)[4]);
		assertEquals(List.of(
			"readBuffer(100,src=0,dst=0,len=8)",
			"uploadBufferRange(100,dst=0,src=0,len=8,direct=true)"
		), bridge.calls);
	}

	@Test
	void readOnlyMappingNeverUploads() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(bridge, VERTEX, ARENA);
		bridge.calls.clear();

		try (GpuBufferSlice.MappedView view = arena.map(0, 8, true, false)) {
			assertTrue(view.data().isReadOnly());
		}

		assertEquals(List.of("readBuffer(100,src=0,dst=0,len=8)"), bridge.calls);
	}

	@Test
	void uploadFailureIsNonFatal() {
		RecordingDxBridge failing = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(failing, VERTEX, ARENA);
		failing.failBufferUploads = true;

		arena.write(0, bytes(4, 0)); // must not throw
	}

	// ---- G2 ----

	@Test
	void arenaToArenaCopyIsNativeAndMovesNoCpuData() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer source = new DxBuffer(bridge, VERTEX, ARENA);
		DxBuffer destination = new DxBuffer(bridge, VERTEX, ARENA);
		source.write(100, bytes(32, 1));
		bridge.calls.clear();

		DxBuffer.copy(source, 100, destination, 4096, 32);

		assertEquals(List.of("copyBuffer(100+100->101+4096,len=32)"), bridge.calls);
		assertEquals(1, bridge.gpuBuffers.get(101L)[4096]);
		assertEquals(32, bridge.gpuBuffers.get(101L)[4096 + 31]);
	}

	@Test
	void overlappingCopyWithinOneArenaHasMemmoveSemantics() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer arena = new DxBuffer(bridge, VERTEX, ARENA);
		arena.write(0, bytes(16, 1));
		bridge.calls.clear();

		DxBuffer.copy(arena, 0, arena, 4, 12);

		assertEquals(List.of("copyBuffer(100+0->100+4,len=12)"), bridge.calls);
		byte[] gpu = bridge.gpuBuffers.get(100L);
		assertEquals(1, gpu[4]);
		assertEquals(12, gpu[15]);
	}

	@Test
	void mirroredToMirroredCopyIsNativeAndKeepsTheDestinationMirrorInStep() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer source = new DxBuffer(bridge, VERTEX, 64);
		DxBuffer destination = new DxBuffer(bridge, VERTEX, 64);
		source.write(0, bytes(8, 5));
		bridge.calls.clear();

		DxBuffer.copy(source, 0, destination, 16, 8);

		assertEquals(List.of("copyBuffer(100+0->101+16,len=8)"), bridge.calls);
		assertEquals(5, destination.sliceStorage(16, 8).get(0));
		assertEquals(12, destination.sliceStorage(16, 8).get(7));
	}

	@Test
	void mirroredDestinationFromGpuOnlySourceReadsBackInsteadOfGoingStale() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer source = new DxBuffer(bridge, VERTEX, ARENA);
		DxBuffer destination = new DxBuffer(bridge, VERTEX, 64);
		source.write(0, bytes(8, 20));
		bridge.calls.clear();

		DxBuffer.copy(source, 0, destination, 0, 8);

		assertEquals(20, destination.sliceStorage(0, 8).get(0)); // mirror is correct
		assertEquals(20, bridge.gpuBuffers.get(101L)[0]);          // and so is the GPU copy
		assertEquals("readBuffer(100,src=0,dst=0,len=8)", bridge.calls.get(0));
	}

	@Test
	void constantBufferDestinationUsesTheMirrorPathNotAPartialNativeCopy() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer source = new DxBuffer(bridge, VERTEX, 64);
		DxBuffer destination = new DxBuffer(bridge, UNIFORM, 64);
		source.write(0, bytes(16, 3));
		bridge.calls.clear();

		DxBuffer.copy(source, 0, destination, 0, 16);

		assertTrue(bridge.calls.stream().noneMatch(call -> call.startsWith("copyBuffer")));
		assertEquals(3, destination.sliceStorage(0, 16).get(0));
	}

	@Test
	void failedNativeCopyFallsBackToACorrectCpuCopy() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer source = new DxBuffer(bridge, VERTEX, ARENA);
		DxBuffer destination = new DxBuffer(bridge, VERTEX, ARENA);
		source.write(0, bytes(8, 60));
		bridge.failBufferCopies = true;

		DxBuffer.copy(source, 0, destination, 8, 8);

		assertEquals(60, bridge.gpuBuffers.get(101L)[8]);
		assertEquals(67, bridge.gpuBuffers.get(101L)[15]);
	}

	@Test
	void copyRejectsRangesOutsideEitherBuffer() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer source = new DxBuffer(bridge, VERTEX, 64);
		DxBuffer destination = new DxBuffer(bridge, VERTEX, 64);

		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DxBuffer.copy(source, 60, destination, 0, 8));
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> DxBuffer.copy(source, 0, destination, 60, 8));
	}

	// ---- G3 ----

	@Test
	void texelBufferViewIsCreatedOnceAndCachedPerFormatAndRange() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer times = new DxBuffer(bridge, TEXEL | COPY_DST, 4096);
		bridge.calls.clear();

		long first = times.texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 0, 4096);
		long again = times.texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 0, 4096);
		long other = times.texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 1024, 1024);

		assertTrue(first != 0L);
		assertEquals(first, again);
		assertTrue(other != first);
		assertEquals(List.of(
			"createBufferSrv(100,fmt=" + DxTexelFormat.R32_SINT.code() + ",first=0,count=1024)",
			"createBufferSrv(100,fmt=" + DxTexelFormat.R32_SINT.code() + ",first=256,count=256)"
		), bridge.calls);
	}

	@Test
	void texelBufferViewsAreReleasedWithTheBuffer() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer times = new DxBuffer(bridge, TEXEL, 4096);
		long view = times.texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 0, 4096);
		bridge.calls.clear();

		times.close();

		assertEquals(List.of("releaseBufferSrv(" + view + ")", "releaseBuffer(100)"), bridge.calls);
		assertEquals(0L, times.texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 0, 4096));
	}

	@Test
	void noViewForBuffersWithoutTheTexelUsageUnalignedRangesOrFailedCreation() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		assertEquals(0L, new DxBuffer(bridge, VERTEX, 4096).texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 0, 4096));

		DxBuffer times = new DxBuffer(bridge, TEXEL, 4096);
		assertEquals(0L, times.texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 2, 4092)); // offset not a multiple of 4
		assertEquals(0L, times.texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 0, 5000)); // outside the buffer
		assertEquals(0L, times.texelBufferShaderResourceView(null, 0, 4096));

		bridge.failBufferViews = true;
		assertEquals(0L, times.texelBufferShaderResourceView(DxTexelFormat.RGBA32_UINT, 0, 4096));
		bridge.failBufferViews = false;
		assertEquals(0L, times.texelBufferShaderResourceView(DxTexelFormat.RGBA32_UINT, 0, 4096)); // failure remembered, not retried every draw
	}

	@Test
	void sectionTimeBufferShapeAsSodiumUsesIt() {
		// u_SectionTimeInfo: R32_SINT, one int per section (regionID * 256 + chunkID) of the whole buffer.
		RecordingDxBridge bridge = new RecordingDxBridge();
		int sections = 256 * 8;
		DxBuffer times = new DxBuffer(bridge, TEXEL | COPY_DST, sections * 4L);
		ByteBuffer data = ByteBuffer.allocateDirect(sections * 4).order(ByteOrder.nativeOrder());
		data.putInt(256 * 3 + 7 << 2, 12345);
		data.limit(sections * 4);
		times.write(0, data);

		assertTrue(times.texelBufferShaderResourceView(DxTexelFormat.R32_SINT, 0, sections * 4L) != 0L);
		assertEquals(12345, times.sliceStorage(0, sections * 4L).order(ByteOrder.nativeOrder()).getInt((256 * 3 + 7) * 4));
	}
}
