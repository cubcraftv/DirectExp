package dev.cubcraftv.directexp.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the compact constructor's validation, specifically the
 * vertexStride/attribute-count interaction fixed alongside Milestone 4.6
 * commit 4's shader-model fix: a pipeline with an empty {@code VertexFormat}
 * (a full-screen-quad/blit shader -- vanilla has several: {@code blit},
 * {@code flat_clouds}, {@code animate_sprite_blit}, {@code lightmap},
 * {@code entity_outline_blit}, {@code tracy_blit}) legitimately has
 * {@code vertexStride} 0 and empty attribute arrays, and must not be
 * rejected the way a non-empty vertex format with a zero stride should be.
 */
class DxPipelineStateDescriptorTest {
	private static DxPipelineStateDescriptor describe(int[] formats, int[] offsets, int vertexStride) {
		return new DxPipelineStateDescriptor(
			0, false, 0, 0, 0, 0, 0, 0, 0xF, false, false, 0, 0, 0, 0, 0.0F,
			formats, offsets, vertexStride
		);
	}

	@Test
	void zeroStrideIsAllowedWithNoAttributes() {
		assertDoesNotThrow(() -> describe(new int[0], new int[0], 0));
	}

	@Test
	void zeroStrideIsRejectedWithAttributes() {
		assertThrows(IllegalArgumentException.class, () -> describe(new int[] {1}, new int[] {0}, 0));
	}

	@Test
	void negativeStrideIsAlwaysRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(new int[0], new int[0], -1));
		assertThrows(IllegalArgumentException.class, () -> describe(new int[] {1}, new int[] {0}, -4));
	}

	@Test
	void positiveStrideWithAttributesIsAllowed() {
		assertDoesNotThrow(() -> describe(new int[] {1, 2}, new int[] {0, 12}, 28));
	}

	@Test
	void mismatchedAttributeArrayLengthsAreRejectedRegardlessOfStride() {
		assertThrows(IllegalArgumentException.class, () -> describe(new int[] {1}, new int[] {0, 4}, 12));
	}
}
