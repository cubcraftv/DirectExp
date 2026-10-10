package dev.cubcraftv.directexp.client.backend;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G4: the decoder behind the opt-in push-constant trace, fed Sodium's per-region block. */
class DxPushConstantTraceTest {
	private static ByteBuffer sodiumBlock(float x, float y, float z, int time, int region) {
		ByteBuffer block = ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());
		block.putFloat(x).putFloat(y).putFloat(z).putInt(time).putInt(region);
		block.flip();
		return block;
	}

	@Test
	void decodesSodiumsTwentyByteBlockPerRegion() {
		String text = DxPushConstantTrace.describe("sodium:opaque", 3, 32, sodiumBlock(-32.0F, 64.0F, 96.0F, 41234, 7));

		assertTrue(text.contains("u_RegionOffset=(-32.0, 64.0, 96.0)"), text);
		assertTrue(text.contains("u_CurrentTime=41234"), text);
		assertTrue(text.contains("u_RegionID=7"), text);
		assertTrue(text.contains("slot 3"), text);
		assertFalse(text.contains("WARNING"), text); // 20 B supplied into a 32 B rounded cbuffer is the normal case
	}

	@Test
	void regionIdIsPrintedUnsignedAndDecodingLeavesTheBufferUntouched() {
		ByteBuffer block = sodiumBlock(0, 0, 0, 0, -1);
		block.position(0);

		String text = DxPushConstantTrace.describe("p", 0, 32, block);

		assertTrue(text.contains("u_RegionID=4294967295"), text);
		org.junit.jupiter.api.Assertions.assertEquals(0, block.position());
		org.junit.jupiter.api.Assertions.assertEquals(20, block.limit());
	}

	@Test
	void unknownLayoutsAreDumpedAsWordsAndShortBlocksAreFlagged() {
		ByteBuffer data = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
		data.putInt(0xCAFE).putInt(1).flip();

		String text = DxPushConstantTrace.describe("other", 1, 32, data);

		assertTrue(text.contains("0xcafe"), text);
		assertTrue(text.contains("WARNING"), text);
	}

	@Test
	void traceIsOffUnlessEnabled() {
		assertFalse(DxPushConstantTrace.shouldTrace());
	}
}
