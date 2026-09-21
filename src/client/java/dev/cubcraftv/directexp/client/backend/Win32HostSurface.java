package dev.cubcraftv.directexp.client.backend;

/**
 * Unlike Cocoa (which needs an NSWindow *and* NSView pair to attach a
 * CAMetalLayer), DXGI only needs the Win32 HWND to build a swap chain, so
 * this is a single-field record rather than a pair like MetalExp's
 * CocoaHostSurface. See AGENTS.md "Where DirectExp Diverges From MetalExp".
 */
record Win32HostSurface(
	long win32WindowHandle
) {
}
