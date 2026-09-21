package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cubcraftv.directexp.bridge.DxBridge;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Same bootstrap-stage shape MetalExp's own MetalDeviceBackend started at:
 * a real GpuDeviceBackend implementation backed by CPU-side resource
 * storage, so the rest of Blaze3D's device-facing code has something valid
 * to talk to while the native D3D11 resource path is still being built out
 * (see docs/plans's Milestone 3 scope).
 */
final class DxDeviceBackend implements GpuDeviceBackend {
	private static final DeviceLimits DEFAULT_LIMITS = new DeviceLimits(16, 256, 16384);

	private final DxSurfaceLease surfaceLease;
	private final DeviceInfo deviceInfo;
	private boolean closed;

	DxDeviceBackend(DxBridge dxBridge, DxSurfaceDescriptor surfaceDescriptor) {
		this(new DxSurfaceLease(dxBridge, surfaceDescriptor));
	}

	DxDeviceBackend(DxSurfaceLease surfaceLease) {
		this.surfaceLease = Objects.requireNonNull(surfaceLease, "surfaceLease");
		this.deviceInfo = new DeviceInfo(
			"DirectX 11 Bootstrap Device",
			"cubcraftv",
			"directexp-bootstrap",
			true,
			"Direct3D 11 (feature level 10.1 floor)",
			1.0F,
			DEFAULT_LIMITS,
			Set.of()
		);
	}

	DxSurfaceDescriptor surfaceDescriptor() {
		return this.surfaceLease.descriptor();
	}

	DxSurfaceLease surfaceLease() {
		return this.surfaceLease;
	}

	@Override
	public GpuSurfaceBackend createSurface(long window) {
		if (this.closed) {
			throw new IllegalStateException("DirectX device backend is closed.");
		}

		return new DxSurfaceBackend(this.surfaceLease);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return new DxCommandEncoderBackend(this.surfaceLease);
	}

	@Override
	public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
		return new DxSampler(addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(Supplier<String> label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return new DxTexture(this.surfaceLease.dxBridge(), usage, label.get(), format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTexture createTexture(String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return new DxTexture(this.surfaceLease.dxBridge(), usage, label, format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture gpuTexture) {
		return new DxTextureView(gpuTexture, 0, gpuTexture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture gpuTexture, int baseMipLevel, int mipLevels) {
		return new DxTextureView(gpuTexture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
		return new DxBuffer(usage, size);
	}

	@Override
	public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
		DxBuffer buffer = new DxBuffer(usage, data.remaining());
		buffer.write(0L, data.duplicate());
		return buffer;
	}

	@Override
	public List<String> getLastDebugMessages() {
		return List.of("DxDeviceBackend bootstrap device path active.");
	}

	@Override
	public boolean isDebuggingEnabled() {
		return false;
	}

	@Override
	public CompiledRenderPipeline precompilePipeline(RenderPipeline renderPipeline, ShaderSource shaderSource) {
		return () -> true;
	}

	@Override
	public void clearPipelineCache() {
	}

	@Override
	public void close() {
		this.surfaceLease.close();
		this.closed = true;
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new DxQueryPool(size);
	}

	@Override
	public long getTimestampNow() {
		return 0L;
	}

	@Override
	public DeviceInfo getDeviceInfo() {
		return this.deviceInfo;
	}

	boolean isClosed() {
		return this.closed;
	}
}
