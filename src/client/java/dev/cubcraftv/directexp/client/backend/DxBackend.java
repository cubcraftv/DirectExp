package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.device.GpuDebugOptions;
import com.mojang.renderpearl.api.device.BackendCreationException;
import com.mojang.renderpearl.api.device.GpuBackend;
import com.mojang.renderpearl.api.device.GpuDevice;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxBridgeProbe;
import dev.cubcraftv.directexp.bridge.NativeDxBridge;
import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import org.lwjgl.sdl.SDLVideo;

import java.util.Objects;

public final class DxBackend implements GpuBackend {
	private final DxBridge dxBridge;
	private final Win32HostSurfaceResolver win32HostSurfaceResolver;
	private final GpuDeviceFactory gpuDeviceFactory;

	public DxBackend() {
		this(
			NativeDxBridge.getInstance(),
			new LwjglWin32HostSurfaceResolver(),
			(dxBridge, surfaceResolver) -> new FrontendGpuDevice(new DxDeviceBackend(dxBridge, surfaceResolver))
		);
	}

	DxBackend(DxBridge dxBridge) {
		this(
			dxBridge,
			new LwjglWin32HostSurfaceResolver(),
			(dxBridgeInstance, surfaceResolver) -> new FrontendGpuDevice(new DxDeviceBackend(dxBridgeInstance, surfaceResolver))
		);
	}

	DxBackend(DxBridge dxBridge, Win32HostSurfaceResolver win32HostSurfaceResolver) {
		this(
			dxBridge,
			win32HostSurfaceResolver,
			(dxBridgeInstance, surfaceResolver) -> new FrontendGpuDevice(new DxDeviceBackend(dxBridgeInstance, surfaceResolver))
		);
	}

	DxBackend(DxBridge dxBridge, Win32HostSurfaceResolver win32HostSurfaceResolver, GpuDeviceFactory gpuDeviceFactory) {
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.win32HostSurfaceResolver = Objects.requireNonNull(win32HostSurfaceResolver, "win32HostSurfaceResolver");
		this.gpuDeviceFactory = Objects.requireNonNull(gpuDeviceFactory, "gpuDeviceFactory");
	}

	@Override
	public String getName() {
		return "DirectX";
	}

	@Override
	public void loadLibrary() {
		// Nothing to load: D3D11 is a system DLL the native bridge links against.
	}

	@Override
	public void unloadLibrary() {
	}

	/**
	 * 26.3 windows come from SDL. A DirectX window needs neither the OpenGL nor
	 * the Vulkan SDL flag, so only the caller's flags apply.
	 */
	@Override
	public long createWindow(String title, int width, int height, long flags) {
		return SDLVideo.SDL_CreateWindow(title, width, height, flags);
	}

	@Override
	public GpuDevice createDevice(GpuDebugOptions debugOptions) throws BackendCreationException {
		DxBridgeProbe systemProbe = this.dxBridge.probe();
		if (!systemProbe.isReady()) {
			throw new BackendCreationException(
				systemProbe.detail(),
				BackendCreationException.Reason.OTHER,
				systemProbe.missingCapabilities()
			);
		}

		return this.gpuDeviceFactory.create(this.dxBridge, this.win32HostSurfaceResolver);
	}

	@FunctionalInterface
	interface GpuDeviceFactory {
		GpuDevice create(DxBridge dxBridge, Win32HostSurfaceResolver surfaceResolver);
	}
}
