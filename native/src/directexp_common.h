// Shared declarations for the DirectExp native bridge. See
// directexp_bridge_probe.cpp (general capability probe) and
// directexp_bridge_surface.cpp (swap chain lifecycle) for the JNI
// entrypoints that use these.
#pragma once

#include <jni.h>

#ifdef _WIN32
#include <windows.h>
#include <d3d11.h>
#include <dxgi.h>
// IDXGISwapChain1 / IDXGIFactory2 / DXGI_SWAP_CHAIN_DESC1 / DXGI_ALPHA_MODE_*
// / DXGI_SCALING_* (needed for the flip-model swap chain path) are declared
// in dxgi1_2.h (DXGI 1.2), not the base dxgi.h above.
#include <dxgi1_2.h>
#include <string>
#include <vector>

namespace directexp {

// DirectExp's floor per project scope: DX11 feature set, FL 10.1 devices.
constexpr D3D_FEATURE_LEVEL kMinimumFeatureLevel = D3D_FEATURE_LEVEL_10_1;

extern const D3D_FEATURE_LEVEL kRequestedFeatureLevels[3];
constexpr UINT kRequestedFeatureLevelCount = 3;

// Keep in sync with NativeDxBridgeProbeResult.OUTCOME_* and
// NativeDxBridgeSurfaceBootstrapResult.OUTCOME_* (both share these values).
constexpr jint OUTCOME_READY = 0;
constexpr jint OUTCOME_STUB_UNIMPLEMENTED = 1;
constexpr jint OUTCOME_ERROR = 2;

// Persistent state for one bootstrapped surface. Allocated on the heap in
// bootstrapSurface0 and handed back to Java as an opaque jlong; released in
// releaseSurface0. Never copy this by value -- always pass around the
// pointer/handle.
struct Surface {
	HWND hwnd = nullptr;
	ID3D11Device* device = nullptr;
	ID3D11DeviceContext* context = nullptr;
	IDXGISwapChain1* swapChain = nullptr;
	UINT width = 0;
	UINT height = 0;
	bool vsync = false;
};

// Plain-data result of attempting to create a D3D11 device at or above
// DirectExp's feature level floor. Not a JNI type itself -- see
// BuildProbeResult to turn this into a NativeDxBridgeProbeResult.
struct DeviceProbeResult {
	bool ready = false;
	jint outcomeCode = OUTCOME_ERROR;
	std::string detail;
	std::vector<std::string> missingCapabilities;
	jint featureLevel = 0;
	std::string adapterDescription;
	jint vendorId = 0;
	jlong dedicatedVideoMemoryBytes = 0;
};

// Creates a throwaway D3D11 device to check capability, reports the result,
// and releases the device before returning. Used by both probe0 (general
// system probe) and probeSurface0 (pre-bootstrap check). Does not touch any
// window/HWND -- HWND validity is checked separately by the caller.
DeviceProbeResult RunDeviceCapabilityProbe(bool gatherAdapterInfo);

// Converts a DeviceProbeResult into a
// dev.cubcraftv.directexp.bridge.NativeDxBridgeProbeResult instance.
// Returns nullptr (with a pending JNI exception) only on a JNI-level
// failure (e.g. the class/constructor could not be found), which should
// not happen outside of a classpath misconfiguration.
jobject BuildProbeResult(JNIEnv* env, const DeviceProbeResult& result);

// Converts a bootstrap outcome into a
// dev.cubcraftv.directexp.bridge.NativeDxBridgeSurfaceBootstrapResult
// instance.
jobject BuildSurfaceBootstrapResult(
	JNIEnv* env,
	jlong nativeSurfaceHandle,
	jint drawableWidth,
	jint drawableHeight,
	jdouble dpiScale,
	jint outcomeCode,
	const std::string& detail,
	const std::vector<std::string>& missingCapabilities
);

// Queries the per-monitor DPI scale for a window via GetDpiForWindow,
// resolved dynamically through GetProcAddress so this links on both a
// recent MSVC/Windows SDK and an older mingw-w64 import library that may
// not export the symbol. Falls back to 1.0 if the API isn't available.
double QueryDpiScaleForWindow(HWND hwnd);

// Throws a java.lang.IllegalStateException with the given message. Callers
// of a `void`-returning JNI method should `return;` immediately after
// calling this; callers of an object-returning method should `return
// nullptr;`.
void ThrowIllegalState(JNIEnv* env, const std::string& message);

std::wstring Utf8ToWide(const char* text);
std::string WideToUtf8(const wchar_t* text);
jstring ToJString(JNIEnv* env, const std::string& text);

} // namespace directexp

#endif // _WIN32
