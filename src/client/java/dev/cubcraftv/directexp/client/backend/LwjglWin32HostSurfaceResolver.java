package dev.cubcraftv.directexp.client.backend;

import org.lwjgl.glfw.GLFWNativeWin32;

final class LwjglWin32HostSurfaceResolver implements Win32HostSurfaceResolver {
	@Override
	public Win32HostSurface resolve(long glfwWindowHandle) {
		return new Win32HostSurface(
			GLFWNativeWin32.glfwGetWin32Window(glfwWindowHandle)
		);
	}
}
