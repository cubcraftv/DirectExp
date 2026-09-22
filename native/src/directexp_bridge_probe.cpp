// DirectExp native bridge - general capability probe entrypoint.
//
// Scope: answers "can we stand up a D3D11 device at feature level 10.1 or
// better on this machine at all", plus adapter identity for diagnostics.
// Does not touch any window/HWND -- see directexp_bridge_surface.cpp for
// the window-bound probe (probeSurface0) and the real swap chain lifecycle
// (bootstrapSurface0 onward).
//
// NOTE: this file has not been compiled by the authoring agent (a Linux
// sandbox without an MSVC/Windows SDK toolchain available). It has been
// compiled and built successfully by the project owner on a real Windows
// MSYS2 UCRT64 toolchain as of the bootstrap-plan Progress Log entry below
// this file's introduction -- check docs/plans's Progress Log for the most
// recent verification status before assuming this is still unverified.
//
// JNI naming: Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_<method>
// mirrors the `native` method declarations in NativeDxBridge.java.

#include "directexp_common.h"

#ifdef _WIN32

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_probe0(JNIEnv* env, jclass) {
	directexp::DeviceProbeResult result = directexp::RunDeviceCapabilityProbe(/* gatherAdapterInfo */ true);
	return directexp::BuildProbeResult(env, result);
}

#else // !_WIN32

// Non-Windows build: the library still needs to link (e.g. if a CI matrix
// ever compiles it for lint/format checks), but every entrypoint reports
// "unsupported OS" rather than attempting anything.
extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_probe0(JNIEnv* env, jclass) {
	(void) env;
	return nullptr;
}

#endif
