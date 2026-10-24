package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.device.BackendCreationException;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import dev.cubcraftv.directexp.bridge.DxHostSurfaceBootstrap;

import java.util.List;
import java.util.Objects;

final class DxBackendBootstrapContext implements AutoCloseable {
	private static final String WINDOW_HANDLE_MISSING_MESSAGE = "DirectX surface requires a live SDL window handle.";

	private final DxBridge dxBridge;
	private final Win32HostSurface win32HostSurface;
	private final DxHostSurfaceBootstrap hostSurfaceBootstrap;
	private boolean closed;
	private boolean surfaceLeaseTransferred;

	private DxBackendBootstrapContext(DxBridge dxBridge, Win32HostSurface win32HostSurface, DxHostSurfaceBootstrap hostSurfaceBootstrap) {
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.win32HostSurface = Objects.requireNonNull(win32HostSurface, "win32HostSurface");
		this.hostSurfaceBootstrap = Objects.requireNonNull(hostSurfaceBootstrap, "hostSurfaceBootstrap");
	}

	static DxBackendBootstrapContext bootstrap(DxBridge dxBridge, Win32HostSurfaceResolver win32HostSurfaceResolver, long window) throws BackendCreationException {
		Objects.requireNonNull(dxBridge, "dxBridge");
		Objects.requireNonNull(win32HostSurfaceResolver, "win32HostSurfaceResolver");

		if (window == 0L) {
			throw new BackendCreationException(
				WINDOW_HANDLE_MISSING_MESSAGE,
				BackendCreationException.Reason.OTHER,
				List.of("sdl_window_handle")
			);
		}

		Win32HostSurface win32HostSurface = win32HostSurfaceResolver.resolve(window);

		DxBridgeProbe surfaceProbe = dxBridge.probeSurface(win32HostSurface.win32WindowHandle());
		if (!surfaceProbe.isReady()) {
			throw new BackendCreationException(
				surfaceProbe.detail(),
				BackendCreationException.Reason.OTHER,
				surfaceProbe.missingCapabilities()
			);
		}

		DxHostSurfaceBootstrap hostSurfaceBootstrap = dxBridge.bootstrapSurface(win32HostSurface.win32WindowHandle());
		if (!hostSurfaceBootstrap.isReady()) {
			throw new BackendCreationException(
				hostSurfaceBootstrap.detail(),
				BackendCreationException.Reason.OTHER,
				hostSurfaceBootstrap.missingCapabilities()
			);
		}

		return new DxBackendBootstrapContext(dxBridge, win32HostSurface, hostSurfaceBootstrap);
	}

	Win32HostSurface win32HostSurface() {
		return this.win32HostSurface;
	}

	DxSurfaceDescriptor surfaceDescriptor() {
		return new DxSurfaceDescriptor(
			this.win32HostSurface.win32WindowHandle(),
			this.hostSurfaceBootstrap.nativeSurfaceHandle(),
			this.hostSurfaceBootstrap.drawableWidth(),
			this.hostSurfaceBootstrap.drawableHeight(),
			this.hostSurfaceBootstrap.dpiScale()
		);
	}

	DxSurfaceLease detachSurfaceLease() {
		if (this.closed) {
			throw new IllegalStateException("DirectX backend bootstrap context is already closed.");
		}

		if (this.surfaceLeaseTransferred) {
			throw new IllegalStateException("DirectX surface lease has already been detached.");
		}

		this.surfaceLeaseTransferred = true;
		return new DxSurfaceLease(this.dxBridge, this.surfaceDescriptor());
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		if (!this.surfaceLeaseTransferred) {
			this.dxBridge.releaseSurface(this.hostSurfaceBootstrap.nativeSurfaceHandle());
		}
	}
}
