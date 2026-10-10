package dev.cubcraftv.directexp.client.backend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G6: the rules a QUADS draw over Sodium's shared 32-bit index buffer must satisfy. */
class DxIndexedDrawTraceTest {
	// 65536 quads * 6 indices * 4 bytes: a plausible shared quad index buffer.
	private static final long SHARED_INDEX_BYTES = 65536L * 6 * 4;

	@Test
	void aSoundQuadDrawHasNoProblems() {
		// 1000 quads from the start of the shared buffer; baseVertex selects the region's vertices.
		assertTrue(DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, 0, 6000, 123456, true).isEmpty());
	}

	@Test
	void drawsReachingExactlyTheEndOfTheBufferAreFineAndOnePastIsNot() {
		assertTrue(DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, 65536 * 6 - 600, 600, 0, true).isEmpty());
		assertEquals(1, DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, 65536 * 6 - 600, 606, 0, true).size());
	}

	@Test
	void indexWidthMattersForTheBoundsCheck() {
		// The same range that fits at 32-bit indices fits trivially at 16-bit; the reverse breaks.
		long sixteenBitBuffer = 600L * 2;
		assertTrue(DxIndexedDrawTrace.problems(false, sixteenBitBuffer, 0, 600, 0, true).isEmpty());
		assertFalse(DxIndexedDrawTrace.problems(true, sixteenBitBuffer, 0, 600, 0, true).isEmpty());
	}

	@Test
	void quadCountsMustBeMultiplesOfSixAndTriangleCountsOfThree() {
		assertFalse(DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, 0, 8, 0, true).isEmpty());
		assertTrue(DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, 0, 9, 0, false).isEmpty());
		assertFalse(DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, 0, 8, 0, false).isEmpty());
	}

	@Test
	void emptyNegativeAndMisplacedDrawsAreFlagged() {
		assertFalse(DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, 0, 0, 0, true).isEmpty());
		assertFalse(DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, -6, 6, 0, true).isEmpty());
		assertFalse(DxIndexedDrawTrace.problems(true, SHARED_INDEX_BYTES, 0, 6, -1, true).isEmpty());
	}

	@Test
	void descriptionNamesTheWidthQuadCountAndVerdict() {
		String ok = DxIndexedDrawTrace.describe("sodium:chunk_opaque", true, SHARED_INDEX_BYTES, 0, 6000, 99, true);
		assertTrue(ok.contains("32-bit"), ok);
		assertTrue(ok.contains("1000 quads"), ok);
		assertTrue(ok.contains("[ok]"), ok);

		String bad = DxIndexedDrawTrace.describe("sodium:chunk_opaque", true, SHARED_INDEX_BYTES, 0, 7, 0, true);
		assertTrue(bad.contains("PROBLEM"), bad);
	}

	@Test
	void traceIsOffUnlessEnabled() {
		assertFalse(DxIndexedDrawTrace.shouldLogDraw());
	}
}
