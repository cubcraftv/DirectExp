package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;

final class DxCommandEncoderBackend implements CommandEncoderBackend {
	private final DxSurfaceLease surfaceLease;
	private final DxDrawContext drawContext;
	private final DxTransientMemory transientMemory;

	DxCommandEncoderBackend(DxSurfaceLease surfaceLease, DxDrawContext drawContext) {
		this.surfaceLease = surfaceLease;
		this.drawContext = drawContext;
		this.transientMemory = new DxTransientMemory(surfaceLease.dxBridge());
	}

	@Override
	public void submit() {
		this.surfaceLease.submitPendingCommands();
		this.transientMemory.reset();
	}

	@Override
	public TransientMemory transientMemory() {
		return this.transientMemory;
	}

	@Override
	public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
		return new DxRenderPassBackend(this.drawContext, descriptor);
	}

	@Override
	public void submitRenderPass() {
	}

	@Override
	public void clearColorTexture(GpuTexture gpuTexture, Vector4fc clearColor) {
		((DxTexture) gpuTexture).fillRegion(0, 0, 0, gpuTexture.getWidth(0), gpuTexture.getHeight(0), DxClearColor.packArgb(clearColor));
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture gpuTexture, Vector4fc clearColor, GpuTexture depthTexture, double depth) {
		clearColorTexture(gpuTexture, clearColor);
		clearDepthTexture(depthTexture, depth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture gpuTexture, Vector4fc clearColor, GpuTexture depthTexture, double depth, int x, int y, int width, int height) {
		((DxTexture) gpuTexture).fillRegion(0, x, y, width, height, DxClearColor.packArgb(clearColor));
		if (x == 0 && y == 0 && width >= depthTexture.getWidth(0) && height >= depthTexture.getHeight(0)) {
			clearDepthTexture(depthTexture, depth);
		} else {
			// ClearDepthStencilView has no rectangle (ID3D11DeviceContext1::ClearView needs
			// D3D11.1, above the 10.1 floor), so a partial depth clear is not expressible yet.
			this.drawContext.diagnostics().reportOnce("partial-depth-clear", "a partial-rectangle depth clear was requested; it is skipped (no D3D11.0 equivalent)");
		}
	}

	@Override
	public void clearDepthTexture(GpuTexture gpuTexture, double depth) {
		if (!(gpuTexture instanceof DxTexture dxTexture) || !dxTexture.hasNativeDepthTarget()) {
			return;
		}

		try {
			this.drawContext.dxBridge().clearDepthTexture(dxTexture.nativeTextureHandle(), (float) depth);
		} catch (UnsupportedOperationException | IllegalStateException error) {
			this.drawContext.diagnostics().reportOnce("clear-depth-texture", "could not clear a depth texture: " + error.getMessage());
		}
	}

	@Override
	public void writeToBuffer(GpuBufferSlice gpuBufferSlice, ByteBuffer byteBuffer) {
		((DxBuffer) gpuBufferSlice.buffer()).write(gpuBufferSlice.offset(), byteBuffer);
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
		ByteBuffer sourceBytes = ((DxBuffer) source.buffer()).sliceStorage(source.offset(), source.length());
		((DxBuffer) destination.buffer()).write(destination.offset(), sourceBytes);
	}

	@Override
	public void writeToTexture(GpuTexture gpuTexture, ByteBuffer byteBuffer, int mipLevel, int layer, int dstX, int dstY, int width, int height) {
		int bytesPerPixel = gpuTexture.getFormat().blockSize();
		((DxTexture) gpuTexture).writeRegion(byteBuffer, bytesPerPixel, width, mipLevel, layer, dstX, dstY, width, height, 0, 0);
	}

	@Override
	public void copyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth, int sourceHeight, GpuTexture destination, int destinationX, int destinationY, int copyWidth, int copyHeight, int mipLevel, int arrayLayer) {
		ByteBuffer sourceBytes = ((DxBuffer) source.buffer()).sliceStorage(source.offset(), source.length());
		int bytesPerPixel = destination.getFormat().blockSize();
		((DxTexture) destination).writeRegion(
			sourceBytes, bytesPerPixel, sourceWidth, mipLevel, arrayLayer,
			destinationX, destinationY, copyWidth, copyHeight, sourceX, sourceY
		);
	}

	@Override
	public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long offset, Runnable onCopyComplete, int mipLevel) {
		copyTextureToBuffer(gpuTexture, gpuBuffer, offset, onCopyComplete, mipLevel, 0, 0, gpuTexture.getWidth(mipLevel), gpuTexture.getHeight(mipLevel));
	}

	@Override
	public void copyTextureToBuffer(GpuTexture gpuTexture, GpuBuffer gpuBuffer, long offset, Runnable onCopyComplete, int mipLevel, int x, int y, int width, int height) {
		ByteBuffer source = ((DxTexture) gpuTexture).readRegion(mipLevel, x, y, width, height);
		((DxBuffer) gpuBuffer).write(offset, source);
		onCopyComplete.run();
	}

	@Override
	public void copyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int dstX, int dstY, int srcX, int srcY, int width, int height) {
		((DxTexture) source).copyRegionTo((DxTexture) destination, mipLevel, dstX, dstY, srcX, srcY, width, height);
	}

	@Override
	public GpuFence createFence() {
		return new DxFence();
	}

	@Override
	public void writeTimestamp(GpuQueryPool gpuQueryPool, int index) {
	}

	long commandContextHandle() {
		return this.surfaceLease.acquirePendingCommandContext();
	}
}
