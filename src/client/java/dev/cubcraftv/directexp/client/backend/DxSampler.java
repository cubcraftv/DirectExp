package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.cubcraftv.directexp.bridge.DxBridge;

import java.util.OptionalDouble;

/**
 * Blaze3D sampler parameters plus (Milestone 4.6 prerequisite scope) a
 * best-effort native {@code ID3D11SamplerState}. The parameter mapping mirrors
 * the decompiled Vulkan backend's {@code VulkanGpuSampler} so both backends
 * sample identically:
 * <ul>
 *   <li>mip filtering is linear iff {@code maxLod.orElse(1000) > 0.25}
 *       (Vulkan's {@code mipmapMode} rule); the native MaxLOD is
 *       {@code max(0.25, maxLod.orElse(1000))};</li>
 *   <li>MipLODBias is 0;</li>
 *   <li>REPEAT -> WRAP, CLAMP_TO_EDGE -> CLAMP.</li>
 * </ul>
 * One deliberate deviation: D3D11 has no "anisotropy on top of independent
 * min/mag filters" -- D3D11_FILTER_ANISOTROPIC is linear everywhere -- so the
 * native side treats {@code maxAnisotropy > 1} as ANISOTROPIC, and this class
 * only requests that when both min and mag are LINEAR (otherwise a NEAREST
 * sampler would silently become smooth). See the source-of-truth doc.
 */
	final class DxSampler implements GpuSampler {
	private static final double DEFAULT_MAX_LOD = 1000.0;
	private static final double MIN_MAX_LOD = 0.25;

	private final DxBridge dxBridge;
	private final AddressMode addressModeU;
	private final AddressMode addressModeV;
	private final FilterMode minFilter;
	private final FilterMode magFilter;
	private final int maxAnisotropy;
	private final OptionalDouble maxLod;
	// Created lazily on first use (on the render thread, from the draw path's
	// texture binding): creation is a real D3D11 object per sampler, and the
	// frontend manufactures samplers eagerly for parameter sets it rarely
	// ever binds. Failure is cached as well so a once-failed sampler never
	// retries on every draw.
	private long nativeSamplerHandle;
	private boolean handleCreationFailed;
	private boolean closed;

	DxSampler(DxBridge dxBridge, AddressMode addressModeU, AddressMode addressModeV, FilterMode minFilter, FilterMode magFilter, int maxAnisotropy, OptionalDouble maxLod) {
		this.dxBridge = dxBridge;
		this.addressModeU = addressModeU;
		this.addressModeV = addressModeV;
		this.minFilter = minFilter;
		this.magFilter = magFilter;
		this.maxAnisotropy = maxAnisotropy;
		this.maxLod = maxLod == null ? OptionalDouble.empty() : maxLod;
	}

	static int nativeAddressMode(AddressMode mode) {
		return switch (mode) {
			case REPEAT -> DxBridge.SAMPLER_ADDRESS_WRAP;
			case CLAMP_TO_EDGE -> DxBridge.SAMPLER_ADDRESS_CLAMP;
		};
	}

	static boolean mipLinear(OptionalDouble maxLod) {
		return maxLod.orElse(DEFAULT_MAX_LOD) > MIN_MAX_LOD;
	}

	static float nativeMaxLod(OptionalDouble maxLod) {
		return (float) Math.max(MIN_MAX_LOD, maxLod.orElse(DEFAULT_MAX_LOD));
	}

	/** Anisotropy the native side should see: only honoured with linear min+mag (see class comment). */
	static int nativeAnisotropy(FilterMode minFilter, FilterMode magFilter, int maxAnisotropy) {
		return minFilter == FilterMode.LINEAR && magFilter == FilterMode.LINEAR ? Math.max(1, maxAnisotropy) : 1;
	}

	/**
	 * Creates the native `ID3D11SamplerState` on first use, once. Called only
	 * from the render thread's draw path (see the field comment); the lock is
	 * belt-and-braces for the native-call-before-release ordering.
	 */
	private synchronized long ensureNativeSamplerHandle() {
		if (this.nativeSamplerHandle != 0L || this.handleCreationFailed || this.closed) {
			return this.nativeSamplerHandle;
		}

		if (this.dxBridge == null) {
			this.handleCreationFailed = true;
			return 0L;
		}

		try {
			long handle = this.dxBridge.createSampler(
				nativeAddressMode(this.addressModeU),
				nativeAddressMode(this.addressModeV),
				this.minFilter == FilterMode.LINEAR,
				this.magFilter == FilterMode.LINEAR,
				mipLinear(this.maxLod),
				nativeAnisotropy(this.minFilter, this.magFilter, this.maxAnisotropy),
				nativeMaxLod(this.maxLod)
			);
			if (handle != 0L) {
				DxNativeResourceLog.nativeActive("sampler", this.minFilter + "/" + this.magFilter + ", aniso " + this.maxAnisotropy);
				this.nativeSamplerHandle = handle;
				return handle;
			}
		} catch (UnsupportedOperationException | IllegalStateException error) {
			DxNativeResourceLog.fallback("sampler", error.getMessage());
		}
		this.handleCreationFailed = true;
		return 0L;
	}

	long nativeSamplerHandle() {
		return ensureNativeSamplerHandle();
	}

	boolean hasNativeSamplerHandle() {
		return ensureNativeSamplerHandle() != 0L;
	}

	@Override
	public AddressMode getAddressModeU() {
		return this.addressModeU;
	}

	@Override
	public AddressMode getAddressModeV() {
		return this.addressModeV;
	}

	@Override
	public FilterMode getMinFilter() {
		return this.minFilter;
	}

	@Override
	public FilterMode getMagFilter() {
		return this.magFilter;
	}

	@Override
	public int getMaxAnisotropy() {
		return this.maxAnisotropy;
	}

	@Override
	public OptionalDouble getMaxLod() {
		return this.maxLod;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		// ensureNativeSamplerHandle() takes the same lock: if it is mid-creation,
		// it finishes first and this then releases what it created; if close
		// wins the lock, no sampler is ever created for this object.
		synchronized (this) {
			if (this.nativeSamplerHandle != 0L && this.dxBridge != null) {
				this.dxBridge.releaseSampler(this.nativeSamplerHandle);
				this.nativeSamplerHandle = 0L;
			}
		}
	}
}
