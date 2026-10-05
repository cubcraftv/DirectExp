package dev.cubcraftv.directexp.client.backend;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;
import org.joml.Vector4f;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxDrawHelpersTest {
	@Test
	void packedArgbWritesRgbaByteOrder() {
		ByteBuffer rgba = ByteBuffer.allocate(4);
		DxClearColor.writeRgba8(rgba, 0, 0x80FF4020);

		assertArrayEquals(new byte[] { (byte) 0xFF, 0x40, 0x20, (byte) 0x80 }, rgba.array());
	}

	@Test
	void clearColorVectorPacksAsArgb() {
		assertEquals(0x80FF4020, DxClearColor.packArgb(new Vector4f(1.0F, 0x40 / 255.0F, 0x20 / 255.0F, 0x80 / 255.0F)));
	}

	@Test
	void scissorKeepsBlaze3dsTopLeftOrigin() {
		// Vulkan passes the rectangle straight to a top-left-origin API, so no Y flip.
		assertArrayEquals(new int[] { 10, 5, 20, 10 }, DxScissorMapping.toTopLeftRect(10, 5, 20, 10, 100, 50));
		assertArrayEquals(new int[] { 0, 0, 100, 50 }, DxScissorMapping.toTopLeftRect(0, 0, 100, 50, 100, 50));
	}

	@Test
	void scissorIsClampedToTheTarget() {
		assertArrayEquals(new int[] { 0, 0, 15, 15 }, DxScissorMapping.toTopLeftRect(-5, -5, 20, 20, 100, 50));
		assertArrayEquals(new int[] { 90, 40, 10, 10 }, DxScissorMapping.toTopLeftRect(90, 40, 50, 50, 100, 50));
		assertEquals(0, DxScissorMapping.toTopLeftRect(200, 0, 10, 10, 100, 50)[2]);
	}

	@Test
	void disabledScissorIsTheFullTarget() {
		assertArrayEquals(new int[] { 0, 0, 640, 480 }, DxScissorMapping.fullRect(640, 480));
		assertTrue(DxScissorMapping.fullRect(1, 1)[2] > 0);
	}
}
