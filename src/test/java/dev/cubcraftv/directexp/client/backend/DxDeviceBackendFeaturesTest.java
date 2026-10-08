package dev.cubcraftv.directexp.client.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins what the device tells Blaze3D (and Sodium) it supports. Sodium's multi-draw
 * path fails at the first terrain draw if the interleaved flag or the limit regress,
 * which is a confusing place to find out.
 */
final class DxDeviceBackendFeaturesTest {
	/** ModelQuadFacing.COUNT (7) * RenderRegion.REGION_SIZE (8 * 4 * 8) + 1 in Sodium 26.2. */
	private static final int SODIUM_MAX_BATCH_DRAWS = 7 * 256 + 1;

	@Test
	void reportsInterleavedMultiDrawBecauseTheRenderPassImplementsIt() {
		assertTrue(DxDeviceBackend.FEATURES.multiDrawDirectInterleaved());
	}

	@Test
	void drawCountLimitCoversSodiumsLargestRegionBatch() {
		assertTrue(DxDeviceBackend.DEFAULT_LIMITS.maxMultiDrawDirectInterleavedDrawCount() >= SODIUM_MAX_BATCH_DRAWS);
		assertEquals(65536, DxDeviceBackend.DEFAULT_LIMITS.maxMultiDrawDirectInterleavedDrawCount());
	}

	@Test
	void everythingElseStaysOffOnPurpose() {
		assertFalse(DxDeviceBackend.FEATURES.shaderDrawParameters());
		assertFalse(DxDeviceBackend.FEATURES.multiDrawDirectSeparate());
		assertFalse(DxDeviceBackend.FEATURES.multiDrawIndirect());
		assertFalse(DxDeviceBackend.FEATURES.drawIndirect());
		assertFalse(DxDeviceBackend.FEATURES.nonZeroFirstInstance());
		// Must stay false: true would route Sodium to its OpenGL-only mapped staging buffer.
		assertFalse(DxDeviceBackend.FEATURES.persistentMapping());
	}
}
