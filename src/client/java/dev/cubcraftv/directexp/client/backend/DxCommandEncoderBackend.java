package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuFence;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.buffers.TransientMemory;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.cubcraftv.directexp.bridge.DxBridge;
import org.joml.Vector4fc;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

final class DxCommandEncoderBackend implements CommandEncoderBackend {
	private final Supplier<DxSurfaceLease> surfaceLeaseSupplier;
	private final DxDrawContext drawContext;
	private final DxTransientMemory transientMemory;

	DxCommandEncoderBackend(
		DxBridge dxBridge,
		Supplier<DxSurfaceLease> surfaceLeaseSupplier,
		DxDrawContext drawContext
	) {
		this.surfaceLeaseSupplier = surfaceLeaseSupplier;
		this.drawContext = drawContext;
		this.transientMemory = new DxTransientMemory(dxBridge);
	}

	@Override
	public void submit() {
		this.surfaceLease().submitPendingCommands();
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
		if (!clearIntoNative(gpuTexture, 0, clearColor, 0, 0, gpuTexture.getWidth(0), gpuTexture.getHeight(0))) {
			((DxTexture) gpuTexture).fillRegion(0, 0, 0, gpuTexture.getWidth(0), gpuTexture.getHeight(0), DxClearColor.packArgb(clearColor));
		}
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture gpuTexture, Vector4fc clearColor, GpuTexture depthTexture, double depth) {
		clearColorTexture(gpuTexture, clearColor);
		clearDepthTexture(depthTexture, depth);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture gpuTexture, Vector4fc clearColor, GpuTexture depthTexture, double depth, int x, int y, int width, int height, int mipLevel) {
		if (!clearIntoNative(gpuTexture, mipLevel, clearColor, x, y, width, height)) {
			((DxTexture) gpuTexture).fillRegion(mipLevel, x, y, width, height, DxClearColor.packArgb(clearColor));
		}
		if (x == 0 && y == 0 && width >= depthTexture.getWidth(0) && height >= depthTexture.getHeight(0)) {
			clearDepthTexture(depthTexture, depth);
		} else if (depthTexture instanceof DxTexture dxDepth && dxDepth.hasNativeDepthTarget()) {
			// ClearDepthStencilView has no rectangle and ClearView needs D3D11.1 (above the
			// 10.1 floor), so the native side draws a scissored depth-only triangle instead.
			try {
				this.drawContext.dxBridge().clearDepthRegion(dxDepth.nativeTextureHandle(), x, y, width, height, (float) depth);
			} catch (UnsupportedOperationException | IllegalStateException error) {
				this.drawContext.diagnostics().reportOnce("partial-depth-clear", "a partial-rectangle depth clear failed: " + error.getMessage());
			}
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

	/**
	 * GPU-side whole-color clear for native textures without a CPU upload path
	 * (RGBA16/32_FLOAT -- the OIT pass targets, which the frame graph clears
	 * through the texture handle every frame): {@link DxTexture#fillRegion}
	 * writes the CPU mirror but syncs it to the D3D11 texture only for RGBA8,
	 * so a CPU-side clear would never reach the GPU there and OIT content
	 * would accumulate across frames. Returns false when the CPU path applies.
	 */
	private boolean clearIntoNative(GpuTexture gpuTexture, int mipLevel, Vector4fc clearColor, int x, int y, int width, int height) {
		if (!(gpuTexture instanceof DxTexture dxTexture)
			|| !dxTexture.hasNativeTextureHandle()
			|| mipLevel != 0
			|| dxTexture.getFormat() == GpuFormat.RGBA8_UNORM) {
			return false;
		}
		if (x != 0 || y != 0 || width < gpuTexture.getWidth(0) || height < gpuTexture.getHeight(0)) {
			this.drawContext.diagnostics().reportOnce(
				"partial-color-clear",
				"a partial-rectangle clear of a non-RGBA8 texture was skipped (only whole-texture GPU clears are supported): " + dxTexture.getLabel()
			);
			return true;
		}

		try {
			this.drawContext.dxBridge().clearColorTexture(
				dxTexture.nativeTextureHandle(), clearColor.x(), clearColor.y(), clearColor.z(), clearColor.w()
			);		} catch (UnsupportedOperationException | IllegalStateException error) {
			this.drawContext.diagnostics().reportOnce("clear-color-texture", "could not clear a color texture natively: " + error.getMessage());
		}
		return true;
	}

	@Override
	public void writeToBuffer(GpuBufferSlice gpuBufferSlice, ByteBuffer byteBuffer) {
		((DxBuffer) gpuBufferSlice.buffer()).write(gpuBufferSlice.offset(), byteBuffer);
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
		long profileStart = DxPerformanceProfiler.start();
		try {
			// GPU-side CopySubresourceRegion when both buffers are native (the case for
			// arena moves and uploads); falls back to a CPU copy otherwise.
			DxBuffer.copy((DxBuffer) source.buffer(), source.offset(), (DxBuffer) destination.buffer(), destination.offset(), source.length());
		} finally {
			DxPerformanceProfiler.record(DxPerformanceProfiler.Category.BUFFER_COPY, profileStart, source.length());
		}
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
		return this.surfaceLease().acquirePendingCommandContext();
	}

	private DxSurfaceLease surfaceLease() {
		return this.surfaceLeaseSupplier.get();
	}
}
