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

#include <cstring>

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
	wrapper->bindFlags = D3D11_BIND_SHADER_RESOURCE;
	wrapper->formatCode = TEXTURE_FORMAT_RGBA8_UNORM;

	return PointerToHandle(wrapper);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_uploadTextureRgba8Native(
	JNIEnv* env, jclass, jlong nativeTextureHandle, jint mipLevel, jint layer, jobject rgbaPixels, jint width, jint height
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

	if (mipLevel < 0 || static_cast<UINT>(mipLevel) >= wrapper->mipLevels) {
		ThrowIllegalState(env, "uploadTextureRgba8Native mip level is out of range for this texture.");
		return;
	}
	if (layer < 0 || static_cast<UINT>(layer) >= wrapper->arrayLayers) {
		ThrowIllegalState(env, "uploadTextureRgba8Native layer is out of range for this texture.");
		return;
	}

	UINT expectedWidth = wrapper->width;
	UINT expectedHeight = wrapper->height;
	for (jint level = 0; level < mipLevel; ++level) {
		expectedWidth = expectedWidth > 1 ? expectedWidth / 2 : 1;
		expectedHeight = expectedHeight > 1 ? expectedHeight / 2 : 1;
	}
	if (width <= 0 || height <= 0 || static_cast<UINT>(width) != expectedWidth || static_cast<UINT>(height) != expectedHeight) {
		ThrowIllegalState(env, "uploadTextureRgba8Native dimensions do not match the selected mip level.");
		return;
	}

	void* sourcePointer = env->GetDirectBufferAddress(rgbaPixels);
	const jlong capacity = env->GetDirectBufferCapacity(rgbaPixels);
	if (sourcePointer == nullptr || capacity < 0) {
		ThrowIllegalState(env, "uploadTextureRgba8Native requires a direct ByteBuffer.");
		return;
	}

	const jlong sourceRowPitch = static_cast<jlong>(width) * 4;
	const jlong layerBytes = sourceRowPitch * static_cast<jlong>(height);
	const jlong requiredBytes = layerBytes * static_cast<jlong>(wrapper->arrayLayers);
	if (capacity < requiredBytes) {
		ThrowIllegalState(env, "uploadTextureRgba8Native buffer is smaller than the texture's layered mip data.");
		return;
	}

	UINT subresource = D3D11CalcSubresource(static_cast<UINT>(mipLevel), static_cast<UINT>(layer), wrapper->mipLevels);
	const auto* layerData = static_cast<const unsigned char*>(sourcePointer) + layerBytes * static_cast<jlong>(layer);

	g_activeContext->UpdateSubresource(wrapper->texture, subresource, nullptr, layerData, static_cast<UINT>(sourceRowPitch), 0);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_uploadTextureRegionRgba8Native(
	JNIEnv* env, jclass, jlong nativeTextureHandle, jint mipLevel, jint layer, jobject rgbaPixels,
	jint mipWidth, jint mipHeight, jint x, jint y, jint width, jint height
) {
	Texture* wrapper = HandleToPointer<Texture>(nativeTextureHandle);
	if (wrapper == nullptr || wrapper->texture == nullptr) {
		ThrowIllegalState(env, "uploadTextureRegionRgba8Native called with an invalid or already-released texture handle.");
		return;
	}
	if (g_activeContext == nullptr) {
		ThrowIllegalState(env, "uploadTextureRegionRgba8Native called with no active DirectX context.");
		return;
	}
	if (mipLevel < 0 || static_cast<UINT>(mipLevel) >= wrapper->mipLevels || layer < 0 || static_cast<UINT>(layer) >= wrapper->arrayLayers) {
		ThrowIllegalState(env, "uploadTextureRegionRgba8Native mip level or layer is out of range.");
		return;
	}
	UINT expectedWidth = wrapper->width;
	UINT expectedHeight = wrapper->height;
	for (jint level = 0; level < mipLevel; ++level) {
		expectedWidth = expectedWidth > 1 ? expectedWidth / 2 : 1;
		expectedHeight = expectedHeight > 1 ? expectedHeight / 2 : 1;
	}
	if (mipWidth <= 0 || mipHeight <= 0 || static_cast<UINT>(mipWidth) != expectedWidth || static_cast<UINT>(mipHeight) != expectedHeight) {
		ThrowIllegalState(env, "uploadTextureRegionRgba8Native mip dimensions do not match the selected mip level.");
		return;
	}
	if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > mipWidth || y + height > mipHeight) {
		ThrowIllegalState(env, "uploadTextureRegionRgba8Native rectangle is outside the mip level.");
		return;
	}

	void* sourcePointer = env->GetDirectBufferAddress(rgbaPixels);
	const jlong capacity = env->GetDirectBufferCapacity(rgbaPixels);
	if (sourcePointer == nullptr || capacity < 0) {
		ThrowIllegalState(env, "uploadTextureRegionRgba8Native requires a direct ByteBuffer.");
		return;
	}

	const jlong rowPitch = static_cast<jlong>(mipWidth) * 4;
	const jlong layerBytes = rowPitch * static_cast<jlong>(mipHeight);
	const jlong requiredBytes = layerBytes * static_cast<jlong>(wrapper->arrayLayers);
	if (capacity < requiredBytes) {
		ThrowIllegalState(env, "uploadTextureRegionRgba8Native buffer is smaller than the texture's layered mip data.");
		return;
	}

	const auto* rectData = static_cast<const unsigned char*>(sourcePointer)
		+ layerBytes * static_cast<jlong>(layer)
		+ rowPitch * static_cast<jlong>(y)
		+ static_cast<jlong>(x) * 4;
	D3D11_BOX box = {};
	box.left = static_cast<UINT>(x);
	box.top = static_cast<UINT>(y);
	box.front = 0;
	box.right = static_cast<UINT>(x + width);
	box.bottom = static_cast<UINT>(y + height);
	box.back = 1;
	UINT subresource = D3D11CalcSubresource(static_cast<UINT>(mipLevel), static_cast<UINT>(layer), wrapper->mipLevels);
	g_activeContext->UpdateSubresource(wrapper->texture, subresource, &box, rectData, static_cast<UINT>(rowPitch), 0);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_readTexturePixelRgba8Native(JNIEnv* env, jclass, jlong nativeTextureHandle, jint mipLevel, jint x, jint y) {
	Texture* wrapper = HandleToPointer<Texture>(nativeTextureHandle);
	if (wrapper == nullptr || wrapper->texture == nullptr || g_activeDevice == nullptr || g_activeContext == nullptr) {
		ThrowIllegalState(env, "readTexturePixelRgba8Native: invalid texture handle or no active device.");
		return 0;
	}
	if (wrapper->formatCode != TEXTURE_FORMAT_RGBA8_UNORM || mipLevel < 0 || static_cast<UINT>(mipLevel) >= wrapper->mipLevels || x < 0 || y < 0) {
		ThrowIllegalState(env, "readTexturePixelRgba8Native: only in-range RGBA8 texels can be read.");
		return 0;
	}
	const UINT mipWidth = wrapper->width >> mipLevel > 0 ? wrapper->width >> mipLevel : 1;
	const UINT mipHeight = wrapper->height >> mipLevel > 0 ? wrapper->height >> mipLevel : 1;
	if (static_cast<UINT>(x) >= mipWidth || static_cast<UINT>(y) >= mipHeight) {
		ThrowIllegalState(env, "readTexturePixelRgba8Native: only in-range RGBA8 texels can be read.");
		return 0;
	}
	D3D11_TEXTURE2D_DESC desc = {};
	desc.Width = 1;
	desc.Height = 1;
	desc.MipLevels = 1;
	desc.ArraySize = 1;
	desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
	desc.SampleDesc.Count = 1;
	desc.Usage = D3D11_USAGE_STAGING;
	desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
	ID3D11Texture2D* staging = nullptr;
	if (FAILED(g_activeDevice->CreateTexture2D(&desc, nullptr, &staging)) || staging == nullptr) {
		ThrowIllegalState(env, "readTexturePixelRgba8Native: could not create the staging texture.");
		return 0;
	}
	D3D11_BOX box = { static_cast<UINT>(x), static_cast<UINT>(y), 0, static_cast<UINT>(x) + 1, static_cast<UINT>(y) + 1, 1 };
	g_activeContext->CopySubresourceRegion(staging, 0, 0, 0, 0, wrapper->texture, D3D11CalcSubresource(static_cast<UINT>(mipLevel), 0, wrapper->mipLevels), &box);
	D3D11_MAPPED_SUBRESOURCE mapped = {};
	jint result = 0;
	if (SUCCEEDED(g_activeContext->Map(staging, 0, D3D11_MAP_READ, 0, &mapped)) && mapped.pData != nullptr) {
		std::memcpy(&result, mapped.pData, sizeof(result));
		g_activeContext->Unmap(staging, 0);
	} else {
		ThrowIllegalState(env, "readTexturePixelRgba8Native: Map failed.");
	}
	staging->Release();
	return result;
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

	// Vulkan-orientation emulation: Blaze3D's render targets are stored
	// bottom-up (vertex shaders are Y-flipped to match, see EmitHlsl) and
	// VulkanGpuSurface.blitFromTexture flips them vertically on present
	// (dstOffsets y: height -> 0). CopyResource cannot flip, so copy one row
	// at a time into the mirrored destination row.
	//
	// Like CopyResource before it, a size mismatch (e.g. a stale texture
	// after a resize) copies nothing that frame rather than crashing.
	D3D11_TEXTURE2D_DESC sourceDesc = {};
	D3D11_TEXTURE2D_DESC backDesc = {};
	wrapper->texture->GetDesc(&sourceDesc);
	backBuffer->GetDesc(&backDesc);
	if (sourceDesc.Width == backDesc.Width && sourceDesc.Height == backDesc.Height) {
		const UINT height = sourceDesc.Height;
		for (UINT row = 0; row < height; ++row) {
			D3D11_BOX box = {};
			box.left = 0;
			box.right = sourceDesc.Width;
			box.top = row;
			box.bottom = row + 1;
			box.front = 0;
			box.back = 1;
			surface->context->CopySubresourceRegion(backBuffer, 0, 0, height - 1 - row, 0, wrapper->texture, 0, &box);
		}
	}

	backBuffer->Release();
}

#else // !_WIN32

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createTexture2DNative(JNIEnv*, jclass, jint, jint, jint) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_uploadTextureRgba8Native(JNIEnv*, jclass, jlong, jint, jint, jobject, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_uploadTextureRegionRgba8Native(JNIEnv*, jclass, jlong, jint, jint, jobject, jint, jint, jint, jint, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseTexture0(JNIEnv*, jclass, jlong) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_blitSurfaceTexture0(JNIEnv*, jclass, jlong, jlong, jlong) {
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_readTexturePixelRgba8Native(JNIEnv*, jclass, jlong, jint, jint, jint) {
	return 0;
}

#endif