package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CPU-side texture storage, with a best-effort attempt to also create a
 * real native D3D11 texture handle through {@link DxBridge} (Milestone 3:
 * {@code createTexture}/{@code uploadTextureRgba8} are real
 * {@link dev.cubcraftv.directexp.bridge.NativeDxBridge} calls now, not
 * default-throw stubs). The attempt can still legitimately fail --
 * {@link #hasNativeTextureHandle()} is false whenever no DirectX surface
 * has been bootstrapped yet, or the native call otherwise throws -- in
 * which case this class silently keeps using its CPU-side storage as a
 * fallback (see {@link DxSurfaceBackend#blitFromTexture}, which is what
 * actually branches on {@link #hasNativeTextureHandle()}) rather than
 * failing outright -- the direct ByteBuffer storage below is always kept
 * populated and correct regardless of whether the native handle exists.
 */
final class DxTexture extends BaseGpuTexture {
	private final DxBridge dxBridge;
	private final ByteBuffer[] mipStorage;
	private final int layerCount;
	private final boolean cubemap;
	private final long nativeTextureHandle;
	private final int nativeBindMask;
	private final AtomicBoolean loggedNativeTextureUploadFailure = new AtomicBoolean(false);
	private boolean closed;

	DxTexture(DxBridge dxBridge, int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.dxBridge = dxBridge;
		this.mipStorage = new ByteBuffer[mipLevels];
		this.layerCount = depthOrLayers;
		this.cubemap = (usage & DxTextureBinding.USAGE_CUBEMAP) != 0;
		this.nativeBindMask = DxTextureBinding.bindMask(usage, format.hasDepthAspect());
		this.nativeTextureHandle = createNativeTextureHandle(format, width, height, depthOrLayers, mipLevels, this.cubemap);
		for (int mipLevel = 0; mipLevel < mipLevels; mipLevel++) {
			int mipWidth = getWidth(mipLevel);
			int mipHeight = getHeight(mipLevel);
			int mipBytes = Math.max(1, mipWidth * mipHeight * depthOrLayers * format.blockSize());
			this.mipStorage[mipLevel] = ByteBuffer.allocateDirect(mipBytes).order(ByteOrder.nativeOrder());
		}
	}

	void writeRegion(
		ByteBuffer source,
		int sourceBytesPerPixel,
		int sourceRowLength,
		int mipLevel,
		int layer,
		int dstX,
		int dstY,
		int width,
		int height,
		int srcX,
		int srcY
	) {
		if (layer < 0 || layer >= this.layerCount) {
			throw new IllegalArgumentException("Texture layer is outside the allocated layer range.");
		}

		ByteBuffer destination = this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder());
		ByteBuffer sourceCopy = source.duplicate().order(ByteOrder.nativeOrder());
		int destinationBytesPerPixel = getFormat().blockSize();
		int copyBytesPerPixel = Math.min(sourceBytesPerPixel, destinationBytesPerPixel);
		int mipWidth = getWidth(mipLevel);
		int sourceStride = Math.max(sourceRowLength > 0 ? sourceRowLength : width, srcX + width);

		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int sourcePixelOffset = sourceCopy.position() + ((srcY + row) * sourceStride + (srcX + column)) * sourceBytesPerPixel;
				int destinationPixelOffset = (layer * mipWidth * getHeight(mipLevel) + (dstY + row) * mipWidth + (dstX + column)) * destinationBytesPerPixel;

				for (int byteIndex = 0; byteIndex < destinationBytesPerPixel; byteIndex++) {
					byte value = byteIndex < copyBytesPerPixel ? sourceCopy.get(sourcePixelOffset + byteIndex) : 0;
					destination.put(destinationPixelOffset + byteIndex, value);
				}
			}
		}

		syncRegionToNative(mipLevel, layer, dstX, dstY, width, height);
	}

	ByteBuffer readRegion(int mipLevel, int srcX, int srcY, int width, int height) {
		return readRegion(mipLevel, 0, srcX, srcY, width, height);
	}

	ByteBuffer readRegion(int mipLevel, int layer, int srcX, int srcY, int width, int height) {
		if (layer < 0 || layer >= this.layerCount) {
			throw new IllegalArgumentException("Texture layer is outside the allocated layer range.");
		}

		int bytesPerPixel = getFormat().blockSize();
		ByteBuffer source = this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder());
		ByteBuffer copy = ByteBuffer.allocateDirect(width * height * bytesPerPixel).order(ByteOrder.nativeOrder());
		int mipWidth = getWidth(mipLevel);

		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int sourcePixelOffset = (layer * mipWidth * getHeight(mipLevel) + (srcY + row) * mipWidth + (srcX + column)) * bytesPerPixel;
				int copyPixelOffset = (row * width + column) * bytesPerPixel;

				for (int byteIndex = 0; byteIndex < bytesPerPixel; byteIndex++) {
					copy.put(copyPixelOffset + byteIndex, source.get(sourcePixelOffset + byteIndex));
				}
			}
		}

		return copy;
	}

	void copyRegionTo(DxTexture destination, int mipLevel, int dstX, int dstY, int srcX, int srcY, int width, int height) {
		destination.writeRegion(readRegion(mipLevel, srcX, srcY, width, height), getFormat().blockSize(), width, mipLevel, 0, dstX, dstY, width, height, 0, 0);
	}

	void fillRegion(int mipLevel, int dstX, int dstY, int width, int height, int packedValue) {
		ByteBuffer destination = this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder());
		int bytesPerPixel = getFormat().blockSize();
		int mipWidth = getWidth(mipLevel);

		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int destinationPixelOffset = ((dstY + row) * mipWidth + (dstX + column)) * bytesPerPixel;
				if (getFormat() == GpuFormat.RGBA8_UNORM) {
					DxClearColor.writeRgba8(destination, destinationPixelOffset, packedValue);
				} else {
					for (int byteIndex = 0; byteIndex < bytesPerPixel; byteIndex++) {
						destination.put(destinationPixelOffset + byteIndex, (byte) (packedValue >>> (byteIndex * 8)));
					}
				}
			}
		}

		syncRegionToNative(mipLevel, 0, dstX, dstY, width, height);
	}

	long nativeTextureHandle() {
		return this.nativeTextureHandle;
	}

	boolean hasNativeTextureHandle() {
		return this.nativeTextureHandle != 0L;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		if (this.nativeTextureHandle != 0L && this.dxBridge != null) {
			this.dxBridge.releaseTexture(this.nativeTextureHandle);
		}
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	// Logged exactly once each way, not per-texture: with resource creation
	// happening every frame for some texture types, per-call logging would
	// spam the log without adding information beyond "which path is this
	// bridge actually taking overall" -- which is the only thing this is
	// for. See DxSurfaceBackend#blitFromTexture for where that path
	// actually gets used; this only reports whether the *handle* exists.
	private static final AtomicBoolean LOGGED_NATIVE_TEXTURE_SUCCESS = new AtomicBoolean(false);
	private static final AtomicBoolean LOGGED_NATIVE_TEXTURE_FALLBACK = new AtomicBoolean(false);
	/** DxBridge.TEXTURE_FORMAT_* for a format the native path supports, else 0 (CPU-only). */
	static int nativeFormatCode(GpuFormat format) {
		return switch (format) {
			case RGBA8_UNORM -> DxBridge.TEXTURE_FORMAT_RGBA8_UNORM;
			// Float color targets: the order-independent-transparency passes
			// (depth bounds, transmittance coefficients, accumulation) render
			// into RGBA16/32_FLOAT and sample the results back as SRVs.
			case RGBA16_FLOAT -> DxBridge.TEXTURE_FORMAT_RGBA16_FLOAT;
			case RGBA32_FLOAT -> DxBridge.TEXTURE_FORMAT_RGBA32_FLOAT;
			case D32_FLOAT -> DxBridge.TEXTURE_FORMAT_D32_FLOAT;
			case D24_UNORM_S8_UINT -> DxBridge.TEXTURE_FORMAT_D24_UNORM_S8_UINT;
			case D32_FLOAT_S8_UINT -> DxBridge.TEXTURE_FORMAT_D32_FLOAT_S8_UINT;
			case D16_UNORM -> DxBridge.TEXTURE_FORMAT_D16_UNORM;
			default -> 0;
		};
	}

	private long createNativeTextureHandle(GpuFormat format, int width, int height, int depthOrLayers, int mipLevels, boolean cubemap) {
		int formatCode = nativeFormatCode(format);
		if (this.dxBridge == null || formatCode == 0 || this.nativeBindMask == 0
			|| !DxTextureBinding.supportsNativeShape(usage(), format.hasDepthAspect(), width, height, depthOrLayers)
			|| (cubemap && (format != GpuFormat.RGBA8_UNORM || this.nativeBindMask != DxBridge.TEXTURE_BIND_SHADER_RESOURCE))) {
			return 0L;
		}

		try {
			long handle = this.dxBridge.createTexture(
				formatCode, width, height, depthOrLayers, mipLevels, this.nativeBindMask, cubemap, getLabel()
			);
			if (handle != 0L && LOGGED_NATIVE_TEXTURE_SUCCESS.compareAndSet(false, true)) {
				DirectExpMod.LOGGER.info(
					"DirectExp: native GPU texture path active (first native texture created, {}x{}, {} mip levels).",
					width, height, mipLevels
				);
			}
			if (handle != 0L && (this.nativeBindMask & (DxBridge.TEXTURE_BIND_RENDER_TARGET | DxBridge.TEXTURE_BIND_DEPTH_STENCIL)) != 0) {
				DxNativeResourceLog.nativeActive("render/depth texture", format + " " + width + "x" + height);
			}
			return handle;
		} catch (UnsupportedOperationException | IllegalStateException error) {
			if (LOGGED_NATIVE_TEXTURE_FALLBACK.compareAndSet(false, true)) {
				DirectExpMod.LOGGER.warn(
					"DirectExp: native GPU texture creation unavailable, using CPU-side fallback for all textures ({}).",
					error.getMessage()
				);
			}
			return 0L;
		}
	}

	boolean hasNativeRenderTarget() {
		return this.nativeTextureHandle != 0L && (this.nativeBindMask & DxBridge.TEXTURE_BIND_RENDER_TARGET) != 0;
	}

	boolean hasNativeDepthTarget() {
		return this.nativeTextureHandle != 0L && (this.nativeBindMask & DxBridge.TEXTURE_BIND_DEPTH_STENCIL) != 0;
	}

	/**
	 * Uploads just the rectangle that was written on the CPU. The CPU mirror is NOT updated by GPU draws, so for a
	 * texture that is also a render target (the GUI item atlas, for instance) uploading the whole mip would overwrite
	 * everything the GPU had rendered into it with stale mirror contents.
	 */
	private void syncRegionToNative(int mipLevel, int layer, int x, int y, int width, int height) {
		if (this.nativeTextureHandle == 0L || this.dxBridge == null || getFormat() != GpuFormat.RGBA8_UNORM || width <= 0 || height <= 0) {
			syncMipToNative(mipLevel, layer);
			return;
		}
		if (!hasNativeRenderTarget()) {
			syncMipToNative(mipLevel, layer);
			return;
		}

		try {
			this.dxBridge.uploadTextureRegionRgba8(
				this.nativeTextureHandle,
				mipLevel,
				layer,
				this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder()),
				getWidth(mipLevel),
				getHeight(mipLevel),
				x, y, width, height
			);
		} catch (UnsupportedOperationException error) {
			// Bridge without region support: fall back to the whole-mip upload.
			syncMipToNative(mipLevel, layer);
		} catch (IllegalStateException error) {
			if (this.loggedNativeTextureUploadFailure.compareAndSet(false, true)) {
				DirectExpMod.LOGGER.warn("DirectExp: native texture region upload failed (texture {}, mip {}, layer {}): {}", this.nativeTextureHandle, mipLevel, layer, error.getMessage());
			}
		}
	}

	private void syncMipToNative(int mipLevel, int layer) {
		// Only RGBA8 has a CPU -> GPU upload path; depth textures are written by
		// the GPU (clears/draws) and their CPU mirror is not synced.
		if (this.nativeTextureHandle == 0L || this.dxBridge == null || getFormat() != GpuFormat.RGBA8_UNORM) {
			return;
		}

		try {
			this.dxBridge.uploadTextureRgba8(
				this.nativeTextureHandle,
				mipLevel,
				layer,
				this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder()),
				getWidth(mipLevel),
				getHeight(mipLevel)
			);
		} catch (UnsupportedOperationException | IllegalStateException error) {
			if (this.loggedNativeTextureUploadFailure.compareAndSet(false, true)) {
				DirectExpMod.LOGGER.warn(
					"DirectExp: native texture upload failed (texture {}, mip {}, layer {}, {}x{}): {}; GPU-sampled textures may contain stale or empty texels.",
					this.nativeTextureHandle, mipLevel, layer, getWidth(mipLevel), getHeight(mipLevel), error.getMessage()
				);
			}
		}
	}
}
