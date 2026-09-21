package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import dev.cubcraftv.directexp.bridge.DxBridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * CPU-side placeholder texture storage, with a best-effort attempt to also
 * create a native D3D11 texture handle through {@link DxBridge}. Since the
 * bridge's texture methods are still default-throw stubs at this milestone
 * (see DxBridge), {@link #hasNativeTextureHandle()} is always false today --
 * every texture lives entirely in the direct ByteBuffer storage below, the
 * same bootstrap-stage shape MetalExp's own MetalTexture started from.
 */
final class DxTexture extends GpuTexture {
	private final DxBridge dxBridge;
	private final ByteBuffer[] mipStorage;
	private final long nativeTextureHandle;
	private boolean closed;

	DxTexture(DxBridge dxBridge, int usage, String label, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		super(usage, label, format, width, height, depthOrLayers, mipLevels);
		this.dxBridge = dxBridge;
		this.mipStorage = new ByteBuffer[mipLevels];
		this.nativeTextureHandle = createNativeTextureHandle(format, width, height, depthOrLayers, mipLevels);

		for (int mipLevel = 0; mipLevel < mipLevels; mipLevel++) {
			int mipWidth = getWidth(mipLevel);
			int mipHeight = getHeight(mipLevel);
			int mipBytes = Math.max(1, mipWidth * mipHeight * depthOrLayers * format.pixelSize());
			this.mipStorage[mipLevel] = ByteBuffer.allocateDirect(mipBytes).order(ByteOrder.nativeOrder());
		}
	}

	void writeRegion(
		ByteBuffer source,
		int sourceBytesPerPixel,
		int sourceRowLength,
		int mipLevel,
		int dstX,
		int dstY,
		int width,
		int height,
		int srcX,
		int srcY
	) {
		ByteBuffer destination = this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder());
		ByteBuffer sourceCopy = source.duplicate().order(ByteOrder.nativeOrder());
		int destinationBytesPerPixel = getFormat().pixelSize();
		int copyBytesPerPixel = Math.min(sourceBytesPerPixel, destinationBytesPerPixel);
		int mipWidth = getWidth(mipLevel);
		int sourceStride = Math.max(sourceRowLength > 0 ? sourceRowLength : width, srcX + width);

		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int sourcePixelOffset = ((srcY + row) * sourceStride + (srcX + column)) * sourceBytesPerPixel;
				int destinationPixelOffset = ((dstY + row) * mipWidth + (dstX + column)) * destinationBytesPerPixel;

				for (int byteIndex = 0; byteIndex < destinationBytesPerPixel; byteIndex++) {
					byte value = byteIndex < copyBytesPerPixel ? sourceCopy.get(sourcePixelOffset + byteIndex) : 0;
					destination.put(destinationPixelOffset + byteIndex, value);
				}
			}
		}

		syncMipToNative(mipLevel);
	}

	ByteBuffer readRegion(int mipLevel, int srcX, int srcY, int width, int height) {
		int bytesPerPixel = getFormat().pixelSize();
		ByteBuffer source = this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder());
		ByteBuffer copy = ByteBuffer.allocateDirect(width * height * bytesPerPixel).order(ByteOrder.nativeOrder());
		int mipWidth = getWidth(mipLevel);

		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int sourcePixelOffset = ((srcY + row) * mipWidth + (srcX + column)) * bytesPerPixel;
				int copyPixelOffset = (row * width + column) * bytesPerPixel;

				for (int byteIndex = 0; byteIndex < bytesPerPixel; byteIndex++) {
					copy.put(copyPixelOffset + byteIndex, source.get(sourcePixelOffset + byteIndex));
				}
			}
		}

		return copy;
	}

	void copyRegionTo(DxTexture destination, int mipLevel, int dstX, int dstY, int srcX, int srcY, int width, int height) {
		destination.writeRegion(readRegion(mipLevel, srcX, srcY, width, height), getFormat().pixelSize(), width, mipLevel, dstX, dstY, width, height, 0, 0);
	}

	void fillRegion(int mipLevel, int dstX, int dstY, int width, int height, int packedValue) {
		ByteBuffer destination = this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder());
		int bytesPerPixel = getFormat().pixelSize();
		int mipWidth = getWidth(mipLevel);

		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int destinationPixelOffset = ((dstY + row) * mipWidth + (dstX + column)) * bytesPerPixel;
				for (int byteIndex = 0; byteIndex < bytesPerPixel; byteIndex++) {
					destination.put(destinationPixelOffset + byteIndex, (byte) (packedValue >>> (byteIndex * 8)));
				}
			}
		}

		syncMipToNative(mipLevel);
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

	private long createNativeTextureHandle(GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		if (this.dxBridge == null || format != GpuFormat.RGBA8_UNORM || depthOrLayers != 1) {
			return 0L;
		}

		try {
			return this.dxBridge.createTexture2D(width, height, mipLevels);
		} catch (UnsupportedOperationException | IllegalStateException error) {
			return 0L;
		}
	}

	private void syncMipToNative(int mipLevel) {
		if (this.nativeTextureHandle == 0L || this.dxBridge == null) {
			return;
		}

		try {
			this.dxBridge.uploadTextureRgba8(
				this.nativeTextureHandle,
				mipLevel,
				this.mipStorage[mipLevel].duplicate().order(ByteOrder.nativeOrder()),
				getWidth(mipLevel),
				getHeight(mipLevel)
			);
		} catch (UnsupportedOperationException | IllegalStateException ignored) {
			// Native texture sync is optional until the bridge advertises the full path.
		}
	}
}
