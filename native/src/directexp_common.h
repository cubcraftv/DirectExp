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
// Texture format codes for createTextureNative. Keep in sync with
// DxBridge.TEXTURE_FORMAT_*. Codes, not DXGI values, so Java never needs
// DXGI's numbering for textures (depth formats in particular need a
// typeless resource format plus different DSV/SRV view formats, which is
// this side's business -- see ResolveTextureFormat).
constexpr jint TEXTURE_FORMAT_RGBA8_UNORM = 1;
constexpr jint TEXTURE_FORMAT_D32_FLOAT = 2;
constexpr jint TEXTURE_FORMAT_D24_UNORM_S8_UINT = 3;
constexpr jint TEXTURE_FORMAT_D32_FLOAT_S8_UINT = 4;
constexpr jint TEXTURE_FORMAT_D16_UNORM = 5;

// Keep in sync with DxBridge.TEXTURE_BIND_*.
constexpr jint TEXTURE_BIND_SHADER_RESOURCE = 1;
constexpr jint TEXTURE_BIND_RENDER_TARGET = 2;
constexpr jint TEXTURE_BIND_DEPTH_STENCIL = 4;

struct TextureFormatInfo {
	DXGI_FORMAT resource;    // format of the ID3D11Texture2D (typeless for depth)
	DXGI_FORMAT shaderView;  // SRV format
	DXGI_FORMAT targetView;  // RTV (color) or DSV (depth) format
	bool depth;
};

// Depth pairings follow the documented typeless convention: the resource is
// created typeless, the DSV uses the D* format and the SRV the matching
// R*_ format (D24S8 -> R24_UNORM_X8_TYPELESS, D32 -> R32_FLOAT, D32S8 ->
// R32_FLOAT_X8X24_TYPELESS, D16 -> R16_UNORM).
inline bool ResolveTextureFormat(jint code, TextureFormatInfo& out) {
	switch (code) {
		case TEXTURE_FORMAT_RGBA8_UNORM:
			out = { DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R8G8B8A8_UNORM, DXGI_FORMAT_R8G8B8A8_UNORM, false };
			return true;
		case TEXTURE_FORMAT_D32_FLOAT:
			out = { DXGI_FORMAT_R32_TYPELESS, DXGI_FORMAT_R32_FLOAT, DXGI_FORMAT_D32_FLOAT, true };
			return true;
		case TEXTURE_FORMAT_D24_UNORM_S8_UINT:
			out = { DXGI_FORMAT_R24G8_TYPELESS, DXGI_FORMAT_R24_UNORM_X8_TYPELESS, DXGI_FORMAT_D24_UNORM_S8_UINT, true };
			return true;
		case TEXTURE_FORMAT_D32_FLOAT_S8_UINT:
			out = { DXGI_FORMAT_R32G8X24_TYPELESS, DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS, DXGI_FORMAT_D32_FLOAT_S8X24_UINT, true };
			return true;
		case TEXTURE_FORMAT_D16_UNORM:
			out = { DXGI_FORMAT_R16_TYPELESS, DXGI_FORMAT_R16_UNORM, DXGI_FORMAT_D16_UNORM, true };
			return true;
		default:
			return false;
	}
}

struct Texture {
	ID3D11Texture2D* texture = nullptr;
	UINT width = 0;
	UINT height = 0;
	UINT mipLevels = 0;
	UINT arrayLayers = 1;
	bool cubemap = false;
	// Which D3D11_BIND_* purposes the resource was created with, and its
	// TEXTURE_FORMAT_* code; createTextureViewNative uses them to decide
	// which views (SRV / RTV / DSV) it can and must create.
	UINT bindFlags = 0;
	jint formatCode = TEXTURE_FORMAT_RGBA8_UNORM;
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

// Immutable D3D11 objects for one RenderPipeline, created by
// createPipelineNative (directexp_bridge_pipeline.cpp). inputLayout is null
// for a pipeline with no vertex inputs.
struct Pipeline {
	ID3D11VertexShader* vertexShader = nullptr;
	ID3D11PixelShader* pixelShader = nullptr;
	// Optional. Only point-sprite pipelines (gl_PointSize) have these two: the
	// generated geometry shader and the 16-byte dynamic constant buffer
	// holding the viewport size it reads (bound at b13 by the draw path,
	// filled by setPipelineViewport0).
	ID3D11GeometryShader* geometryShader = nullptr;
	ID3D11Buffer* geometryViewportBuffer = nullptr;
	ID3D11InputLayout* inputLayout = nullptr;
	ID3D11BlendState* blendState = nullptr;
	ID3D11DepthStencilState* depthStencilState = nullptr;
	ID3D11RasterizerState* rasterizerState = nullptr;
	D3D_PRIMITIVE_TOPOLOGY topology = D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST;
	UINT vertexStride = 0;
	// Viewport size last written into geometryViewportBuffer by the draw path
	// (directexp_bridge_draw.cpp); 0 until the first bind.
	UINT geometryViewportWidth = 0;
	UINT geometryViewportHeight = 0;
};

// The views one texture-view object owns over a Texture, created via
// createTextureViewNative. Each member is null when the texture wasn't
// created for that purpose (see Texture::bindFlags). Holds its own COM
// references on the underlying resource, so releasing the Texture wrapper
// first is safe.
struct TextureView {
	ID3D11ShaderResourceView* shaderView = nullptr;
	ID3D11RenderTargetView* renderTargetView = nullptr;
	ID3D11DepthStencilView* depthStencilView = nullptr;
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

// The feature level actually negotiated for g_activeDevice (0 when no
// device is active). D3D_FEATURE_LEVEL_10_1 only guarantees Shader Model
// 4.1; Shader Model 5.0 needs D3D_FEATURE_LEVEL_11_0
// [Microsoft Learn, D3D_FEATURE_LEVEL enumeration]. kRequestedFeatureLevels
// accepts 10.1 as a floor (matching kMinimumFeatureLevel and the "feature
// level 10.1 floor" the project advertises), so anything that picks a
// D3DCompile target profile or an spirv_cross::CompilerHLSL::Options::
// shader_model value MUST branch on this rather than assuming Shader
// Model 5.0 is always available -- see directexp_shader_hlsl.cpp.
extern D3D_FEATURE_LEVEL g_activeFeatureLevel;

// Draw-path state (directexp_bridge_draw.cpp). The "bound pipeline" is what
// bindVertexBuffer0 reads the vertex stride from; ForgetBoundPipeline must be
// called before a Pipeline is deleted and ResetDrawState when the device goes
// away, so the draw path never dereferences a released object.
void ForgetBoundPipeline(const Pipeline* pipeline);
void ResetDrawState();

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
