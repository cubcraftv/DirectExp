package dev.cubcraftv.directexp.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the compact constructor's validation: per-slot stride arrays in
 * D3D11 input-slot width, attribute arrays all one length, and the
 * doom-teller ranges for slots and step rates. The "slot whose attributes
 * need it must have a positive stride" rule lives client-side in
 * {@code DxPipelineStateMapping} (record-level, a fully-zero stride table is
 * legitimate for pipelines with no vertex inputs -- vanilla's fullscreen-quad
 * shaders: {@code blit}, {@code flat_clouds}, {@code animate_sprite_blit},
 * {@code lightmap}, {@code entity_outline_blit}, {@code tracy_blit}).
 */
class DxPipelineStateDescriptorTest {
	private static DxPipelineStateDescriptor describe(int[] formats, int[] offsets, int[] slots, int[] stepRates, int[] strides) {
		return new DxPipelineStateDescriptor(
			0, false, 0, 0, 0, 0, 0, 0, 0xF, false, false, 0, 0, 0, 0, 0.0F,
			formats, offsets, slots, stepRates, strides
		);
	}

	private static int[] plainStrides() {
		return new int[DxPipelineStateDescriptor.VERTEX_BUFFER_SLOT_COUNT];
	}

	private static int[] stridesWith(int slot, int stride) {
		int[] strides = plainStrides();
		strides[slot] = stride;
		return strides;
	}

	@Test
	void zeroStridesWithNoAttributesAreAllowed() {
		assertDoesNotThrow(() -> describe(new int[0], new int[0], new int[0], new int[0], plainStrides()));
	}

	@Test
	void zeroStrideSlotUsesAreValidatedByDescribeNotTheRecord() {
		// The record accepts attribute arrays whose slot has stride 0: the
		// positive stride requirement per used slot is (deliberately) part of
		// the Blaze3D -> D3D11 mapping and the native call, not this record.
		assertDoesNotThrow(() -> describe(new int[] {1}, new int[] {0}, new int[] {0}, new int[] {0}, plainStrides()));
	}

	@Test
	void negativeStrideIsAlwaysRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(new int[0], new int[0], new int[0], new int[0], stridesWith(0, -1)));
	}

	@Test
	void negativeStepRateIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(new int[] {1}, new int[] {0}, new int[] {0}, new int[] {-1}, stridesWith(0, 16)));
	}

	@Test
	void outOfRangeAttributeSlotIsRejected() {
		int[] strides = stridesWith(31, 16);
		assertDoesNotThrow(() -> describe(new int[] {1}, new int[] {0}, new int[] {31}, new int[] {0}, strides));
		assertThrows(IllegalArgumentException.class, () -> describe(new int[] {1}, new int[] {0}, new int[] {32}, new int[] {0}, strides));
	}

	@Test
	void wrongStrideTableWidthIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(new int[0], new int[0], new int[0], new int[0], new int[DxPipelineStateDescriptor.VERTEX_BUFFER_SLOT_COUNT - 1]));
	}

	@Test
	void mismatchedAttributeArrayLengthsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(new int[] {1}, new int[] {0, 4}, new int[] {0}, new int[] {0}, stridesWith(0, 16)));
		assertThrows(IllegalArgumentException.class, () -> describe(new int[] {1}, new int[] {0}, new int[] {0, 0}, new int[] {0}, stridesWith(0, 16)));
	}

	@Test
	void supportsEverythingTogether() {
		assertDoesNotThrow(() -> describe(
			new int[] {6, 0x10}, new int[] {0, 12}, new int[] {0, 1}, new int[] {0, 1}, stridesWith(1, 32)
		));
	}
}
