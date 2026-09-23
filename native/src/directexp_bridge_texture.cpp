// DirectExp native bridge - GPU texture lifecycle entrypoints (Milestone 3:
// Resource Core).
//
// Scope: real ID3D11Texture2D creation/upload/release, plus a GPU-to-GPU
// blit (CopyResource) into the swap chain back buffer, replacing the
// CPU-upload path (blitSurfaceRgba80 in directexp_bridge_surface.cpp) for
// anything Blaze3D marks as texture-backed. The CPU path stays in place and
// still runs for anything DxTexture couldn't get a native handle for (see
// DxTexture.createNativeTextureHandle's try/catch) -- this file doesn't
// replace it, it gives DxTexture something to succeed at getting a handle
// from in the first place.
//
// Device ownership: texture creation/upload here operates against
// g_activeDevice/g_activeContext (see the comment in directexp_common.h),
// not a device handle passed in per-call, because DxBridge's
// createTexture2D/uploadTextureRgba8 are deliberately surface-agnostic on
// the Java side. This assumes a single window/device for the whole
// process, consistent with the rest of this bridge and with AGENTS.md's
// Non-Goals (no multi-window support is a current project goal).
//
// nativeCommandContextHandle (blitSurfaceTexture0's first parameter) is
// intentionally unused: createCommandContext/submitCommandContext/
// releaseCommandContext are Java-only sentinel counters (see
// NativeDxBridge.java), not real native objects, because D3D11's single
// immediate-context model (the project's deliberate v1 choice -- see the
// design spec's Open Decisions on deferred contexts) means there is
// nothing per-"context" to actually look up here. If deferred contexts are
// ever adopted, this is the parameter that would start meaning something.
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

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createTexture2DNative(
	JNIEnv* env, jclass, jint width, jint height, jint mipLevels
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createTexture2DNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}

	if (width <= 0 || height <= 0 || mipLevels <= 0) {
		ThrowIllegalState(env, "createTexture2DNative requires positive width, height, and mipLevels.");
		return 0;
	}

	D3D11_TEXTURE2D_DESC desc = {};
	desc.Width = static_cast<UINT>(width);
	desc.Height = static_cast<UINT>(height);
	desc.MipLevels = static_cast<UINT>(mipLevels);
	desc.ArraySize = 1;
	desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
	desc.SampleDesc.Count = 1;
	desc.SampleDesc.Quality = 0;
	desc.Usage = D3D11_USAGE_DEFAULT;
	// SHADER_RESOURCE is not used by anything yet (Milestone 4 will bind it
	// for real pipelines) but costs nothing to declare now and avoids a
	// texture recreation later just to add the bind flag.
	desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
	desc.CPUAccessFlags = 0;
	desc.MiscFlags = 0;

	ID3D11Texture2D* texture = nullptr;
	HRESULT hr = g_activeDevice->CreateTexture2D(&desc, nullptr, &texture);
	if (FAILED(hr) || texture == nullptr) {
		ThrowIllegalState(env, "ID3D11Device::CreateTexture2D failed.");
		return 0;
	}

	Texture* wrapper = new Texture();
	wrapper->texture = texture;
	wrapper->width = static_cast<UINT>(width);
	wrapper->height = static_cast<UINT>(height);
	wrapper->mipLevels = static_cast<UINT>(mipLevels);

	return PointerToHandle(wrapper);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_uploadTextureRgba8Native(
	JNIEnv* env, jclass, jlong nativeTextureHandle, jint mipLevel, jobject rgbaPixels, jint width, jint height
) {
	Texture* wrapper = HandleToPointer<Texture>(nativeTextureHandle);
	if (wrapper == nullptr || wrapper->texture == nullptr) {
		ThrowIllegalState(env, "uploadTextureRgba8Native called with an invalid or already-released texture handle.");
		return;
	}

	if (g_activeContext == nullptr) {
		ThrowIllegalState(env, "uploadTextureRgba8Native called with no active DirectX context (no surface bootstrapped yet?).");
		return;
	}

	void* sourcePointer = env->GetDirectBufferAddress(rgbaPixels);
	if (sourcePointer == nullptr) {
		ThrowIllegalState(env, "uploadTextureRgba8Native requires a direct ByteBuffer.");
		return;
	}

	if (mipLevel < 0 || static_cast<UINT>(mipLevel) >= wrapper->mipLevels) {
		ThrowIllegalState(env, "uploadTextureRgba8Native mip level is out of range for this texture.");
		return;
	}

	UINT subresource = D3D11CalcSubresource(static_cast<UINT>(mipLevel), 0, wrapper->mipLevels);
	UINT sourceRowPitch = static_cast<UINT>(width) * 4;

	g_activeContext->UpdateSubresource(wrapper->texture, subresource, nullptr, sourcePointer, sourceRowPitch, 0);
	(void) height; // Not needed beyond validating the buffer size on the Java side; UpdateSubresource infers extent from the mip's own dimensions.
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseTexture0(JNIEnv*, jclass, jlong nativeTextureHandle) {
	Texture* wrapper = HandleToPointer<Texture>(nativeTextureHandle);
	if (wrapper == nullptr) {
		return;
	}

	if (wrapper->texture != nullptr) {
		wrapper->texture->Release();
	}
	delete wrapper;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_blitSurfaceTexture0(
	JNIEnv* env, jclass, jlong nativeCommandContextHandle, jlong nativeSurfaceHandle, jlong nativeTextureHandle
) {
	(void) nativeCommandContextHandle; // see file header comment

	Surface* surface = HandleToPointer<Surface>(nativeSurfaceHandle);
	if (surface == nullptr || surface->swapChain == nullptr || surface->context == nullptr) {
		ThrowIllegalState(env, "blitSurfaceTexture0 called with an invalid or already-released surface handle.");
		return;
	}

	Texture* wrapper = HandleToPointer<Texture>(nativeTextureHandle);
	if (wrapper == nullptr || wrapper->texture == nullptr) {
		ThrowIllegalState(env, "blitSurfaceTexture0 called with an invalid or already-released texture handle.");
		return;
	}

	ID3D11Texture2D* backBuffer = nullptr;
	HRESULT hr = surface->swapChain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(&backBuffer));
	if (FAILED(hr) || backBuffer == nullptr) {
		ThrowIllegalState(env, "Failed to acquire the swap chain back buffer for a texture blit.");
		return;
	}

	// NOTE: CopyResource requires matching resource dimensions and
	// compatible formats. If the source texture's mip 0 doesn't match the
	// back buffer's current size (e.g. a stale texture after a resize),
	// D3D11 skips the copy and emits a debug-layer warning rather than
	// crashing -- but the visible result is simply nothing being drawn
	// that frame. Revisit if this turns out to matter in practice; no
	// explicit dimension check is done here yet.
	surface->context->CopyResource(backBuffer, wrapper->texture);

	backBuffer->Release();
}

#else // !_WIN32

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createTexture2DNative(JNIEnv*, jclass, jint, jint, jint) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_uploadTextureRgba8Native(JNIEnv*, jclass, jlong, jint, jobject, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseTexture0(JNIEnv*, jclass, jlong) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_blitSurfaceTexture0(JNIEnv*, jclass, jlong, jlong, jlong) {
}

#endif
