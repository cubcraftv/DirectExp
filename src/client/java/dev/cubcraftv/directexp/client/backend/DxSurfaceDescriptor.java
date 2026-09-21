package dev.cubcraftv.directexp.client.backend;

record DxSurfaceDescriptor(
	long win32WindowHandle,
	long nativeSurfaceHandle,
	int drawableWidth,
	int drawableHeight,
	double dpiScale
) {
}
