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
import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.client.shader.DxSpirvCache;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
	private final Map<RenderPipeline, DxRenderPipeline> pipelineCache = new IdentityHashMap<>();
	private final Map<RenderPipeline, DxRenderPipeline> deferredSourcePipelines = new IdentityHashMap<>();
	private final Set<RenderPipeline> loggedMissingShaderSources = Collections.newSetFromMap(new IdentityHashMap<>());
	private final DxSpirvCache spirvCache = new DxSpirvCache();
	private final AtomicBoolean loggedFirstValidPipeline = new AtomicBoolean(false);
	private final DxDrawDiagnostics drawDiagnostics = (key, message) -> DxNativeResourceLog.drawProblem(key, message);
	private final DxDrawScratch drawScratch;
	private final ShaderSource defaultShaderSource;
	private boolean closed;

	DxDeviceBackend(DxBridge dxBridge, DxSurfaceDescriptor surfaceDescriptor) {
		this(new DxSurfaceLease(dxBridge, surfaceDescriptor), (ShaderSource) null);
	}

	DxDeviceBackend(DxSurfaceLease surfaceLease) {
		this(surfaceLease, (ShaderSource) null);
	}

	/**
	 * @param defaultShaderSource what Blaze3D handed {@code GpuBackend.createDevice}; used to compile a pipeline
	 *                            on first use when it never went through {@link #precompilePipeline}
	 *                            (the Vulkan backend's {@code getOrCompilePipeline} does the same). May be null in tests.
	 */
	DxDeviceBackend(DxSurfaceLease surfaceLease, ShaderSource defaultShaderSource) {
		this.surfaceLease = Objects.requireNonNull(surfaceLease, "surfaceLease");
		this.defaultShaderSource = defaultShaderSource;
		this.drawScratch = new DxDrawScratch(surfaceLease.dxBridge(), this.drawDiagnostics);
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
		return new DxCommandEncoderBackend(
			this.surfaceLease,
			new DxDrawContext(this.surfaceLease.dxBridge(), this.drawScratch, this::getOrCompilePipeline, this.drawDiagnostics)
		);
	}

	/** The pipeline for {@code renderPipeline}, compiled now with the default shader source if it was never precompiled; null only if there is no default source. */
	private DxRenderPipeline getOrCompilePipeline(RenderPipeline renderPipeline) {
		synchronized (this.pipelineCache) {
			DxRenderPipeline cached = this.pipelineCache.get(renderPipeline);
			if (cached != null) {
				return cached;
			}
			DxRenderPipeline deferred = this.deferredSourcePipelines.get(renderPipeline);
			if (deferred != null || this.defaultShaderSource == null) {
				return deferred;
			}
			return compileAndCache(renderPipeline, this.defaultShaderSource, true);
		}
	}

	@Override
	public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
		return new DxSampler(this.surfaceLease.dxBridge(), addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
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
		return new DxTextureView(this.surfaceLease.dxBridge(), gpuTexture, 0, gpuTexture.getMipLevels());
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture gpuTexture, int baseMipLevel, int mipLevels) {
		return new DxTextureView(this.surfaceLease.dxBridge(), gpuTexture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
		return new DxBuffer(this.surfaceLease.dxBridge(), usage, size);
	}

	@Override
	public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
		DxBuffer buffer = new DxBuffer(this.surfaceLease.dxBridge(), usage, data.remaining());
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
	public CompiledRenderPipeline precompilePipeline(RenderPipeline renderPipeline, ShaderSource customShaderSource) {
		ShaderSource shaderSource = customShaderSource == null ? this.defaultShaderSource : customShaderSource;
		// Keyed on the RenderPipeline instance itself, like the Vulkan backend:
		// an instance already encodes the full render state. Invalid results are
		// cached too, so a broken pipeline is reported once rather than every frame.
		synchronized (this.pipelineCache) {
			DxRenderPipeline cached = this.pipelineCache.get(renderPipeline);
			if (cached != null) {
				return cached;
			}
			DxRenderPipeline deferred = this.deferredSourcePipelines.get(renderPipeline);
			if (deferred != null) {
				return deferred;
			}

			return compileAndCache(renderPipeline, shaderSource, false);
		}
	}

	/** Caller holds the {@code pipelineCache} lock. */
	private DxRenderPipeline compileAndCache(RenderPipeline renderPipeline, ShaderSource shaderSource, boolean deferMissingSource) {
		DxRenderPipeline compiled = DxPipelineCompiler.compile(renderPipeline, shaderSource, this.surfaceLease.dxBridge(), this.spirvCache);
		if (compiled.isValid()) {
			this.pipelineCache.put(renderPipeline, compiled);
			this.deferredSourcePipelines.remove(renderPipeline);
			this.loggedMissingShaderSources.remove(renderPipeline);
			if (this.loggedFirstValidPipeline.compareAndSet(false, true)) {
				DirectExpMod.LOGGER.info("DirectExp: first native render pipeline assembled ({}).", renderPipeline.getLocation());
			}
		} else if (deferMissingSource && compiled.failureReason().startsWith("shader source: ")) {
			this.deferredSourcePipelines.put(renderPipeline, compiled);
			if (this.loggedMissingShaderSources.add(renderPipeline)) {
				DirectExpMod.LOGGER.warn(
					"DirectExp: shader source is not available yet for pipeline {}; it will retry after resource reload.",
					renderPipeline.getLocation()
				);
			}
		} else {
			this.pipelineCache.put(renderPipeline, compiled);
			DirectExpMod.LOGGER.warn("DirectExp: pipeline {} is invalid: {}", renderPipeline.getLocation(), compiled.failureReason());
		}
		return compiled;
	}

	@Override
	public void clearPipelineCache() {
		synchronized (this.pipelineCache) {
			this.pipelineCache.values().forEach(DxRenderPipeline::close);
			this.pipelineCache.clear();
			this.deferredSourcePipelines.clear();
		}
		this.spirvCache.clear();
	}

	@Override
	public void close() {
		clearPipelineCache();
		this.drawScratch.close();
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
