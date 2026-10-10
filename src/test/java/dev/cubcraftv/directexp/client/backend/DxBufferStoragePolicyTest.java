package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxBufferStoragePolicyTest {
	private static final int V = DxBridge.BUFFER_BIND_VERTEX;
	private static final int I = DxBridge.BUFFER_BIND_INDEX;
	private static final long MIN = 1L << 20;

	@Test
	void onlyLargeNativeVertexOrIndexOnlyBuffersQualify() {
		assertTrue(DxBufferStoragePolicy.gpuOnly(V, MIN, true, true, MIN));
		assertTrue(DxBufferStoragePolicy.gpuOnly(I, MIN * 4, true, true, MIN));
		assertTrue(DxBufferStoragePolicy.gpuOnly(V | I, MIN, true, true, MIN));
		assertFalse(DxBufferStoragePolicy.gpuOnly(V, MIN - 1, true, true, MIN));
		assertFalse(DxBufferStoragePolicy.gpuOnly(V, MIN, false, true, MIN));
		assertFalse(DxBufferStoragePolicy.gpuOnly(V, MIN, true, false, MIN));
		assertFalse(DxBufferStoragePolicy.gpuOnly(DxBridge.BUFFER_BIND_CONSTANT, MIN, true, true, MIN));
		assertFalse(DxBufferStoragePolicy.gpuOnly(V | DxBridge.BUFFER_BIND_CONSTANT, MIN, true, true, MIN));
		assertFalse(DxBufferStoragePolicy.gpuOnly(DxBridge.BUFFER_BIND_SHADER_RESOURCE, MIN, true, true, MIN));
		assertFalse(DxBufferStoragePolicy.gpuOnly(0, MIN, true, true, MIN));
	}

	@Test
	void thresholdOverrideZeroMeansEveryEligibleBufferAndNegativeDisables() {
		assertTrue(DxBufferStoragePolicy.gpuOnly(V, 16, true, true, 0));
		assertFalse(DxBufferStoragePolicy.gpuOnly(V, Long.MAX_VALUE, true, true, -1));
	}

	@Test
	void propertyParsingFallsBackToTheDefault() {
		assertEquals(DxBufferStoragePolicy.DEFAULT_MINIMUM_BYTES, DxBufferStoragePolicy.readMinimumBytes(null));
		assertEquals(DxBufferStoragePolicy.DEFAULT_MINIMUM_BYTES, DxBufferStoragePolicy.readMinimumBytes("  "));
		assertEquals(DxBufferStoragePolicy.DEFAULT_MINIMUM_BYTES, DxBufferStoragePolicy.readMinimumBytes("lots"));
		assertEquals(4096L, DxBufferStoragePolicy.readMinimumBytes(" 4096 "));
		assertEquals(-1L, DxBufferStoragePolicy.readMinimumBytes("-1"));
	}
}
