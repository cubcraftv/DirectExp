package dev.cubcraftv.directexp.client.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.cubcraftv.directexp.client.api.DxPushConstants;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class DxPushConstantStoreTest {
	/** Sodium's block: vec3 offset, int time, uint region id (20 bytes). */
	private static ByteBuffer sodiumBlock(float x, float y, float z, int time, int region) {
		ByteBuffer data = ByteBuffer.allocate(20).order(ByteOrder.nativeOrder());
		data.putFloat(x).putFloat(y).putFloat(z).putInt(time).putInt(region).flip();
		return data;
	}

	@Test
	void unsetStoreHasNoBytes() {
		assertNull(new DxPushConstantStore().bytes());
	}

	@Test
	void copiesTheRemainingBytesAndDoesNotKeepTheCallersBuffer() {
		DxPushConstantStore store = new DxPushConstantStore();
		ByteBuffer source = sodiumBlock(1f, 2f, 3f, 40, 5);
		store.set(source);

		// The caller's buffer is untouched (position still 0) and can be reused straight away.
		assertEquals(0, source.position());
		source.putFloat(0, 99f);

		ByteBuffer bytes = store.bytes();
		assertNotNull(bytes);
		assertEquals(20, bytes.remaining());
		assertEquals(1f, bytes.getFloat(0));
		assertEquals(2f, bytes.getFloat(4));
		assertEquals(3f, bytes.getFloat(8));
		assertEquals(40, bytes.getInt(12));
		assertEquals(5, bytes.getInt(16));
		assertTrue(bytes.isReadOnly());
	}

	@Test
	void honoursPositionAndLimitOfTheSource() {
		DxPushConstantStore store = new DxPushConstantStore();
		ByteBuffer source = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
		source.putInt(0, 111).putInt(4, 222).putInt(8, 333).putInt(12, 444);
		source.position(4).limit(12);
		store.set(source);

		ByteBuffer bytes = store.bytes();
		assertEquals(8, bytes.remaining());
		assertEquals(222, bytes.getInt(0));
		assertEquals(333, bytes.getInt(4));
	}

	@Test
	void laterSetReplacesEarlierAndAShorterOneShrinksTheView() {
		DxPushConstantStore store = new DxPushConstantStore();
		store.set(sodiumBlock(1f, 2f, 3f, 4, 5));
		store.set(ByteBuffer.allocate(4).order(ByteOrder.nativeOrder()).putInt(0, 7));

		ByteBuffer bytes = store.bytes();
		assertEquals(4, bytes.remaining());
		assertEquals(7, bytes.getInt(0));
	}

	@Test
	void everyCallReturnsAnIndependentViewSoStagingCannotDisturbTheStore() {
		DxPushConstantStore store = new DxPushConstantStore();
		store.set(sodiumBlock(1f, 2f, 3f, 4, 5));
		ByteBuffer first = store.bytes();
		first.position(first.limit());

		assertEquals(20, store.bytes().remaining());
	}

	@Test
	void emptyBufferClearsThePushConstants() {
		DxPushConstantStore store = new DxPushConstantStore();
		store.set(sodiumBlock(1f, 2f, 3f, 4, 5));
		store.set(ByteBuffer.allocate(0));

		assertNull(store.bytes());
	}

	@Test
	void acceptsExactlyTheMaximumAndRejectsMoreOrNull() {
		DxPushConstantStore store = new DxPushConstantStore();
		store.set(ByteBuffer.allocate(DxPushConstants.MAX_BYTES));
		assertEquals(DxPushConstants.MAX_BYTES, store.bytes().remaining());

		assertThrows(IllegalArgumentException.class, () -> store.set(ByteBuffer.allocate(DxPushConstants.MAX_BYTES + 1)));
		assertThrows(NullPointerException.class, () -> store.set(null));
		// A rejected call must not disturb what was set before.
		assertEquals(DxPushConstants.MAX_BYTES, store.bytes().remaining());
	}
}
