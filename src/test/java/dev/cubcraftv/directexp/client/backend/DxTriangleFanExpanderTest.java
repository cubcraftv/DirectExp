package dev.cubcraftv.directexp.client.backend;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DxTriangleFanExpanderTest {
	private static int[] result(DxTriangleFanExpander expander) {
		return Arrays.copyOf(expander.indices(), expander.count());
	}

	@Test
	void expandedCountIsThreePerTriangle() {
		assertEquals(0, DxTriangleFanExpander.expandedIndexCount(0));
		assertEquals(0, DxTriangleFanExpander.expandedIndexCount(2));
		assertEquals(3, DxTriangleFanExpander.expandedIndexCount(3));
		assertEquals(6, DxTriangleFanExpander.expandedIndexCount(4));
		assertEquals(3 * 14, DxTriangleFanExpander.expandedIndexCount(16));
	}

	@Test
	void nonIndexedFanKeepsTheHubAndWinding() {
		DxTriangleFanExpander expander = new DxTriangleFanExpander();
		expander.expandNonIndexed(0, 5);
		assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3, 0, 3, 4}, result(expander));
	}

	@Test
	void nonIndexedFanHonoursFirstVertex() {
		DxTriangleFanExpander expander = new DxTriangleFanExpander();
		expander.expandNonIndexed(10, 4);
		assertArrayEquals(new int[] {10, 11, 12, 10, 12, 13}, result(expander));
	}

	@Test
	void singleTriangleIsUnchangedAndDegenerateFansAreEmpty() {
		DxTriangleFanExpander expander = new DxTriangleFanExpander();
		expander.expandNonIndexed(0, 3);
		assertArrayEquals(new int[] {0, 1, 2}, result(expander));
		expander.expandNonIndexed(0, 2);
		assertEquals(0, expander.count());
		expander.expandNonIndexed(0, 0);
		assertEquals(0, expander.count());
	}

	@Test
	void indexedFanTransformsTheSourceSequenceAndKeepsRelativeValues() {
		ByteBuffer source = shorts(7, 3, 9, 4, 1);
		DxTriangleFanExpander expander = new DxTriangleFanExpander();
		expander.expandIndexed(source, 2, 0, 5);
		assertArrayEquals(new int[] {7, 3, 9, 7, 9, 4, 7, 4, 1}, result(expander));
		assertEquals(0, source.position(), "source position must be untouched");
	}

	@Test
	void indexedFanHonoursFirstIndexAndRange() {
		ByteBuffer source = shorts(100, 200, 7, 3, 9, 4, 300);
		DxTriangleFanExpander expander = new DxTriangleFanExpander();
		expander.expandIndexed(source, 2, 2, 4);
		assertArrayEquals(new int[] {7, 3, 9, 7, 9, 4}, result(expander));
	}

	@Test
	void indexedFanReadsThirtyTwoBitAndUnsignedSixteenBitIndices() {
		ByteBuffer ints = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
		ints.putInt(0, 70000).putInt(4, 1).putInt(8, 2).putInt(12, 3);
		DxTriangleFanExpander expander = new DxTriangleFanExpander();
		expander.expandIndexed(ints, 4, 0, 4);
		assertArrayEquals(new int[] {70000, 1, 2, 70000, 2, 3}, result(expander));

		expander.expandIndexed(shorts(0xFFFF, 1, 2), 2, 0, 3);
		assertArrayEquals(new int[] {65535, 1, 2}, result(expander));
	}

	@Test
	void batchedDrawsReuseTheScratchArray() {
		DxTriangleFanExpander expander = new DxTriangleFanExpander();
		ByteBuffer source = shorts(0, 1, 2, 3, 10, 11, 12, 13, 14);
		expander.expandIndexed(source, 2, 4, 5);
		int[] scratch = expander.indices();
		assertArrayEquals(new int[] {10, 11, 12, 10, 12, 13, 10, 13, 14}, result(expander));

		expander.expandIndexed(source, 2, 0, 4);
		assertSame(scratch, expander.indices(), "smaller draws must not reallocate");
		assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, result(expander));
	}

	@Test
	void rejectsBadArguments() {
		DxTriangleFanExpander expander = new DxTriangleFanExpander();
		assertThrows(IllegalArgumentException.class, () -> expander.expandNonIndexed(-1, 3));
		assertThrows(IllegalArgumentException.class, () -> expander.expandNonIndexed(0, -3));
		assertThrows(IllegalArgumentException.class, () -> expander.expandIndexed(shorts(1, 2, 3), 3, 0, 3));
		assertThrows(IllegalArgumentException.class, () -> expander.expandIndexed(shorts(1, 2, 3), 2, 1, 3));
	}

	private static ByteBuffer shorts(int... values) {
		ByteBuffer buffer = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < values.length; i++) {
			buffer.putShort(i * 2, (short) values[i]);
		}
		return buffer;
	}
}
