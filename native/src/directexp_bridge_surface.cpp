// DirectExp native bridge - surface (swap chain) lifecycle entrypoints.
//
// Scope: this is the first real implementation past the capability probe
// (see directexp_bridge_probe.cpp). It creates a persistent D3D11 device +
// immediate context + flip-model IDXGISwapChain1 bound to the game's HWND,
// and supports resize, a CPU-upload blit path, and present.
//
// Deliberately NOT in scope yet (see docs/plans's Milestone 3+ scope):
//   - GPU-texture-to-backbuffer blit (blitSurfaceTexture) -- DxTexture never
//     produces a native texture handle yet (createTexture2D is still a
//     Java-side default-throw stub), so DxSurfaceBackend always takes the
//     CPU-upload path (blitSurfaceRgba8/blitSurfaceRgba80 below) today.
//   - Any real command context object -- createCommandContext/
//     submitCommandContext/releaseCommandContext stay Java-only no-ops
//     (see NativeDxBridge.java) because nothing currently calls
//     commandContextHandle() while GPU textures don't exist yet. D3D11's
//     single-immediate-context model means there is nothing to actually
//     submit separately from the blit/UpdateSubresource calls below.
//   - Occlusion/device-removed recovery beyond a basic detection-and-throw.
//
// NOTE ON VERIFICATION: this file has not been compiled by the authoring
// agent (a Linux sandbox without an MSVC/Windows SDK toolchain available).
// Check docs/plans's Progress Log for the most recent build verification
// status on a real Windows machine before assuming this compiles cleanly.
//
// JNI naming: Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_<method>
// mirrors the `native` method declarations in NativeDxBridge.java.

#include "directexp_common.h"

#ifdef _WIN32

using namespace directexp;

namespace {

Surface* ToSurface(jlong handle) {
	return reinterpret_cast<Surface*>(static_cast<intptr_t>(handle));
}

jlong ToHandle(Surface* surface) {
	return static_cast<jlong>(reinterpret_cast<intptr_t>(surface));
}

void ReleaseSurfaceObjects(Surface* surface) {
	if (surface == nullptr) {
		return;
	}
	if (surface->swapChain != nullptr) {
		surface->swapChain->Release();
		surface->swapChain = nullptr;
	}
	if (surface->context != nullptr) {
		surface->context->Release();
		surface->context = nullptr;
	}
	if (surface->device != nullptr) {
		surface->device->Release();
		surface->device = nullptr;
	}
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_probeSurface0(JNIEnv* env, jclass, jlong win32WindowHandle) {
	HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(win32WindowHandle));

	if (hwnd == nullptr || !IsWindow(hwnd)) {
		DeviceProbeResult result;
		result.ready = false;
		result.outcomeCode = OUTCOME_ERROR;
		result.detail = "The provided Win32 window handle is not a live window.";
		result.missingCapabilities.push_back("win32_window_handle");
		return BuildProbeResult(env, result);
	}

	// Adapter identity isn't needed a second time here -- probe0 (the
	// general system probe) already reports it. This just re-validates
	// device creation is still possible for this specific bootstrap
	// attempt.
	DeviceProbeResult result = RunDeviceCapabilityProbe(/* gatherAdapterInfo */ false);
	return BuildProbeResult(env, result);
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bootstrapSurface0(JNIEnv* env, jclass, jlong win32WindowHandle) {
	HWND hwnd = reinterpret_cast<HWND>(static_cast<intptr_t>(win32WindowHandle));

	if (hwnd == nullptr || !IsWindow(hwnd)) {
		return BuildSurfaceBootstrapResult(
			env, 0, 0, 0, 1.0, OUTCOME_ERROR,
			"The provided Win32 window handle is not a live window.",
			{"win32_window_handle"}
		);
	}

	RECT clientRect = {};
	GetClientRect(hwnd, &clientRect);
	UINT width = static_cast<UINT>(clientRect.right - clientRect.left);
	UINT height = static_cast<UINT>(clientRect.bottom - clientRect.top);
	if (width == 0 || height == 0) {
		// Window not yet shown/sized; fall back to a 1x1 swap chain rather
		// than failing bootstrap outright -- configureSurface0 will resize
		// it once the real window size is known.
		width = 1;
		height = 1;
	}

	ID3D11Device* device = nullptr;
	ID3D11DeviceContext* context = nullptr;
	D3D_FEATURE_LEVEL negotiatedLevel = static_cast<D3D_FEATURE_LEVEL>(0);

	HRESULT hr = D3D11CreateDevice(
		nullptr,
		D3D_DRIVER_TYPE_HARDWARE,
		nullptr,
		0,
		kRequestedFeatureLevels,
		kRequestedFeatureLevelCount,
		D3D11_SDK_VERSION,
		&device,
		&negotiatedLevel,
		&context
	);

	if (FAILED(hr)) {
		return BuildSurfaceBootstrapResult(
			env, 0, 0, 0, 1.0, OUTCOME_ERROR,
			"D3D11CreateDevice failed while bootstrapping the surface.",
			{"d3d11_device"}
		);
	}

	if (negotiatedLevel < kMinimumFeatureLevel) {
		if (context != nullptr) {
			context->Release();
		}
		device->Release();
		return BuildSurfaceBootstrapResult(
			env, 0, 0, 0, 1.0, OUTCOME_ERROR,
			"D3D11 device created but negotiated feature level is below the DirectExp floor (10.1).",
			{"feature_level_10_1"}
		);
	}

	IDXGIDevice* dxgiDevice = nullptr;
	IDXGIAdapter* dxgiAdapter = nullptr;
	IDXGIFactory2* dxgiFactory = nullptr;
	IDXGISwapChain1* swapChain = nullptr;

	hr = device->QueryInterface(__uuidof(IDXGIDevice), reinterpret_cast<void**>(&dxgiDevice));
	if (SUCCEEDED(hr)) {
		hr = dxgiDevice->GetAdapter(&dxgiAdapter);
	}
	if (SUCCEEDED(hr)) {
		hr = dxgiAdapter->GetParent(__uuidof(IDXGIFactory2), reinterpret_cast<void**>(&dxgiFactory));
	}

	if (SUCCEEDED(hr)) {
		DXGI_SWAP_CHAIN_DESC1 swapChainDesc = {};
		swapChainDesc.Width = width;
		swapChainDesc.Height = height;
		swapChainDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
		swapChainDesc.SampleDesc.Count = 1;
		swapChainDesc.SampleDesc.Quality = 0;
		swapChainDesc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
		swapChainDesc.BufferCount = 2;
		// Flip-model per the design spec's DXGI research question -- the
		// modern, non-deprecated swap effect on Windows 10+.
		swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
		swapChainDesc.AlphaMode = DXGI_ALPHA_MODE_IGNORE;
		swapChainDesc.Scaling = DXGI_SCALING_STRETCH;

		hr = dxgiFactory->CreateSwapChainForHwnd(device, hwnd, &swapChainDesc, nullptr, nullptr, &swapChain);
		if (SUCCEEDED(hr)) {
			// Opt out of DXGI's default Alt+Enter fullscreen toggle;
			// Minecraft manages its own fullscreen state.
			dxgiFactory->MakeWindowAssociation(hwnd, DXGI_MWA_NO_ALT_ENTER);
		}
	}

	if (dxgiFactory != nullptr) {
		dxgiFactory->Release();
	}
	if (dxgiAdapter != nullptr) {
		dxgiAdapter->Release();
	}
	if (dxgiDevice != nullptr) {
		dxgiDevice->Release();
	}

	if (FAILED(hr) || swapChain == nullptr) {
		if (context != nullptr) {
			context->Release();
		}
		device->Release();
		return BuildSurfaceBootstrapResult(
			env, 0, 0, 0, 1.0, OUTCOME_ERROR,
			"Failed to create the DXGI swap chain for the game window.",
			{"dxgi_swap_chain"}
		);
	}

	Surface* surface = new Surface();
	surface->hwnd = hwnd;
	surface->device = device;
	surface->context = context;
	surface->swapChain = swapChain;
	surface->width = width;
	surface->height = height;
	surface->vsync = false;

	// Milestone 3 (texture creation/upload, directexp_bridge_texture.cpp)
	// operates against whichever device is "active" here -- see the
	// g_activeDevice/g_activeContext comment in directexp_common.h.
	g_activeDevice = device;
	g_activeContext = context;

	double dpiScale = QueryDpiScaleForWindow(hwnd);

	return BuildSurfaceBootstrapResult(
		env,
		ToHandle(surface),
		static_cast<jint>(width),
		static_cast<jint>(height),
		dpiScale,
		OUTCOME_READY,
		"DirectX surface bootstrapped.",
		{}
	);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_configureSurface0(
	JNIEnv* env, jclass, jlong nativeSurfaceHandle, jint width, jint height, jboolean vsync
) {
	Surface* surface = ToSurface(nativeSurfaceHandle);
	if (surface == nullptr || surface->swapChain == nullptr) {
		ThrowIllegalState(env, "configureSurface0 called with an invalid or already-released surface handle.");
		return;
	}

	surface->vsync = (vsync == JNI_TRUE);

	if (width <= 0 || height <= 0) {
		ThrowIllegalState(env, "configureSurface0 requires a positive width and height.");
		return;
	}

	if (static_cast<UINT>(width) == surface->width && static_cast<UINT>(height) == surface->height) {
		return;
	}

	// No back-buffer-derived views (e.g. RTVs) are held across frames by
	// this bridge today -- every blit/present fetches the back buffer
	// fresh via GetBuffer and releases it immediately (see
	// blitSurfaceRgba80 and present0 below) -- so there is nothing else to
	// release before ResizeBuffers. If a persistent RTV cache is added
	// later (Milestone 3+), it MUST be released here first.
	surface->context->OMSetRenderTargets(0, nullptr, nullptr);
	surface->context->Flush();

	HRESULT hr = surface->swapChain->ResizeBuffers(0, static_cast<UINT>(width), static_cast<UINT>(height), DXGI_FORMAT_UNKNOWN, 0);
	if (FAILED(hr)) {
		ThrowIllegalState(env, "IDXGISwapChain::ResizeBuffers failed.");
		return;
	}

	surface->width = static_cast<UINT>(width);
	surface->height = static_cast<UINT>(height);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_acquireSurface0(JNIEnv* env, jclass, jlong nativeSurfaceHandle) {
	Surface* surface = ToSurface(nativeSurfaceHandle);
	if (surface == nullptr || surface->device == nullptr) {
		ThrowIllegalState(env, "acquireSurface0 called with an invalid or already-released surface handle.");
		return;
	}

	// D3D11's flip-model swap chain does not require an explicit
	// acquire-next-image step the way Vulkan/Metal do -- the back buffer is
	// simply fetched via GetBuffer(0, ...) when needed (see
	// blitSurfaceRgba80). This entrypoint exists to match the shared
	// DxBridge contract and to catch a removed/reset device early with a
	// clear diagnostic, rather than failing confusingly at Present time.
	HRESULT removedReason = surface->device->GetDeviceRemovedReason();
	if (FAILED(removedReason)) {
		ThrowIllegalState(env, "The D3D11 device was removed or reset before the surface could be acquired.");
	}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_blitSurfaceRgba80(
	JNIEnv* env, jclass, jlong nativeSurfaceHandle, jobject rgbaPixels, jint width, jint height
) {
	Surface* surface = ToSurface(nativeSurfaceHandle);
	if (surface == nullptr || surface->swapChain == nullptr) {
		ThrowIllegalState(env, "blitSurfaceRgba80 called with an invalid or already-released surface handle.");
		return;
	}

	void* sourcePointer = env->GetDirectBufferAddress(rgbaPixels);
	if (sourcePointer == nullptr) {
		ThrowIllegalState(env, "blitSurfaceRgba80 requires a direct ByteBuffer.");
		return;
	}

	ID3D11Texture2D* backBuffer = nullptr;
	HRESULT hr = surface->swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&backBuffer));
	if (FAILED(hr) || backBuffer == nullptr) {
		ThrowIllegalState(env, "Failed to acquire the swap chain back buffer for blitting.");
		return;
	}

	// NOTE: this assumes width/height match the back buffer's own
	// dimensions exactly (no scaling/format conversion). That is true for
	// the current caller (DxSurfaceBackend blits the full color target at
	// its native size) but should be revisited if that assumption changes.
	UINT sourceRowPitch = static_cast<UINT>(width) * 4;
	surface->context->UpdateSubresource(backBuffer, 0, nullptr, sourcePointer, sourceRowPitch, 0);

	backBuffer->Release();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_presentSurface0(JNIEnv* env, jclass, jlong nativeSurfaceHandle) {
	Surface* surface = ToSurface(nativeSurfaceHandle);
	if (surface == nullptr || surface->swapChain == nullptr) {
		ThrowIllegalState(env, "presentSurface0 called with an invalid or already-released surface handle.");
		return;
	}

	HRESULT hr = surface->swapChain->Present(surface->vsync ? 1 : 0, 0);
	if (FAILED(hr)) {
		if (hr == DXGI_ERROR_DEVICE_REMOVED || hr == DXGI_ERROR_DEVICE_RESET) {
			ThrowIllegalState(env, "The D3D11 device was removed or reset during Present.");
		} else {
			ThrowIllegalState(env, "IDXGISwapChain::Present failed.");
		}
	}
	// DXGI_STATUS_OCCLUDED and similar non-error S_* codes are intentionally
	// not treated as failures here (FAILED() only trips on negative HRESULTs).
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseSurface0(JNIEnv*, jclass, jlong nativeSurfaceHandle) {
	Surface* surface = ToSurface(nativeSurfaceHandle);
	if (surface == nullptr) {
		return;
	}

	if (g_activeDevice == surface->device) {
		// See the g_activeDevice/g_activeContext comment in
		// directexp_common.h. Any texture created against this device that
		// outlives the surface is now orphaned (its handle stays valid
		// until releaseTexture0 is called on it, but no new textures can be
		// created until a new surface is bootstrapped) -- acceptable for
		// the current single-window assumption, since releasing the
		// surface means the game is shutting down or the backend is being
		// torn down anyway.
		g_activeDevice = nullptr;
		g_activeContext = nullptr;
	}

	ReleaseSurfaceObjects(surface);
	delete surface;
}

#else // !_WIN32

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_probeSurface0(JNIEnv*, jclass, jlong) {
	return nullptr;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bootstrapSurface0(JNIEnv*, jclass, jlong) {
	return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_configureSurface0(JNIEnv*, jclass, jlong, jint, jint, jboolean) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_acquireSurface0(JNIEnv*, jclass, jlong) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_blitSurfaceRgba80(JNIEnv*, jclass, jlong, jobject, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_presentSurface0(JNIEnv*, jclass, jlong) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseSurface0(JNIEnv*, jclass, jlong) {
}

#endif
