package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxBufferBindingTest {
	@Test
	void usageBitsMapToBindPurposes() {
		assertEquals(DxBridge.BUFFER_BIND_VERTEX, DxBufferBinding.bindMask(32));
		assertEquals(DxBridge.BUFFER_BIND_INDEX, DxBufferBinding.bindMask(64));
		assertEquals(DxBridge.BUFFER_BIND_CONSTANT, DxBufferBinding.bindMask(128));
		assertEquals(DxBridge.BUFFER_BIND_SHADER_RESOURCE, DxBufferBinding.bindMask(256));
		assertEquals(DxBridge.BUFFER_BIND_VERTEX | DxBridge.BUFFER_BIND_CONSTANT, DxBufferBinding.bindMask(32 | 128 | 8));
		assertEquals(DxBridge.BUFFER_BIND_SHADER_RESOURCE | DxBridge.BUFFER_BIND_VERTEX, DxBufferBinding.bindMask(32 | 256));
	}

	@Test
	void copyOnlyBuffersHaveNoBindPurpose() {
		assertEquals(0, DxBufferBinding.bindMask(8));
		assertEquals(0, DxBufferBinding.bindMask(16));
	}

	@Test
	void alignedSizeRoundsUpToSixteen() {
		assertEquals(0L, DxBufferBinding.alignedSize(0));
		assertEquals(16L, DxBufferBinding.alignedSize(1));
		assertEquals(16L, DxBufferBinding.alignedSize(16));
		assertEquals(32L, DxBufferBinding.alignedSize(17));
	}

	@Test
	void constantBuffersAreMirroredOnlyUpToSixtyFourKiB() {
		assertTrue(DxBufferBinding.canMirrorNatively(DxBridge.BUFFER_BIND_CONSTANT, 65536));
		assertFalse(DxBufferBinding.canMirrorNatively(DxBridge.BUFFER_BIND_CONSTANT, 65537));
		assertTrue(DxBufferBinding.canMirrorNatively(DxBridge.BUFFER_BIND_VERTEX, 10_000_000));
		assertFalse(DxBufferBinding.canMirrorNatively(0, 64));
		assertFalse(DxBufferBinding.canMirrorNatively(DxBridge.BUFFER_BIND_VERTEX, 0));
	}
}
