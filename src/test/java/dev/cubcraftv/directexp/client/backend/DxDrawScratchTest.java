package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxDrawScratchTest {
	private final List<String> calls = new ArrayList<>();
	private final Set<String> reports = new LinkedHashSet<>();
	private long nextHandle = 500L;

	private final DxBridge bridge = new DxBridge() {
		@Override
		public DxBridgeProbe probe() {
			return null;
		}

		@Override
		public long createBuffer(int bindMask, int byteWidth) {
			DxDrawScratchTest.this.calls.add("createBuffer(" + bindMask + "," + byteWidth + ")");
			return DxDrawScratchTest.this.nextHandle++;
		}

		@Override
		public void updateBuffer(long handle, ByteBuffer mirror, int offset, int length) {
			DxDrawScratchTest.this.calls.add("updateBuffer(" + handle + "," + offset + "," + length + ",cap=" + mirror.capacity() + ",first=" + mirror.getInt(0) + ")");
		}

		@Override
		public void releaseBuffer(long handle) {
			DxDrawScratchTest.this.calls.add("releaseBuffer(" + handle + ")");
		}
	};

	private final DxDrawScratch scratch = new DxDrawScratch(this.bridge, (key, message) -> this.reports.add(key));

	private static ByteBuffer bytes(int size) {
		return ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
	}

	@Test
	void constantBufferIsCreatedAlignedAndResentWhole() {
		ByteBuffer data = bytes(20);
		data.putInt(0, 42);

		long handle = this.scratch.stageConstants(3, data);

		assertEquals(500L, handle);
		assertEquals("createBuffer(" + DxBridge.BUFFER_BIND_CONSTANT + ",32)", this.calls.get(0));
		assertEquals("updateBuffer(500,0,32,cap=32,first=42)", this.calls.get(1));
		assertEquals(0, data.position(), "the source buffer must not be consumed");
		assertEquals(20, data.remaining());
	}

	@Test
	void constantBufferIsReusedUntilItHasToGrow() {
		ByteBuffer small = bytes(20);
		long first = this.scratch.stageConstants(3, small);
		this.calls.clear();

		assertEquals(first, this.scratch.stageConstants(3, small));
		assertEquals(1, this.calls.size(), "reuse only re-sends the contents");
		this.calls.clear();

		long grown = this.scratch.stageConstants(3, bytes(100));
		assertNotEquals(first, grown);
		assertEquals("releaseBuffer(" + first + ")", this.calls.get(0));
		assertEquals("createBuffer(" + DxBridge.BUFFER_BIND_CONSTANT + ",112)", this.calls.get(1));
		this.calls.clear();

		assertEquals(grown, this.scratch.stageConstants(3, small), "a smaller slice reuses the bigger buffer");
		assertEquals(1, this.calls.size());
	}

	@Test
	void slotsDoNotShareBuffers() {
		long a = this.scratch.stageConstants(0, bytes(16));
		long b = this.scratch.stageConstants(1, bytes(16));
		assertNotEquals(a, b);
	}

	@Test
	void oversizedUniformIsRejectedAndReported() {
		assertEquals(0L, this.scratch.stageConstants(4, bytes(70000)));
		assertTrue(this.reports.contains("uniform-too-large:4"));
	}

	@Test
	void fanIndicesAreUploadedAsThirtyTwoBitAndGrowOnDemand() {
		long handle = this.scratch.stageIndices(new int[] { 7, 8, 9, 0, 0 }, 3);

		assertNotEquals(0L, handle);
		assertEquals("createBuffer(" + DxBridge.BUFFER_BIND_INDEX + ",16)", this.calls.get(0));
		assertEquals("updateBuffer(" + handle + ",0,12,cap=16,first=7)", this.calls.get(1));
		this.calls.clear();

		long grown = this.scratch.stageIndices(new int[10], 10);
		assertNotEquals(handle, grown);
		assertEquals("releaseBuffer(" + handle + ")", this.calls.get(0));
		assertEquals(0L, this.scratch.stageIndices(new int[0], 0));
	}

	@Test
	void closedScratchRefusesAndReleasesEverything() {
		long constants = this.scratch.stageConstants(0, bytes(16));
		long indices = this.scratch.stageIndices(new int[] { 1, 2, 3 }, 3);
		this.calls.clear();

		this.scratch.close();

		assertTrue(this.calls.contains("releaseBuffer(" + constants + ")"));
		assertTrue(this.calls.contains("releaseBuffer(" + indices + ")"));
		assertEquals(0L, this.scratch.stageConstants(0, bytes(16)));
	}
}
