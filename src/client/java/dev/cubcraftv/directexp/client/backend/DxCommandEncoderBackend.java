package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.function.Supplier;

final class DxCommandEncoderBackend implements CommandEncoderBackend {
	private final DxSurfaceLease surfaceLease;

	DxCommandEncoderBackend(DxSurfaceLease surfaceLease) {
		this.surfaceLease = surfaceLease;
	}

	@Override
	public void submit() {
		this.surfaceLease.submitPendingCommands();
	}

	@Override
	public RenderPassBackend createRenderPass(Supplier<String> label, GpuTextureView colorTextureView, OptionalInt clearColor) {
		return new DxRenderPassBackend(this, colorTextureView);
	}

	@Override
	public RenderPassBackend createRenderPass(Supplier<String> label, GpuTextureView colorTextureView, OptionalInt clearColor, GpuTextureView depthTextureView, OptionalDouble clearDepth) {
		return new DxRenderPassBackend(this, colorTextureView);
	}

	@Override
	public void submitRenderPass() {
	}

	@Override
	public void clearColorTexture(GpuTexture gpuTexture, int packedColor) {
		((DxTexture) gpuTexture).fillRegion(0, 0, 0, gpuTexture.getWidth(0), gpuTexture.getHeight(0), packedColor);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture gpuTexture, int packedColor, GpuTexture depthTexture, double depth) {
		clearColorTexture(gpuTexture, packedColor);
	}

	@Override
	public void clearColorAndDepthTextures(GpuTexture gpuTexture, int packedColor, GpuTexture depthTexture, double depth, int x, int y, int width, int height) {
		((DxTexture) gpuTexture).fillRegion(0, x, y, width, height, packedColor);
	}

	@Override
	public void clearDepthTexture(GpuTexture gpuTexture, double depth) {
	}

	@Override
	public void writeToBuffer(GpuBufferSlice gpuBufferSlice, ByteBuffer byteBuffer) {
		((DxBuffer) gpuBufferSlice.buffer()).write(gpuBufferSlice.offset(), byteBuffer);
	}

	@Override
	public GpuBuffer.MappedView mapBuffer(GpuBufferSlice gpuBufferSlice, boolean read, boolean write) {
		return new DxMappedBufferView(((DxBuffer) gpuBufferSlice.buffer()).sliceStorage(gpuBufferSlice.offset(), gpuBufferSlice.length()));
	}

	@Override
	public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
		ByteBuffer sourceBytes = ((DxBuffer) source.buffer()).sliceStorage(source.offset(), source.length());
		((DxBuffer) destination.buffer()).write(destination.offset(), sourceBytes);
	}

	@Override
	public void writeToTexture(GpuTexture gpuTexture, NativeImage nativeImage, int mipLevel, int layer, int dstX, int dstY, int width, int height, int srcX, int srcY) {
		ByteBuffer source = MemoryUtil.memByteBuffer(
			nativeImage.getPointer(),
			nativeImage.getWidth() * nativeImage.getHeight() * nativeImage.format().components()
		);
		((DxTexture) gpuTexture).writeRegion(source, nativeImage.format().components(), nativeImage.getWidth(), mipLevel, dstX, dstY, width, height, srcX, srcY);
	}

	@Override
	public void writeToTexture(GpuTexture gpuTexture, ByteBuffer byteBuffer, NativeImage.Format format, int mipLevel, int layer, int dstX, int dstY, int width, int height) {
		((DxTexture) gpuTexture).writeRegion(byteBuffer, format.components(), width, mipLevel, dstX, dstY, width, height, 0, 0);
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
