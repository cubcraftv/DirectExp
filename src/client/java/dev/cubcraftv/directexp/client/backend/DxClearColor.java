package dev.cubcraftv.directexp.client.backend;

import java.nio.ByteBuffer;
import org.joml.Vector4fc;

/**
 * Packed clear colors to the float components {@code ClearRenderTargetView}
 * takes. Blaze3D hands clear colors over as packed ARGB ints (the same layout
 * as {@code net.minecraft.util.ARGB}: alpha in the top byte, blue in the
 * bottom. RGBA8 CPU mirrors must explicitly write those channels in R, G, B,
 * A byte order rather than relying on the machine's integer byte order.
 */
final class DxClearColor {
	private DxClearColor() {
	}

	/** {red, green, blue, alpha}, each 0..1. */
	static float[] unpackArgb(int argb) {
		return new float[] {
			((argb >>> 16) & 0xFF) / 255.0F,
			((argb >>> 8) & 0xFF) / 255.0F,
			(argb & 0xFF) / 255.0F,
			((argb >>> 24) & 0xFF) / 255.0F
		};
	}

	static void writeRgba8(ByteBuffer destination, int offset, int argb) {
		destination.put(offset, (byte) (argb >>> 16));
		destination.put(offset + 1, (byte) (argb >>> 8));
		destination.put(offset + 2, (byte) argb);
		destination.put(offset + 3, (byte) (argb >>> 24));
	}

	static int packArgb(Vector4fc rgba) {
		int red = Math.round(clamp(rgba.x()) * 255.0F);
		int green = Math.round(clamp(rgba.y()) * 255.0F);
		int blue = Math.round(clamp(rgba.z()) * 255.0F);
		int alpha = Math.round(clamp(rgba.w()) * 255.0F);
		return alpha << 24 | red << 16 | green << 8 | blue;
	}

	private static float clamp(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}
}
