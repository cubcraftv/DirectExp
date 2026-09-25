package dev.cubcraftv.directexp.bridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class NativeDxBridge implements DxBridge {
	private static final NativeDxBridge INSTANCE = new NativeDxBridge();

	// D3D11's immediate-context model means there is no real object to
	// create here yet -- see directexp_bridge_surface.cpp's file header for
	// why createCommandContext/submitCommandContext/releaseCommandContext
	// stay Java-only no-ops for now. This counter just hands out distinct,
	// non-zero handles so callers can't mistake "no context" (0) for a live
	// one, without needing a native round-trip.
	private static final AtomicLong NEXT_COMMAND_CONTEXT_HANDLE = new AtomicLong(1);

	private NativeDxBridge() {
	}

	public static NativeDxBridge getInstance() {
		return INSTANCE;
	}

	@Override
	public DxBridgeProbe probe() {
		return runProbe(NativeDxBridge::probe0);
	}

	@Override
	public DxBridgeProbe probeSurface(long win32WindowHandle) {
		return runProbe(() -> probeSurface0(win32WindowHandle));
	}

	@Override
	public DxHostSurfaceBootstrap bootstrapSurface(long win32WindowHandle) {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			DxBridgeProbe unloaded = fromLoadResult(loadResult);
			return new DxHostSurfaceBootstrap(0L, 0, 0, 1.0, unloaded.detail(), unloaded.missingCapabilities(), false, false);
		}

		try {
			NativeDxBridgeSurfaceBootstrapResult nativeResult = bootstrapSurface0(win32WindowHandle);
			return fromNativeBootstrapResult(nativeResult);
		} catch (UnsatisfiedLinkError error) {
			return new DxHostSurfaceBootstrap(
				0L, 0, 0, 1.0,
				error.getMessage() == null ? "DirectX bridge native surface bootstrap entrypoint is missing." : error.getMessage(),
				List.of("native_bootstrap_surface_entrypoint"),
				true,
				false
			);
		} catch (RuntimeException error) {
			return new DxHostSurfaceBootstrap(
				0L, 0, 0, 1.0,
				error.getMessage() == null ? "DirectX bridge native surface bootstrap failed." : error.getMessage(),
				List.of("native_bootstrap_surface_runtime"),
				true,
				true
			);
		}
	}

	@Override
	public void configureSurface(long nativeSurfaceHandle, int width, int height, boolean vsync) {
		configureSurface0(nativeSurfaceHandle, width, height, vsync);
	}

	@Override
	public void acquireSurface(long nativeSurfaceHandle) {
		acquireSurface0(nativeSurfaceHandle);
	}

	@Override
	public long createCommandContext() {
		return NEXT_COMMAND_CONTEXT_HANDLE.getAndIncrement();
	}

	@Override
	public void submitCommandContext(long nativeCommandContextHandle) {
		// No native work to flush yet -- see the class-level comment on
		// NEXT_COMMAND_CONTEXT_HANDLE.
	}

	@Override
	public void releaseCommandContext(long nativeCommandContextHandle) {
	}

	@Override
	public void blitSurfaceRgba8(long nativeSurfaceHandle, ByteBuffer rgbaPixels, int width, int height) {
		blitSurfaceRgba80(nativeSurfaceHandle, rgbaPixels, width, height);
	}

	@Override
	public long createTexture2D(int width, int height, int mipLevels) {
		return createTexture2DNative(width, height, mipLevels);
	}

	@Override
	public void uploadTextureRgba8(long nativeTextureHandle, int mipLevel, ByteBuffer rgbaPixels, int width, int height) {
		uploadTextureRgba8Native(nativeTextureHandle, mipLevel, rgbaPixels, width, height);
	}

	@Override
	public void releaseTexture(long nativeTextureHandle) {
		if (nativeTextureHandle == 0L) {
			return;
		}
		releaseTexture0(nativeTextureHandle);
	}

	@Override
	public void blitSurfaceTexture(long nativeCommandContextHandle, long nativeSurfaceHandle, long nativeTextureHandle) {
		blitSurfaceTexture0(nativeCommandContextHandle, nativeSurfaceHandle, nativeTextureHandle);
	}

	@Override
	public void presentSurface(long nativeSurfaceHandle) {
		presentSurface0(nativeSurfaceHandle);
	}

	@Override
	public void releaseSurface(long nativeSurfaceHandle) {
		if (nativeSurfaceHandle == 0L) {
			return;
		}
		releaseSurface0(nativeSurfaceHandle);
	}

	private DxBridgeProbe runProbe(Supplier<NativeDxBridgeProbeResult> nativeCall) {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			return fromLoadResult(loadResult);
		}

		try {
			return fromNativeResult(nativeCall.get());
		} catch (UnsatisfiedLinkError error) {
			return new DxBridgeProbe(
				DxBridgeProbeStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native probe entrypoint is missing." : error.getMessage(),
				List.of("native_probe_entrypoint"),
				true,
				false,
				0,
				null,
				0,
				0L
			);
		} catch (RuntimeException error) {
			return new DxBridgeProbe(
				DxBridgeProbeStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native probe failed." : error.getMessage(),
				List.of("native_probe_runtime"),
				true,
				true,
				0,
				null,
				0,
				0L
			);
		}
	}

	private DxBridgeProbe fromLoadResult(DxBridgeLoadResult loadResult) {
		DxBridgeProbeStatus status = switch (loadResult.status()) {
			case UNSUPPORTED_OS -> DxBridgeProbeStatus.UNSUPPORTED_OS;
			case NOT_FOUND -> DxBridgeProbeStatus.LIBRARY_MISSING;
			case LOAD_FAILED -> DxBridgeProbeStatus.LIBRARY_LOAD_FAILED;
			case DEPENDENCY_MISSING -> DxBridgeProbeStatus.RUNTIME_DEPENDENCY_MISSING;
			case LOADED -> DxBridgeProbeStatus.NATIVE_ERROR;
		};

		List<String> missingCapabilities = switch (loadResult.status()) {
			case UNSUPPORTED_OS -> List.of("windows_host");
			case NOT_FOUND, LOAD_FAILED -> List.of("native_directx_bridge_library");
			case DEPENDENCY_MISSING -> List.of(loadResult.source());
			case LOADED -> List.of("native_directx_bridge");
		};

		return new DxBridgeProbe(
			status,
			loadResult.detail(),
			missingCapabilities,
			false,
			false,
			0,
			null,
			0,
			0L
		);
	}

	private DxBridgeProbe fromNativeResult(NativeDxBridgeProbeResult nativeResult) {
		if (nativeResult == null) {
			return new DxBridgeProbe(
				DxBridgeProbeStatus.NATIVE_ERROR,
				"DirectX bridge native probe returned no result.",
				List.of("native_probe_result"),
				true,
				true,
				0,
				null,
				0,
				0L
			);
		}

		List<String> missingCapabilities = nativeResult.missingCapabilities() == null
			? List.of()
			: Arrays.asList(nativeResult.missingCapabilities());

		DxBridgeProbeStatus status = switch (nativeResult.outcomeCode()) {
			case NativeDxBridgeProbeResult.OUTCOME_READY -> DxBridgeProbeStatus.READY;
			case NativeDxBridgeProbeResult.OUTCOME_STUB_UNIMPLEMENTED -> DxBridgeProbeStatus.STUB_UNIMPLEMENTED;
			default -> missingCapabilities.contains("feature_level_10_1")
				? DxBridgeProbeStatus.FEATURE_LEVEL_UNSUPPORTED
				: DxBridgeProbeStatus.NATIVE_ERROR;
		};

		return new DxBridgeProbe(
			status,
			nativeResult.detail(),
			missingCapabilities,
			true,
			true,
			nativeResult.featureLevel(),
			nativeResult.adapterDescription(),
			nativeResult.vendorId(),
			nativeResult.dedicatedVideoMemoryBytes()
		);
	}

	private DxHostSurfaceBootstrap fromNativeBootstrapResult(NativeDxBridgeSurfaceBootstrapResult nativeResult) {
		if (nativeResult == null) {
			return new DxHostSurfaceBootstrap(
				0L, 0, 0, 1.0,
				"DirectX bridge native surface bootstrap returned no result.",
				List.of("native_bootstrap_surface_result"),
				true,
				true
			);
		}

		List<String> missingCapabilities = nativeResult.missingCapabilities() == null
			? List.of()
			: Arrays.asList(nativeResult.missingCapabilities());

		long nativeSurfaceHandle = nativeResult.outcomeCode() == NativeDxBridgeSurfaceBootstrapResult.OUTCOME_READY
			? nativeResult.nativeSurfaceHandle()
			: 0L;

		return new DxHostSurfaceBootstrap(
			nativeSurfaceHandle,
			nativeResult.drawableWidth(),
			nativeResult.drawableHeight(),
			nativeResult.dpiScale(),
			nativeResult.detail(),
			missingCapabilities,
			true,
			true
		);
	}

	private static native NativeDxBridgeProbeResult probe0();

	private static native NativeDxBridgeProbeResult probeSurface0(long win32WindowHandle);

	private static native NativeDxBridgeSurfaceBootstrapResult bootstrapSurface0(long win32WindowHandle);

	private static native void configureSurface0(long nativeSurfaceHandle, int width, int height, boolean vsync);

	private static native void acquireSurface0(long nativeSurfaceHandle);

	// Named with a trailing "0" (matching this file's other native method
	// naming) rather than "blitSurfaceRgba8" to avoid a same-signature
	// clash with the public override above that forwards to it.
	private static native void blitSurfaceRgba80(long nativeSurfaceHandle, ByteBuffer rgbaPixels, int width, int height);

	private static native void presentSurface0(long nativeSurfaceHandle);

	private static native void releaseSurface0(long nativeSurfaceHandle);

	// Named "...Native"/"...0" rather than exactly matching the interface
	// method names above (createTexture2D, uploadTextureRgba8) for the same
	// signature-clash reason as blitSurfaceRgba80: a static native method
	// and the public override that forwards to it can't share a name when
	// their parameter lists are identical.
	private static native long createTexture2DNative(int width, int height, int mipLevels);

	private static native void uploadTextureRgba8Native(long nativeTextureHandle, int mipLevel, ByteBuffer rgbaPixels, int width, int height);

	private static native void releaseTexture0(long nativeTextureHandle);

	private static native void blitSurfaceTexture0(long nativeCommandContextHandle, long nativeSurfaceHandle, long nativeTextureHandle);
}
