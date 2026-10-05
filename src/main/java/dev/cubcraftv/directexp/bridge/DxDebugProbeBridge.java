package dev.cubcraftv.directexp.bridge;

/** Native operations used only by opt-in rendering investigations. */
public interface DxDebugProbeBridge {
	/** Synchronously reads one texel of a native RGBA8 texture as {@code R | G<<8 | B<<16 | A<<24}. */
	default int readTexturePixelRgba8(long nativeTextureHandle, int mipLevel, int x, int y) {
		throw new UnsupportedOperationException("DirectX texel readback is not implemented by this bridge yet.");
	}

	/** Describes the D3D11 state currently bound for the pixel stage. */
	default String describeDrawState(int srvSlot, long expectedTextureHandle) {
		return "(unsupported by this bridge)";
	}

	/** Samples a texture view's centre through known-good shaders and reports the results. */
	default String runSamplingExperiment(long nativeTextureViewHandle) {
		return "(unsupported by this bridge)";
	}

	/**
	 * Re-issues the current draw into a scratch target with the real vertex stage, substituting controlled pixel
	 * shaders, and reports the texel at (x, y) per variant.
	 */
	default String runPixelSubstitutionProbe(int x, int y, int vertexCount, int firstVertex) {
		return "(unsupported by this bridge)";
	}
}
