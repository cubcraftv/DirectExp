package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxBufferTest {
	private static ByteBuffer bytes(int count) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(count);
		for (int i = 0; i < count; i++) {
			buffer.put((byte) i);
		}
		buffer.flip();
		return buffer;
	}

	@Test
	void vertexBufferGetsNativeHandleAndInitialSyncOfItsLogicalSize() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer buffer = new DxBuffer(bridge, 32, 100);

		assertTrue(buffer.hasNativeBufferHandle());
		assertEquals(List.of(
			"createBuffer(mask=" + DxBridge.BUFFER_BIND_VERTEX + ",width=100)",
			"updateBuffer(100,off=0,len=100,cap=112)" // syncs the logical size; only the mirror is padded to a multiple of 16 (source of truth 11.4)
		), bridge.calls);
	}

	@Test
	void writeSyncsExactlyTheWrittenRangeAndKeepsCpuMirrorCorrect() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer buffer = new DxBuffer(bridge, 64, 64);
		bridge.calls.clear();

		buffer.write(8, bytes(4));

		assertEquals(List.of("updateBuffer(100,off=8,len=4,cap=64)"), bridge.calls);
		ByteBuffer slice = buffer.sliceStorage(8, 4);
		for (int i = 0; i < 4; i++) {
			assertEquals((byte) i, slice.get(i));
		}
	}

	@Test
	void oversizedConstantBufferStaysCpuOnly() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer buffer = new DxBuffer(bridge, 128, 65537);

		assertFalse(buffer.hasNativeBufferHandle());
		buffer.write(0, bytes(4));
		assertTrue(bridge.calls.isEmpty());
	}

	@Test
	void copyOnlyBufferStaysCpuOnly() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer buffer = new DxBuffer(bridge, 8 | 16, 64);

		assertFalse(buffer.hasNativeBufferHandle());
		assertTrue(bridge.calls.isEmpty());
	}

	@Test
	void nativeCreationFailureFallsBackToCpuStorage() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		bridge.failCreates = true;
		DxBuffer buffer = new DxBuffer(bridge, 32, 64);

		assertFalse(buffer.hasNativeBufferHandle());
		buffer.write(0, bytes(8));
		assertEquals((byte) 7, buffer.sliceStorage(7, 1).get(0));
		assertTrue(bridge.calls.isEmpty());
	}

	@Test
	void nullBridgeIsSupported() {
		DxBuffer buffer = new DxBuffer(null, 32, 64);

		assertFalse(buffer.hasNativeBufferHandle());
		buffer.write(0, bytes(8));
		buffer.close();
		assertTrue(buffer.isClosed());
	}

	@Test
	void closeReleasesNativeHandleExactlyOnce() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer buffer = new DxBuffer(bridge, 32, 64);
		bridge.calls.clear();

		buffer.close();
		buffer.close();

		assertEquals(List.of("releaseBuffer(100)"), bridge.calls);
		assertTrue(buffer.isClosed());
	}

	@Test
	void writeMappedViewSyncsOnCloseAndReadViewDoesNot() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxBuffer buffer = new DxBuffer(bridge, 32, 64);
		bridge.calls.clear();

		GpuBufferSlice.MappedView readView = buffer.map(0, 16, true, false);
		readView.close();
		assertTrue(bridge.calls.isEmpty());

		GpuBufferSlice.MappedView writeView = buffer.map(16, 16, false, true);
		writeView.data().put(0, (byte) 9);
		assertTrue(bridge.calls.isEmpty(), "nothing is pushed until the view is closed");
		writeView.close();
		writeView.close();

		assertEquals(List.of("updateBuffer(100,off=16,len=16,cap=64)"), bridge.calls);
		assertEquals((byte) 9, buffer.sliceStorage(16, 1).get(0));
	}
}
