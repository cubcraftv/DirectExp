package dev.cubcraftv.directexp.client.backend;

/**
 * Packed clear colors to the float components {@code ClearRenderTargetView}
 * takes. Blaze3D hands clear colors over as packed ARGB ints (the same layout
 * as {@code net.minecraft.util.ARGB}: alpha in the top byte, blue in the
 * bottom). NOTE: {@code DxTexture.fillRegion} (the texture-level clear) reads
 * the int as bytes in memory order instead, i.e. ABGR; that looks like a
 * red/blue swap for non-gray colors and is tracked in the 4.6 source of truth.
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
}
