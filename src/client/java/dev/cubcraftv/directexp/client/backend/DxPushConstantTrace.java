package dev.cubcraftv.directexp.client.backend;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in trace of the bytes that reach the push-constant constant buffer, for
 * verifying the push-constant path against a real renderer (Sodium) on a real
 * D3D11 device: {@code -Ddirectexp.debug.pushconstants=true}. It logs the
 * first {@link #budget()} draws, then goes quiet, so a normal play session is
 * not flooded ({@code -Ddirectexp.debug.pushconstants.draws=N} changes the count).
 *
 * <p>What to look for: Sodium's block is
 * {@code vec3 u_RegionOffset; int u_CurrentTime; uint u_RegionID} = 20 bytes
 * (std140: offsets 0, 12, 16). Across draws the offsets must change per region
 * (multiples of 16 blocks per axis), {@code u_RegionID} must index into the
 * section-time texel buffer (<256 regions here), and a pipeline whose reflected
 * constant buffer is larger than the 20 bytes (it is rounded up to 32) must
 * still show the same values. The decoding is separate from the logging so it
 * is unit-testable.
 */
final class DxPushConstantTrace {
	static final String ENABLED_PROPERTY = "directexp.debug.pushconstants";
	static final String DRAWS_PROPERTY = "directexp.debug.pushconstants.draws";
	static final int SODIUM_BLOCK_BYTES = 20;

	private static final AtomicInteger REMAINING = new AtomicInteger(Integer.getInteger(DRAWS_PROPERTY, 32));

	private DxPushConstantTrace() {
	}

	static boolean enabled() {
		return Boolean.getBoolean(ENABLED_PROPERTY);
	}

	static int budget() {
		return Integer.getInteger(DRAWS_PROPERTY, 32);
	}

	/** True (and consumes budget) while there are draws left to trace. */
	static boolean shouldTrace() {
		return enabled() && REMAINING.getAndDecrement() > 0;
	}

	/** A one-line description of {@code bytes} (position/limit respected, not changed). */
	static String describe(String pipeline, int slot, int reflectedBlockBytes, ByteBuffer bytes) {
		ByteBuffer view = bytes.duplicate().order(ByteOrder.nativeOrder());
		int length = view.remaining();
		StringBuilder text = new StringBuilder("push constants for ")
			.append(pipeline).append(" (cbuffer slot ").append(slot)
			.append(", reflected block ").append(reflectedBlockBytes).append(" B, supplied ").append(length).append(" B): ");
		if (length == SODIUM_BLOCK_BYTES) {
			int base = view.position();
			text.append("as Sodium {u_RegionOffset=(")
				.append(view.getFloat(base)).append(", ").append(view.getFloat(base + 4)).append(", ").append(view.getFloat(base + 8))
				.append("), u_CurrentTime=").append(view.getInt(base + 12))
				.append(", u_RegionID=").append(Integer.toUnsignedString(view.getInt(base + 16))).append('}');
		} else {
			text.append("words=[");
			for (int offset = 0; offset + 4 <= length; offset += 4) {
				if (offset > 0) {
					text.append(", ");
				}
				text.append("0x").append(Integer.toHexString(view.getInt(view.position() + offset)));
			}
			text.append(']');
		}
		// std140 rounds a block up to a multiple of 16 (Sodium's 20 bytes -> 32), so only a
		// supply that falls short even after that rounding means the shader reads bytes
		// nobody wrote.
		if (reflectedBlockBytes > 0 && ((length + 15) & ~15) < reflectedBlockBytes) {
			text.append(" [WARNING: shader block is larger than the supplied bytes; the tail is zero-filled]");
		}
		return text.toString();
	}
}
