package dev.cubcraftv.directexp.bridge;

import java.nio.ByteBuffer;

/**
 * Java-side surface for calling into the native D3D11/DXGI bridge.
 *
 * Scope note: as of Milestone 2's start, only {@link #probe()} has a real
 * native implementation. Every other method below documents the intended
 * contract (mirroring MetalExp's MetalBridge shape) but throws by default
 * until the corresponding native entrypoint and Java override exist. Do not
 * remove a default method just because it's unimplemented -- it's there so
 * the eventual real implementation has an agreed-upon signature to target,
 * and so callers written against this interface compile today.
 */
public interface DxBridge {
	DxBridgeProbe probe();

	default DxBridgeProbe probeSurface(long win32WindowHandle) {
		throw new UnsupportedOperationException("DirectX surface probe is not implemented by this bridge yet.");
	}

	default DxHostSurfaceBootstrap bootstrapSurface(long win32WindowHandle) {
		throw new UnsupportedOperationException("DirectX surface bootstrap is not implemented by this bridge yet.");
	}

	default void configureSurface(long nativeSurfaceHandle, int width, int height, boolean vsync) {
		throw new UnsupportedOperationException("DirectX surface configure is not implemented by this bridge yet.");
	}

	default void acquireSurface(long nativeSurfaceHandle) {
		throw new UnsupportedOperationException("DirectX surface acquire is not implemented by this bridge yet.");
	}

	default long createCommandContext() {
		throw new UnsupportedOperationException("DirectX command context creation is not implemented by this bridge yet.");
	}

	default void submitCommandContext(long nativeCommandContextHandle) {
		throw new UnsupportedOperationException("DirectX command context submit is not implemented by this bridge yet.");
	}

	default void releaseCommandContext(long nativeCommandContextHandle) {
	}

	default void blitSurfaceRgba8(long nativeSurfaceHandle, ByteBuffer rgbaPixels, int width, int height) {
		throw new UnsupportedOperationException("DirectX surface blit is not implemented by this bridge yet.");
	}

	default long createTexture2D(int width, int height, int mipLevels) {
		throw new UnsupportedOperationException("DirectX texture creation is not implemented by this bridge yet.");
	}

	default void uploadTextureRgba8(long nativeTextureHandle, int mipLevel, ByteBuffer rgbaPixels, int width, int height) {
		throw new UnsupportedOperationException("DirectX texture upload is not implemented by this bridge yet.");
	}

	default void releaseTexture(long nativeTextureHandle) {
	}

	default void blitSurfaceTexture(long nativeCommandContextHandle, long nativeSurfaceHandle, long nativeTextureHandle) {
		throw new UnsupportedOperationException("DirectX surface texture blit is not implemented by this bridge yet.");
	}

	default void presentSurface(long nativeSurfaceHandle) {
		throw new UnsupportedOperationException("DirectX surface present is not implemented by this bridge yet.");
	}

	default void releaseSurface(long nativeSurfaceHandle) {
	}
}
