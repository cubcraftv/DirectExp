package dev.cubcraftv.directexp.bridge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the compact constructor's validation: per-target blend arrays (one
 * entry per D3D11 render-target slot), per-slot stride arrays in input-slot
 * width, attribute arrays all one length, and the ranges for slots and step
 * rates. The "slot whose attributes need it must have a positive stride" rule
 * lives client-side in {@code DxPipelineStateMapping} (record-level, a fully-
 * zero stride table is legitimate for pipelines with no vertex inputs --
 * vanilla's fullscreen-quad shaders: {@code blit}, {@code flat_clouds},
 * {@code animate_sprite_blit}, {@code lightmap}, {@code entity_outline_blit},
 * {@code tracy_blit}).
 */
class DxPipelineStateDescriptorTest {
	private static final int NO_BLEND = 0;

	private static DxPipelineStateDescriptor describe(
		int targetCount,
		int[] formats,
		int[] offsets,
		int[] slots,
		int[] stepRates,
		int[] strides
	) {
		int[] blendEnable = new int[targetCount];
		int[] srcColor = new int[targetCount];
		int[] dstColor = new int[targetCount];
		int[] colorOp = new int[targetCount];
		int[] srcAlpha = new int[targetCount];
		int[] dstAlpha = new int[targetCount];
		int[] alphaOp = new int[targetCount];
		int[] writeMask = new int[targetCount];
		for (int target = 0; target < targetCount; target++) {
			writeMask[target] = 0xF;
		}
		return new DxPipelineStateDescriptor(
			0, targetCount, blendEnable, srcColor, dstColor, colorOp,
			srcAlpha, dstAlpha, alphaOp, writeMask,
			false, false, 0, 0, 0, 0, 0.0F,
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
		assertDoesNotThrow(() -> describe(1, new int[0], new int[0], new int[0], new int[0], plainStrides()));
	}

	@Test
	void zeroStrideSlotUsesAreValidatedByDescribeNotTheRecord() {
		// The record accepts attribute arrays whose slot has stride 0: the
		// positive stride requirement per used slot is (deliberately) part of
		// the Blaze3D -> D3D11 mapping and the native call, not this record.
		assertDoesNotThrow(() -> describe(1, new int[] {1}, new int[] {0}, new int[] {0}, new int[] {0}, plainStrides()));
	}

	@Test
	void negativeStrideIsAlwaysRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(1, new int[0], new int[0], new int[0], new int[0], stridesWith(0, -1)));
	}

	@Test
	void negativeStepRateIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(1, new int[] {1}, new int[] {0}, new int[] {0}, new int[] {-1}, stridesWith(0, 16)));
	}

	@Test
	void outOfRangeAttributeSlotIsRejected() {
		int[] strides = stridesWith(31, 16);
		assertDoesNotThrow(() -> describe(1, new int[] {1}, new int[] {0}, new int[] {31}, new int[] {0}, strides));
		assertThrows(IllegalArgumentException.class, () -> describe(1, new int[] {1}, new int[] {0}, new int[] {32}, new int[] {0}, strides));
	}

	@Test
	void wrongStrideTableWidthIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(1, new int[0], new int[0], new int[0], new int[0], new int[DxPipelineStateDescriptor.VERTEX_BUFFER_SLOT_COUNT - 1]));
	}

	@Test
	void mismatchedAttributeArrayLengthsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> describe(1, new int[] {1}, new int[] {0, 4}, new int[] {0}, new int[] {0}, stridesWith(0, 16)));
		assertThrows(IllegalArgumentException.class, () -> describe(1, new int[] {1}, new int[] {0}, new int[] {0, 0}, new int[] {0}, stridesWith(0, 16)));
	}

	@Test
	void targetCountMustStayInD3d11Range() {
		assertDoesNotThrow(() -> describe(DxPipelineStateDescriptor.MAX_COLOR_TARGETS, new int[0], new int[0], new int[0], new int[0], plainStrides()));
		assertThrows(IllegalArgumentException.class, () -> describe(DxPipelineStateDescriptor.MAX_COLOR_TARGETS + 1, new int[0], new int[0], new int[0], new int[0], plainStrides()));
		assertThrows(IllegalArgumentException.class, () -> describe(0, new int[0], new int[0], new int[0], new int[0], plainStrides()));
	}

	@Test
	void targetArraysMustMatchTheTargetCount() {
		int[] formats = new int[0];
		int[] strides = plainStrides();
		// Practical construction helpers produce correct-length arrays; feed a
		// mismatch by building the record manually with a wrong write-mask size.
		assertThrows(IllegalArgumentException.class, () -> new DxPipelineStateDescriptor(
			0, 2, new int[] {NO_BLEND, NO_BLEND}, new int[2], new int[2], new int[2],
			new int[2], new int[2], new int[2], new int[3],
			false, false, 8, 1, 3, 0, 0.0F,
			formats, new int[0], new int[0], new int[0], strides
		));
	}

	@Test
	void writeMaskMustBeAColorWriteEnableMask() {
		assertThrows(IllegalArgumentException.class, () -> new DxPipelineStateDescriptor(
			0, 1, new int[] {NO_BLEND}, new int[1], new int[1], new int[1],
			new int[1], new int[1], new int[1], new int[] {0x1F},
			false, false, 8, 1, 3, 0, 0.0F,
			new int[0], new int[0], new int[0], new int[0], plainStrides()
		));
	}

	@Test
	void stateArrayCarriesOneStripPerTargetAndDefaultStripsStayZero() {
		DxPipelineStateDescriptor d = describe(2, new int[0], new int[0], new int[0], new int[0], plainStrides());
		int[] s = d.stateArray();
		assertEquals(DxPipelineStateDescriptor.STATE_COUNT, s.length);
		assertEquals(2, s[DxPipelineStateDescriptor.STATE_TARGET_COUNT]);
		assertEquals(0xF, s[DxPipelineStateDescriptor.targetStateIndex(0, DxPipelineStateDescriptor.TARGET_STATE_WRITE_MASK)]);
		assertEquals(0xF, s[DxPipelineStateDescriptor.targetStateIndex(1, DxPipelineStateDescriptor.TARGET_STATE_WRITE_MASK)]);
		assertEquals(0, s[DxPipelineStateDescriptor.targetStateIndex(2, DxPipelineStateDescriptor.TARGET_STATE_BLEND_ENABLE)]);
		assertEquals(0, s[DxPipelineStateDescriptor.targetStateIndex(7, DxPipelineStateDescriptor.TARGET_STATE_WRITE_MASK)]);
	}

	@Test
	void supportsEverythingTogether() {
		assertDoesNotThrow(() -> describe(
			2,
			new int[] {6, 0x10}, new int[] {0, 12}, new int[] {0, 1}, new int[] {0, 1}, stridesWith(1, 32)
		));
	}
}
