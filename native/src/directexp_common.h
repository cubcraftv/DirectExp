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

// Persistent state for one GPU texture created via createTexture2D0.
// Allocated on the heap, handed back to Java as an opaque jlong, released
// in releaseTexture0.
struct Texture {
	ID3D11Texture2D* texture = nullptr;
	UINT width = 0;
	UINT height = 0;
	UINT mipLevels = 0;
	// Recorded at creation so createTextureViewNative can build a matching
	// SRV without re-querying the resource. Always RGBA8_UNORM today (see
	// DxTexture.createNativeTextureHandle); the field exists so widening
	// the native texture path later doesn't also mean touching the view code.
	DXGI_FORMAT format = DXGI_FORMAT_R8G8B8A8_UNORM;
};

// Bind-purpose mask for createBufferNative. Keep in sync with
// DxBridge.BUFFER_BIND_* (Java) -- the Java side deliberately does not know
// D3D11's own D3D11_BIND_* values, and this side deliberately does not know
// Blaze3D's GpuBuffer.Usage bit layout.
constexpr jint BUFFER_BIND_VERTEX = 1;
constexpr jint BUFFER_BIND_INDEX = 2;
constexpr jint BUFFER_BIND_CONSTANT = 4;

// Keep in sync with DxBridge.SAMPLER_ADDRESS_*.
constexpr jint SAMPLER_ADDRESS_WRAP = 0;
constexpr jint SAMPLER_ADDRESS_CLAMP = 1;

// Persistent state for one GPU buffer created via createBufferNative
// (Milestone 4.6 prerequisite scope; see
// docs/plans/2026-09-28-directexp-milestone4.6-source-of-truth.md).
struct Buffer {
	ID3D11Buffer* buffer = nullptr;
	UINT byteWidth = 0;
	UINT bindFlags = 0;
};

// One ID3D11SamplerState, created via createSamplerNative.
struct Sampler {
	ID3D11SamplerState* state = nullptr;
};

// One ID3D11ShaderResourceView over a Texture, created via
// createTextureViewNative. Holds its own COM reference on the underlying
// resource, so releasing the Texture wrapper first is safe.
struct TextureView {
	ID3D11ShaderResourceView* view = nullptr;
};

// The device/context of the single surface currently bootstrapped, set by
// bootstrapSurface0 and cleared by releaseSurface0 (directexp_bridge_surface.cpp).
// Texture creation/upload (directexp_bridge_texture.cpp) has no
// surface/device handle of its own to work with -- the DxBridge interface's
// createTexture2D/uploadTextureRgba8 are deliberately surface-agnostic on
// the Java side -- so it operates against whichever device is "active"
// here. This assumes a single window/device for the whole process, which
// matches every other part of this bridge (Win32HostSurfaceResolver
// resolves exactly one window, DxBackendBootstrapContext bootstraps exactly
// one surface). Revisit if/when multi-window support is ever a goal; it
// currently is not (see AGENTS.md's Non-Goals).
extern ID3D11Device* g_activeDevice;
extern ID3D11DeviceContext* g_activeContext;

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

// Converts an opaque jlong handle to/from a heap-allocated pointer. Shared
// between directexp_bridge_surface.cpp (Surface*) and
// directexp_bridge_texture.cpp (Texture*) so both use the same
// pointer<->jlong convention.
template <typename T>
T* HandleToPointer(jlong handle) {
	return reinterpret_cast<T*>(static_cast<intptr_t>(handle));
}

template <typename T>
jlong PointerToHandle(T* pointer) {
	return static_cast<jlong>(reinterpret_cast<intptr_t>(pointer));
}

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
