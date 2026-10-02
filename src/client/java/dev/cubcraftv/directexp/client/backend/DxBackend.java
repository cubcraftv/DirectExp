package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.NativeDxBridge;
import org.lwjgl.glfw.GLFW;

import java.util.Objects;
import java.util.function.BiFunction;

public final class DxBackend implements GpuBackend {
	private static final String WINDOW_CREATION_FAILURE_MESSAGE = "Failed to create window for DirectX";
	private final DxBridge dxBridge;
	private final Win32HostSurfaceResolver win32HostSurfaceResolver;
	private final BiFunction<DxSurfaceLease, ShaderSource, GpuDevice> gpuDeviceFactory;

	public DxBackend() {
		this(
			NativeDxBridge.getInstance(),
			new LwjglWin32HostSurfaceResolver(),
			(surfaceLease, defaultShaderSource) -> new GpuDevice(new DxDeviceBackend(surfaceLease, defaultShaderSource))
		);
	}

	DxBackend(DxBridge dxBridge) {
		this(
			dxBridge,
			new LwjglWin32HostSurfaceResolver(),
			(surfaceLease, defaultShaderSource) -> new GpuDevice(new DxDeviceBackend(surfaceLease, defaultShaderSource))
		);
	}

	DxBackend(DxBridge dxBridge, Win32HostSurfaceResolver win32HostSurfaceResolver) {
		this(
			dxBridge,
			win32HostSurfaceResolver,
			(surfaceLease, defaultShaderSource) -> new GpuDevice(new DxDeviceBackend(surfaceLease, defaultShaderSource))
		);
	}

	DxBackend(DxBridge dxBridge, Win32HostSurfaceResolver win32HostSurfaceResolver, BiFunction<DxSurfaceLease, ShaderSource, GpuDevice> gpuDeviceFactory) {
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.win32HostSurfaceResolver = Objects.requireNonNull(win32HostSurfaceResolver, "win32HostSurfaceResolver");
		this.gpuDeviceFactory = Objects.requireNonNull(gpuDeviceFactory, "gpuDeviceFactory");
	}

	@Override
	public String getName() {
		return "DirectX";
	}

	@Override
	public void setWindowHints() {
		GLFW.glfwDefaultWindowHints();
		GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
	}

	@Override
	public void handleWindowCreationErrors(GLFWErrorCapture.Error error) throws BackendCreationException {
		if (error != null) {
			throw new BackendCreationException(error.toString(), BackendCreationException.Reason.GLFW_ERROR);
		}

		throw new BackendCreationException(WINDOW_CREATION_FAILURE_MESSAGE, BackendCreationException.Reason.GLFW_ERROR);
	}

	@Override
	public GpuDevice createDevice(long window, ShaderSource defaultShaderSource, GpuDebugOptions debugOptions) throws BackendCreationException {
		try (DxBackendBootstrapContext bootstrapContext = DxBackendBootstrapContext.bootstrap(
			this.dxBridge,
			this.win32HostSurfaceResolver,
			window
		)) {
			DxSurfaceLease surfaceLease = bootstrapContext.detachSurfaceLease();

			try {
				return this.gpuDeviceFactory.apply(surfaceLease, defaultShaderSource);
			} catch (RuntimeException | Error error) {
				surfaceLease.close();
				throw error;
			}
		}
	}
}
