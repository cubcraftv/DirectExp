package dev.cubcraftv.directexp.client.backend;

import org.lwjgl.sdl.SDLProperties;
import org.lwjgl.sdl.SDLVideo;

/** Resolves the Win32 HWND of an SDL window (SDL_PROP_WINDOW_WIN32_HWND_POINTER). */
final class LwjglWin32HostSurfaceResolver implements Win32HostSurfaceResolver {
	private static final String HWND_PROPERTY = "SDL.window.win32.hwnd";

	@Override
	public Win32HostSurface resolve(long sdlWindowHandle) {
		int properties = SDLVideo.SDL_GetWindowProperties(sdlWindowHandle);
		return new Win32HostSurface(SDLProperties.SDL_GetPointerProperty(properties, HWND_PROPERTY, 0L));
	}
}
