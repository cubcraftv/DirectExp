// DirectExp native bridge - GPU buffer, sampler, and texture-view lifecycle
// entrypoints (Milestone 4.6 prerequisite scope).
//
// Scope: real ID3D11Buffer / ID3D11SamplerState / ID3D11ShaderResourceView
// creation, buffer upload, and release. Nothing here binds anything to the
// pipeline -- binding (IASetVertexBuffers, PSSetShaderResources, ...) is the
// draw-path commit's job. This file only gives DxBuffer / DxSampler /
// DxTextureView something real to hold.
//
// Authority model: the Java side (DxBuffer) keeps a CPU-side mirror that is
// always correct; the native buffer is a GPU copy of it, refreshed by
// updateBufferNative on every write (eager, whole-range-of-the-write). A
// missing native handle is a supported state, not an error -- see
// DxBuffer.createNativeBufferHandle's try/catch, same pattern as DxTexture.
//
// Device ownership: like directexp_bridge_texture.cpp, everything here works
// against g_activeDevice / g_activeContext (see directexp_common.h), i.e. one
// window/device per process.
//
// Threading: device methods (Create*) are free-threaded in D3D11, but
// updateBufferNative goes through the immediate context (UpdateSubresource),
// which is not. It is called from whichever thread writes a buffer; today
// that is the render thread, the same assumption directexp_bridge_texture.cpp
// already makes for uploadTextureRgba8Native.
//
// NOTE ON VERIFICATION: not compiled by the authoring agent (Linux sandbox,
// no MSVC/MinGW + Windows SDK). Every D3D11 call shape below was checked
// against Microsoft's documentation, but check docs/plans's Progress Log for
// the actual build status on a real Windows machine.
//
// JNI naming: Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_<method>
// mirrors the `native` declarations in NativeDxBridge.java.

#include "directexp_common.h"

#ifdef _WIN32

#include <algorithm>

using namespace directexp;

namespace {

// D3D11_REQ_CONSTANT_BUFFER_ELEMENT_COUNT (4096) 16-byte constants. Buffers
// larger than this need the D3D11.1 runtime (Windows 8+) and ranged binding
// (*SetConstantBuffers1); this commit deliberately does not depend on that,
// so createBufferNative rejects them and Java falls back to CPU-only.
constexpr UINT kMaxConstantBufferBytes = 4096u * 16u;

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createBufferNative(
	JNIEnv* env, jclass, jint bindMask, jint byteWidth
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createBufferNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}

	const jint knownMask = BUFFER_BIND_VERTEX | BUFFER_BIND_INDEX | BUFFER_BIND_CONSTANT;
	if (bindMask <= 0 || (bindMask & ~knownMask) != 0) {
		ThrowIllegalState(env, "createBufferNative requires a non-empty bind mask made only of BUFFER_BIND_* bits.");
		return 0;
	}

	if (byteWidth <= 0) {
		ThrowIllegalState(env, "createBufferNative requires a positive byteWidth.");
		return 0;
	}

	UINT bindFlags = 0;
	if ((bindMask & BUFFER_BIND_VERTEX) != 0) {
		bindFlags |= D3D11_BIND_VERTEX_BUFFER;
	}
	if ((bindMask & BUFFER_BIND_INDEX) != 0) {
		bindFlags |= D3D11_BIND_INDEX_BUFFER;
	}
	if ((bindMask & BUFFER_BIND_CONSTANT) != 0) {
		bindFlags |= D3D11_BIND_CONSTANT_BUFFER;
	}

	UINT width = static_cast<UINT>(byteWidth);
	if ((bindFlags & D3D11_BIND_CONSTANT_BUFFER) != 0) {
		// CreateBuffer: a constant buffer's ByteWidth must be a multiple of 16
		// and no larger than D3D11_REQ_CONSTANT_BUFFER_ELEMENT_COUNT constants
		// on a pre-11.1 runtime.
		width = (width + 15u) & ~15u;
		if (width > kMaxConstantBufferBytes) {
			ThrowIllegalState(env, "createBufferNative: constant buffer exceeds 64 KiB (needs the D3D11.1 ranged-binding path, not implemented).");
			return 0;
		}
	}

	D3D11_BUFFER_DESC desc = {};
	desc.ByteWidth = width;
	desc.Usage = D3D11_USAGE_DEFAULT; // updated via UpdateSubresource, never mapped
	desc.BindFlags = bindFlags;
	desc.CPUAccessFlags = 0;
	desc.MiscFlags = 0;
	desc.StructureByteStride = 0;

	ID3D11Buffer* buffer = nullptr;
	HRESULT hr = g_activeDevice->CreateBuffer(&desc, nullptr, &buffer);
	if (FAILED(hr) || buffer == nullptr) {
		ThrowIllegalState(env, "ID3D11Device::CreateBuffer failed (unsupported bind-flag combination, or out of memory).");
		return 0;
	}

	Buffer* wrapper = new Buffer();
	wrapper->buffer = buffer;
	wrapper->byteWidth = width;
	wrapper->bindFlags = bindFlags;
	return PointerToHandle(wrapper);
}

// Copies [offset, offset + length) of `mirror` (a direct ByteBuffer holding
// the full CPU-side copy, same layout as the GPU buffer) into the GPU buffer.
extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_updateBufferNative(
	JNIEnv* env, jclass, jlong nativeBufferHandle, jobject mirror, jint offset, jint length
) {
	Buffer* wrapper = HandleToPointer<Buffer>(nativeBufferHandle);
	if (wrapper == nullptr || wrapper->buffer == nullptr) {
		ThrowIllegalState(env, "updateBufferNative called with an invalid or already-released buffer handle.");
		return;
	}

	if (g_activeContext == nullptr) {
		ThrowIllegalState(env, "updateBufferNative called with no active DirectX context (no surface bootstrapped yet?).");
		return;
	}

	unsigned char* base = static_cast<unsigned char*>(env->GetDirectBufferAddress(mirror));
	if (base == nullptr) {
		ThrowIllegalState(env, "updateBufferNative requires a direct ByteBuffer.");
		return;
	}

	const jlong capacity = env->GetDirectBufferCapacity(mirror);

	if ((wrapper->bindFlags & D3D11_BIND_CONSTANT_BUFFER) != 0) {
		// UpdateSubresource: "For a shader-constant buffer; set pDstBox to
		// NULL. It is not possible to use this method to partially update a
		// shader-constant buffer." So the whole ByteWidth is re-sent; the
		// Java mirror is always padded to at least that size.
		if (capacity < static_cast<jlong>(wrapper->byteWidth)) {
			ThrowIllegalState(env, "updateBufferNative: mirror is smaller than the constant buffer's ByteWidth.");
			return;
		}
		g_activeContext->UpdateSubresource(wrapper->buffer, 0, nullptr, base, 0, 0);
		return;
	}

	if (offset < 0 || length <= 0
		|| static_cast<jlong>(offset) + static_cast<jlong>(length) > static_cast<jlong>(wrapper->byteWidth)
		|| static_cast<jlong>(offset) + static_cast<jlong>(length) > capacity) {
		ThrowIllegalState(env, "updateBufferNative: range is outside the buffer or the mirror.");
		return;
	}

	// For buffers the box coordinates are bytes: left/right carry the range,
	// top/bottom and front/back must span exactly one unit or the box is
	// "empty" and the call is a documented no-op.
	D3D11_BOX box = {};
	box.left = static_cast<UINT>(offset);
	box.right = static_cast<UINT>(offset) + static_cast<UINT>(length);
	box.top = 0;
	box.bottom = 1;
	box.front = 0;
	box.back = 1;

	// pSrcData points at the first byte of the *range* (not of the buffer).
	g_activeContext->UpdateSubresource(wrapper->buffer, 0, &box, base + offset, 0, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseBuffer0(JNIEnv*, jclass, jlong nativeBufferHandle) {
	Buffer* wrapper = HandleToPointer<Buffer>(nativeBufferHandle);
	if (wrapper == nullptr) {
		return;
	}

	if (wrapper->buffer != nullptr) {
		wrapper->buffer->Release();
	}
	delete wrapper;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createSamplerNative(
	JNIEnv* env, jclass,
	jint addressU, jint addressV,
	jboolean minLinear, jboolean magLinear, jboolean mipLinear,
	jint maxAnisotropy, jfloat maxLod
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createSamplerNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}

	auto toAddressMode = [](jint code, D3D11_TEXTURE_ADDRESS_MODE& out) {
		switch (code) {
			case SAMPLER_ADDRESS_WRAP: out = D3D11_TEXTURE_ADDRESS_WRAP; return true;
			case SAMPLER_ADDRESS_CLAMP: out = D3D11_TEXTURE_ADDRESS_CLAMP; return true;
			default: return false;
		}
	};

	D3D11_SAMPLER_DESC desc = {};
	if (!toAddressMode(addressU, desc.AddressU) || !toAddressMode(addressV, desc.AddressV)) {
		ThrowIllegalState(env, "createSamplerNative: unknown SAMPLER_ADDRESS_* code.");
		return 0;
	}
	desc.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP; // 2D textures only; W is unused

	// D3D11_FILTER's point/linear encoding is one bit per stage:
	// 0x10 = min linear, 0x04 = mag linear, 0x01 = mip linear (see the
	// D3D11_FILTER enumeration: MIN_MAG_MIP_POINT = 0, MIN_MAG_MIP_LINEAR
	// = 0x15, MIN_MAG_LINEAR_MIP_POINT = 0x14, ...).
	if (maxAnisotropy > 1) {
		desc.Filter = D3D11_FILTER_ANISOTROPIC;
	} else {
		const UINT bits = (minLinear ? 0x10u : 0u) | (magLinear ? 0x04u : 0u) | (mipLinear ? 0x01u : 0u);
		desc.Filter = static_cast<D3D11_FILTER>(bits);
	}

	desc.MipLODBias = 0.0f;
	// Valid MaxAnisotropy range is 1..16 (D3D11_SAMPLER_DESC).
	desc.MaxAnisotropy = static_cast<UINT>(std::min<jint>(16, std::max<jint>(1, maxAnisotropy)));
	desc.ComparisonFunc = D3D11_COMPARISON_NEVER;
	desc.BorderColor[0] = 0.0f;
	desc.BorderColor[1] = 0.0f;
	desc.BorderColor[2] = 0.0f;
	desc.BorderColor[3] = 0.0f;
	desc.MinLOD = 0.0f;
	desc.MaxLOD = std::max(0.0f, maxLod); // MaxLOD must be >= MinLOD

	ID3D11SamplerState* state = nullptr;
	HRESULT hr = g_activeDevice->CreateSamplerState(&desc, &state);
	if (FAILED(hr) || state == nullptr) {
		ThrowIllegalState(env, "ID3D11Device::CreateSamplerState failed.");
		return 0;
	}

	Sampler* wrapper = new Sampler();
	wrapper->state = state;
	return PointerToHandle(wrapper);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseSampler0(JNIEnv*, jclass, jlong nativeSamplerHandle) {
	Sampler* wrapper = HandleToPointer<Sampler>(nativeSamplerHandle);
	if (wrapper == nullptr) {
		return;
	}

	if (wrapper->state != nullptr) {
		wrapper->state->Release();
	}
	delete wrapper;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createTextureViewNative(
	JNIEnv* env, jclass, jlong nativeTextureHandle, jint baseMipLevel, jint mipLevels
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createTextureViewNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}

	Texture* texture = HandleToPointer<Texture>(nativeTextureHandle);
	if (texture == nullptr || texture->texture == nullptr) {
		ThrowIllegalState(env, "createTextureViewNative called with an invalid or already-released texture handle.");
		return 0;
	}

	if (baseMipLevel < 0 || mipLevels <= 0
		|| static_cast<jlong>(baseMipLevel) + static_cast<jlong>(mipLevels) > static_cast<jlong>(texture->mipLevels)) {
		ThrowIllegalState(env, "createTextureViewNative: mip range is outside the texture's mip chain.");
		return 0;
	}

	D3D11_SHADER_RESOURCE_VIEW_DESC desc = {};
	desc.Format = texture->format;
	desc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
	desc.Texture2D.MostDetailedMip = static_cast<UINT>(baseMipLevel);
	desc.Texture2D.MipLevels = static_cast<UINT>(mipLevels);

	ID3D11ShaderResourceView* view = nullptr;
	HRESULT hr = g_activeDevice->CreateShaderResourceView(texture->texture, &desc, &view);
	if (FAILED(hr) || view == nullptr) {
		ThrowIllegalState(env, "ID3D11Device::CreateShaderResourceView failed.");
		return 0;
	}

	TextureView* wrapper = new TextureView();
	wrapper->view = view;
	return PointerToHandle(wrapper);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseTextureView0(JNIEnv*, jclass, jlong nativeTextureViewHandle) {
	TextureView* wrapper = HandleToPointer<TextureView>(nativeTextureViewHandle);
	if (wrapper == nullptr) {
		return;
	}

	if (wrapper->view != nullptr) {
		wrapper->view->Release();
	}
	delete wrapper;
}

#else // !_WIN32

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createBufferNative(JNIEnv*, jclass, jint, jint) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_updateBufferNative(JNIEnv*, jclass, jlong, jobject, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseBuffer0(JNIEnv*, jclass, jlong) {
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createSamplerNative(JNIEnv*, jclass, jint, jint, jboolean, jboolean, jboolean, jint, jfloat) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseSampler0(JNIEnv*, jclass, jlong) {
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createTextureViewNative(JNIEnv*, jclass, jlong, jint, jint) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseTextureView0(JNIEnv*, jclass, jlong) {
}

#endif
