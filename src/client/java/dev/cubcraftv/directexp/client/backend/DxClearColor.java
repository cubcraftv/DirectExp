package dev.cubcraftv.directexp.client.backend;

import java.nio.ByteBuffer;
import org.joml.Vector4fc;

/**
 * Converts between packed ARGB colors and RGBA8 CPU texture storage.
 * Blaze3D render-pass clears already arrive as float RGBA components and do
 * not use this class.
 */
final class DxClearColor {
	private DxClearColor() {
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
