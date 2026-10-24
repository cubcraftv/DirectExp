package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.DeviceLimits;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.device.DeviceType;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.backend.api.GpuSurfaceBackend;
import com.mojang.renderpearl.api.device.HintsAndWorkarounds;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.client.shader.DxSpirvPipelineModules;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Same bootstrap-stage shape MetalExp's own MetalDeviceBackend started at:
 * a real GpuDeviceBackend implementation backed by CPU-side resource
 * storage, so the rest of Blaze3D's device-facing code has something valid
 * to talk to while the native D3D11 resource path is still being built out
 * (see docs/plans's Milestone 3 scope).
 */
final class DxDeviceBackend implements GpuDeviceBackend {
	/**
	 * Blaze3D's {@code RenderPass.multiDrawIndexed(IntBuffer, ...)} rejects a
	 * {@code drawCount} above this. Sodium's per-region batch holds at most
	 * {@code ModelQuadFacing.COUNT (7) * RenderRegion.REGION_SIZE (256) + 1 = 1793}
	 * draws; the loop in {@link DxRenderPassBackend} has no limit of its own, so
	 * this is a generous ceiling rather than a hardware figure.
	 */
	static final int MAX_MULTI_DRAW_DIRECT_INTERLEAVED_DRAW_COUNT = 65536;

	static final DeviceLimits DEFAULT_LIMITS = new DeviceLimits(16, 256, 16384, Long.MAX_VALUE, MAX_MULTI_DRAW_DIRECT_INTERLEAVED_DRAW_COUNT, 1, 0);

	/**
	 * Only {@code multiDrawDirectInterleaved} is on: {@link DxRenderPassBackend}
	 * implements {@code multiDrawIndexed(IntBuffer, ...)} with one JNI call for a
	 * batch, and only Blaze3D's {@code RenderPass} validation and Sodium read the
	 * flag. Everything else stays off on purpose: there is no
	 * {@code gl_DrawID}/base-instance support ({@code shaderDrawParameters},
	 * {@code nonZeroFirstInstance}), no multi-draw variants beyond the interleaved
	 * one, no indirect draws, and {@code persistentMapping} must stay false:
	 * D3D11 cannot keep a mapped resource persistently accessible with the
	 * semantics Sodium expects, and its fence implementation is still a stub.
	 * Sodium therefore stays on its {@code writeToBuffer} staging path rather
	 * than one that casts to OpenGL classes.
	 */
	static final DeviceFeatures FEATURES = new DeviceFeatures(true, false, true, false, false, false, false, false);

	private final DxBridge dxBridge;
	private final Win32HostSurfaceResolver win32HostSurfaceResolver;
	private DxSurfaceLease surfaceLease;
	private final DeviceInfo deviceInfo;
	private final AtomicBoolean loggedFirstValidPipeline = new AtomicBoolean(false);
	private final DxDrawDiagnostics drawDiagnostics = (key, message) -> DxNativeResourceLog.drawProblem(key, message);
	private final DxDrawScratch drawScratch;
	private boolean closed;

	DxDeviceBackend(DxBridge dxBridge, Win32HostSurfaceResolver win32HostSurfaceResolver) {
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.win32HostSurfaceResolver = Objects.requireNonNull(win32HostSurfaceResolver, "win32HostSurfaceResolver");
		this.drawScratch = new DxDrawScratch(dxBridge, this.drawDiagnostics);
		this.deviceInfo = new DeviceInfo(
			"DirectX 11 Bootstrap Device",
			"cubcraftv",
			"directexp-bootstrap",
			true,
			"Direct3D 11 (feature level 10.1 floor)",
			1.0F,
			DEFAULT_LIMITS,
			FEATURES,
			Set.of(),
			new HintsAndWorkarounds(false, false, false, false),
			DeviceType.OTHER
		);
	}

	@Override
	public GpuSurfaceBackend createSurface(long windowHandle, BooleanSupplier isIconified) {
		if (this.closed) {
			throw new IllegalStateException("DirectX device backend is closed.");
		}

		if (this.surfaceLease != null) {
			throw new IllegalStateException("DirectX device backend already has a surface.");
		}

		try {
			try (DxBackendBootstrapContext bootstrapContext = DxBackendBootstrapContext.bootstrap(
				this.dxBridge,
				this.win32HostSurfaceResolver,
				windowHandle
			)) {
				this.surfaceLease = bootstrapContext.detachSurfaceLease();
			}
		} catch (BackendCreationException error) {
			throw new IllegalStateException("Failed to initialize the DirectX surface: " + error.getMessage(), error);
		}

		return new DxSurfaceBackend(this.surfaceLease);
	}

	@Override
	public CommandEncoderBackend createCommandEncoder() {
		return new DxCommandEncoderBackend(
			this.dxBridge,
			() -> {
				if (this.surfaceLease == null) {
					throw new IllegalStateException("DirectX surface has not been created yet.");
				}
				return this.surfaceLease;
			},
			new DxDrawContext(this.dxBridge, this.drawScratch, this.drawDiagnostics)
		);
	}

	@Override
	public GpuSampler createSampler(AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
		return new DxSampler(this.dxBridge, addressModeU, addressModeV, minFilter, magFilter, maxAnisotropy, maxLod);
	}

	@Override
	public GpuTexture createTexture(@Nullable String label, int usage, GpuFormat format, int width, int height, int depthOrLayers, int mipLevels) {
		return new DxTexture(this.dxBridge, usage, label == null ? "" : label, format, width, height, depthOrLayers, mipLevels);
	}

	@Override
	public GpuTextureView createTextureView(GpuTexture gpuTexture, int baseMipLevel, int mipLevels) {
		return new DxTextureView(this.dxBridge, gpuTexture, baseMipLevel, mipLevels);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, long size) {
		return new DxBuffer(this.dxBridge, usage, size);
	}

	@Override
	public GpuBuffer createBuffer(@Nullable Supplier<String> label, int usage, ByteBuffer data) {
		DxBuffer buffer = new DxBuffer(this.dxBridge, usage, data.remaining());
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

	/**
	 * Called on the frontend's compile executor. Only the SPIR-V is copied out
	 * here; the native work (reflection, HLSL, D3D11 objects) runs in
	 * {@code finishCompile}, which the frontend calls on the render thread, so
	 * the native toolchain and device are never touched from two threads.
	 */
	@Override
	public BackendRenderPipeline.Pending compilePipeline(BackendRenderPipeline.CreateInfo pipelineCreateInfo) {
		DxSpirvPipelineModules spirv;
		try {
			spirv = DxPipelineCompiler.copySpirv(pipelineCreateInfo);
		} catch (RuntimeException error) {
			DirectExpMod.LOGGER.warn("DirectExp: pipeline {} is invalid: {}", pipelineCreateInfo.name(), error.getMessage());
			return BackendRenderPipeline.Pending.NULL;
		}
		return () -> {
			if (this.closed) {
				return null;
			}
			DxRenderPipeline compiled = DxPipelineCompiler.compile(pipelineCreateInfo, spirv, this.dxBridge);
			if (compiled != null && compiled.isSupported() && this.loggedFirstValidPipeline.compareAndSet(false, true)) {
				DirectExpMod.LOGGER.info("DirectExp: first native render pipeline assembled ({}).", pipelineCreateInfo.name());
			}
			return compiled;
		};
	}

	@Override
	public void close() {
		this.drawScratch.close();
		this.closed = true;
		if (this.surfaceLease != null) {
			this.surfaceLease.close();
		}
	}

	@Override
	public GpuQueryPool createTimestampQueryPool(int size) {
		return new DxQueryPool(size);
	}

	@Override
	public long getTimestampCalibrationOffset() {
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
