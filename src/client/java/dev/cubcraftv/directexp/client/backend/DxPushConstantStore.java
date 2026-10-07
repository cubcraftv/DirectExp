package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.client.api.DxPushConstants;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * The bytes behind {@link DxPushConstants}: one small buffer per render pass,
 * overwritten in place by every {@link #set} so the per-region call a terrain
 * renderer makes does not allocate. Kept separate from
 * {@link DxRenderPassBackend} (which needs Minecraft classes) so the copy,
 * validation and "unset" semantics are unit-testable.
 */
final class DxPushConstantStore {
	private ByteBuffer storage;
	private int length;

	/** Copies {@code [data.position(), data.limit())}; the source is neither changed nor kept. */
	void set(ByteBuffer data) {
		Objects.requireNonNull(data, "data");
		int size = data.remaining();
		if (size > DxPushConstants.MAX_BYTES) {
			throw new IllegalArgumentException("Push constants are limited to " + DxPushConstants.MAX_BYTES + " bytes, got " + size + ".");
		}

		this.length = size;
		if (size == 0) {
			return;
		}
		if (this.storage == null) {
			this.storage = ByteBuffer.allocate(DxPushConstants.MAX_BYTES).order(ByteOrder.nativeOrder());
		}
		this.storage.clear();
		this.storage.put(data.duplicate());
		this.storage.flip();
	}

	/** A read-only view of the current bytes (position 0, limit = length), or null when nothing is set. */
	ByteBuffer bytes() {
		if (this.length == 0) {
			return null;
		}
		return this.storage.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
	}
}
