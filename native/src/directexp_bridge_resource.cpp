// DirectExp native bridge - GPU buffer, sampler, texture (render/depth
// target capable), and texture-view lifecycle entrypoints (Milestone 4.6).
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
// JNI naming: Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_<method>
// mirrors the `native` declarations in NativeDxBridge.java.

#include "directexp_common.h"

#ifdef _WIN32

#include <algorithm>
#include <cstring>

using namespace directexp;

namespace {

// D3D11_REQ_CONSTANT_BUFFER_ELEMENT_COUNT (4096) 16-byte constants. Buffers
// larger than this need the D3D11.1 runtime (Windows 8+) and ranged binding
// (*SetConstantBuffers1); this commit deliberately does not depend on that,
// so createBufferNative rejects them and Java falls back to CPU-only.
constexpr UINT kMaxConstantBufferBytes = 4096u * 16u;

constexpr GUID kD3DDebugObjectName = {
	0x429b8c22, 0x9188, 0x4b0c, { 0x87, 0x42, 0xac, 0xb0, 0xbf, 0x85, 0xc2, 0x00 }
};

bool SetDebugObjectName(JNIEnv* env, ID3D11DeviceChild* object, const std::string& name, const char* objectType) {
	const UINT byteCount = static_cast<UINT>(name.size() + 1);
	HRESULT hr = object->SetPrivateData(kD3DDebugObjectName, byteCount, name.c_str());
	if (FAILED(hr)) {
		ThrowIllegalState(env, std::string("Could not assign the D3D11 debug name for ") + objectType + ".");
		return false;
	}
	return true;
}

// Texel-buffer element formats. The codes are DxTexelFormat.code() on the Java
// side (1..30); they are an ABI between the two and deliberately not DXGI
// numbers, like the TEXTURE_FORMAT_* / BUFFER_BIND_* codes.
struct TexelFormatInfo {
	DXGI_FORMAT format;
	UINT bytesPerElement;
};

bool ResolveTexelFormat(jint code, TexelFormatInfo& out) {
	switch (code) {
		case 1: out = { DXGI_FORMAT_R8_UNORM, 1 }; return true;
		case 2: out = { DXGI_FORMAT_R8_UINT, 1 }; return true;
		case 3: out = { DXGI_FORMAT_R8_SINT, 1 }; return true;
		case 4: out = { DXGI_FORMAT_R8G8_UNORM, 2 }; return true;
		case 5: out = { DXGI_FORMAT_R8G8_UINT, 2 }; return true;
		case 6: out = { DXGI_FORMAT_R8G8_SINT, 2 }; return true;
		case 7: out = { DXGI_FORMAT_R8G8B8A8_UNORM, 4 }; return true;
		case 8: out = { DXGI_FORMAT_R8G8B8A8_UINT, 4 }; return true;
		case 9: out = { DXGI_FORMAT_R8G8B8A8_SINT, 4 }; return true;
		case 10: out = { DXGI_FORMAT_R16_UINT, 2 }; return true;
		case 11: out = { DXGI_FORMAT_R16_SINT, 2 }; return true;
		case 12: out = { DXGI_FORMAT_R16_FLOAT, 2 }; return true;
		case 13: out = { DXGI_FORMAT_R16G16_UINT, 4 }; return true;
		case 14: out = { DXGI_FORMAT_R16G16_SINT, 4 }; return true;
		case 15: out = { DXGI_FORMAT_R16G16_FLOAT, 4 }; return true;
		case 16: out = { DXGI_FORMAT_R16G16B16A16_UINT, 8 }; return true;
		case 17: out = { DXGI_FORMAT_R16G16B16A16_SINT, 8 }; return true;
		case 18: out = { DXGI_FORMAT_R16G16B16A16_FLOAT, 8 }; return true;
		case 19: out = { DXGI_FORMAT_R32_UINT, 4 }; return true;
		case 20: out = { DXGI_FORMAT_R32_SINT, 4 }; return true;
		case 21: out = { DXGI_FORMAT_R32_FLOAT, 4 }; return true;
		case 22: out = { DXGI_FORMAT_R32G32_UINT, 8 }; return true;
		case 23: out = { DXGI_FORMAT_R32G32_SINT, 8 }; return true;
		case 24: out = { DXGI_FORMAT_R32G32_FLOAT, 8 }; return true;
		case 25: out = { DXGI_FORMAT_R32G32B32_UINT, 12 }; return true;
		case 26: out = { DXGI_FORMAT_R32G32B32_SINT, 12 }; return true;
		case 27: out = { DXGI_FORMAT_R32G32B32_FLOAT, 12 }; return true;
		case 28: out = { DXGI_FORMAT_R32G32B32A32_UINT, 16 }; return true;
		case 29: out = { DXGI_FORMAT_R32G32B32A32_SINT, 16 }; return true;
		case 30: out = { DXGI_FORMAT_R32G32B32A32_FLOAT, 16 }; return true;
		default: return false;
	}
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createBufferNative(
	JNIEnv* env, jclass, jint bindMask, jint byteWidth
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createBufferNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}

	const jint knownMask = BUFFER_BIND_VERTEX | BUFFER_BIND_INDEX | BUFFER_BIND_CONSTANT | BUFFER_BIND_SHADER_RESOURCE;
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
	if ((bindMask & BUFFER_BIND_SHADER_RESOURCE) != 0) {
		bindFlags |= D3D11_BIND_SHADER_RESOURCE;
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

// Writes [srcOffset, srcOffset + length) of a direct ByteBuffer to
// [dstOffset, dstOffset + length) of the GPU buffer. The Java side uses this for
// buffers that have no CPU mirror (large vertex/index arenas), so unlike
// updateBufferNative the source is just the written bytes.
extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_uploadBufferRangeNative(
	JNIEnv* env, jclass, jlong nativeBufferHandle, jint dstOffset, jobject source, jint srcOffset, jint length
) {
	Buffer* wrapper = HandleToPointer<Buffer>(nativeBufferHandle);
	if (wrapper == nullptr || wrapper->buffer == nullptr) {
		ThrowIllegalState(env, "uploadBufferRangeNative called with an invalid or already-released buffer handle.");
		return;
	}
	if (g_activeContext == nullptr) {
		ThrowIllegalState(env, "uploadBufferRangeNative called with no active DirectX context (no surface bootstrapped yet?).");
		return;
	}
	if ((wrapper->bindFlags & D3D11_BIND_CONSTANT_BUFFER) != 0) {
		// UpdateSubresource cannot partially update a constant buffer; those use updateBufferNative.
		ThrowIllegalState(env, "uploadBufferRangeNative does not support constant buffers.");
		return;
	}

	unsigned char* base = static_cast<unsigned char*>(env->GetDirectBufferAddress(source));
	if (base == nullptr) {
		ThrowIllegalState(env, "uploadBufferRangeNative requires a direct ByteBuffer.");
		return;
	}
	const jlong capacity = env->GetDirectBufferCapacity(source);
	if (dstOffset < 0 || srcOffset < 0 || length <= 0
		|| static_cast<jlong>(dstOffset) + length > static_cast<jlong>(wrapper->byteWidth)
		|| static_cast<jlong>(srcOffset) + length > capacity) {
		ThrowIllegalState(env, "uploadBufferRangeNative: range is outside the buffer or the source.");
		return;
	}

	D3D11_BOX box = {};
	box.left = static_cast<UINT>(dstOffset);
	box.right = static_cast<UINT>(dstOffset) + static_cast<UINT>(length);
	box.top = 0;
	box.bottom = 1;
	box.front = 0;
	box.back = 1;
	g_activeContext->UpdateSubresource(wrapper->buffer, 0, &box, base + srcOffset, 0, 0);
}

// Copies a range of the GPU buffer into a direct ByteBuffer through a transient
// STAGING buffer. Map(READ) waits for every queued GPU write to the source, so
// this is for the rare moments the CPU needs bytes of a GPU-only buffer
// (triangle-fan index expansion, read-mapping), not for per-frame use.
extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_readBufferNative(
	JNIEnv* env, jclass, jlong nativeBufferHandle, jint srcOffset, jobject destination, jint dstOffset, jint length
) {
	Buffer* wrapper = HandleToPointer<Buffer>(nativeBufferHandle);
	if (wrapper == nullptr || wrapper->buffer == nullptr) {
		ThrowIllegalState(env, "readBufferNative called with an invalid or already-released buffer handle.");
		return;
	}
	if (g_activeDevice == nullptr || g_activeContext == nullptr) {
		ThrowIllegalState(env, "readBufferNative called with no active DirectX device (no surface bootstrapped yet?).");
		return;
	}

	unsigned char* base = static_cast<unsigned char*>(env->GetDirectBufferAddress(destination));
	if (base == nullptr) {
		ThrowIllegalState(env, "readBufferNative requires a direct ByteBuffer.");
		return;
	}
	const jlong capacity = env->GetDirectBufferCapacity(destination);
	if (srcOffset < 0 || dstOffset < 0 || length <= 0
		|| static_cast<jlong>(srcOffset) + length > static_cast<jlong>(wrapper->byteWidth)
		|| static_cast<jlong>(dstOffset) + length > capacity) {
		ThrowIllegalState(env, "readBufferNative: range is outside the buffer or the destination.");
		return;
	}

	D3D11_BUFFER_DESC desc = {};
	desc.ByteWidth = static_cast<UINT>(length);
	desc.Usage = D3D11_USAGE_STAGING;
	desc.BindFlags = 0;
	desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
	ID3D11Buffer* staging = nullptr;
	if (FAILED(g_activeDevice->CreateBuffer(&desc, nullptr, &staging)) || staging == nullptr) {
		ThrowIllegalState(env, "readBufferNative: could not create the staging buffer (out of memory?).");
		return;
	}

	D3D11_BOX box = {};
	box.left = static_cast<UINT>(srcOffset);
	box.right = static_cast<UINT>(srcOffset) + static_cast<UINT>(length);
	box.top = 0;
	box.bottom = 1;
	box.front = 0;
	box.back = 1;
	g_activeContext->CopySubresourceRegion(staging, 0, 0, 0, 0, wrapper->buffer, 0, &box);

	D3D11_MAPPED_SUBRESOURCE mapped = {};
	if (FAILED(g_activeContext->Map(staging, 0, D3D11_MAP_READ, 0, &mapped))) {
		staging->Release();
		ThrowIllegalState(env, "readBufferNative: ID3D11DeviceContext::Map failed.");
		return;
	}
	memcpy(base + dstOffset, mapped.pData, static_cast<size_t>(length));
	g_activeContext->Unmap(staging, 0);
	staging->Release();
}

// GPU-side buffer-to-buffer copy. D3D11 forbids CopySubresourceRegion between
// overlapping ranges of one resource, so that case bounces through a temporary
// buffer; everything else is a single copy command.
extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_copyBufferNative(
	JNIEnv* env, jclass, jlong sourceHandle, jint srcOffset, jlong destinationHandle, jint dstOffset, jint length
) {
	Buffer* source = HandleToPointer<Buffer>(sourceHandle);
	Buffer* destination = HandleToPointer<Buffer>(destinationHandle);
	if (source == nullptr || source->buffer == nullptr || destination == nullptr || destination->buffer == nullptr) {
		ThrowIllegalState(env, "copyBufferNative called with an invalid or already-released buffer handle.");
		return;
	}
	if (g_activeDevice == nullptr || g_activeContext == nullptr) {
		ThrowIllegalState(env, "copyBufferNative called with no active DirectX device (no surface bootstrapped yet?).");
		return;
	}
	if (((source->bindFlags | destination->bindFlags) & D3D11_BIND_CONSTANT_BUFFER) != 0) {
		ThrowIllegalState(env, "copyBufferNative does not support constant buffers.");
		return;
	}
	if (srcOffset < 0 || dstOffset < 0 || length <= 0
		|| static_cast<jlong>(srcOffset) + length > static_cast<jlong>(source->byteWidth)
		|| static_cast<jlong>(dstOffset) + length > static_cast<jlong>(destination->byteWidth)) {
		ThrowIllegalState(env, "copyBufferNative: range is outside the source or destination buffer.");
		return;
	}

	D3D11_BOX box = {};
	box.left = static_cast<UINT>(srcOffset);
	box.right = static_cast<UINT>(srcOffset) + static_cast<UINT>(length);
	box.top = 0;
	box.bottom = 1;
	box.front = 0;
	box.back = 1;

	const bool sameResource = source->buffer == destination->buffer;
	const bool overlaps = sameResource
		&& static_cast<jlong>(srcOffset) < static_cast<jlong>(dstOffset) + length
		&& static_cast<jlong>(dstOffset) < static_cast<jlong>(srcOffset) + length;
	if (!overlaps) {
		g_activeContext->CopySubresourceRegion(destination->buffer, 0, static_cast<UINT>(dstOffset), 0, 0, source->buffer, 0, &box);
		return;
	}

	D3D11_BUFFER_DESC desc = {};
	desc.ByteWidth = static_cast<UINT>(length);
	desc.Usage = D3D11_USAGE_DEFAULT;
	desc.BindFlags = 0;
	ID3D11Buffer* temporary = nullptr;
	if (FAILED(g_activeDevice->CreateBuffer(&desc, nullptr, &temporary)) || temporary == nullptr) {
		ThrowIllegalState(env, "copyBufferNative: could not create the temporary buffer for an overlapping copy (out of memory?).");
		return;
	}
	g_activeContext->CopySubresourceRegion(temporary, 0, 0, 0, 0, source->buffer, 0, &box);
	g_activeContext->CopySubresourceRegion(destination->buffer, 0, static_cast<UINT>(dstOffset), 0, 0, temporary, 0, nullptr);
	temporary->Release();
}

// A typed-buffer SRV for texel buffers (GLSL samplerBuffer / isamplerBuffer, e.g.
// Sodium's u_SectionTimeInfo). The result reuses the TextureView wrapper with only
// shaderView filled in, so bindShaderResource0 and releaseTextureView0 handle it
// without a second code path; the view holds its own COM reference on the buffer.
extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createBufferShaderResourceViewNative(
	JNIEnv* env, jclass, jlong nativeBufferHandle, jint formatCode, jint firstElement, jint elementCount
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createBufferShaderResourceViewNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}
	Buffer* buffer = HandleToPointer<Buffer>(nativeBufferHandle);
	if (buffer == nullptr || buffer->buffer == nullptr) {
		ThrowIllegalState(env, "createBufferShaderResourceViewNative called with an invalid or already-released buffer handle.");
		return 0;
	}
	if ((buffer->bindFlags & D3D11_BIND_SHADER_RESOURCE) == 0) {
		ThrowIllegalState(env, "createBufferShaderResourceViewNative: the buffer was not created with BUFFER_BIND_SHADER_RESOURCE.");
		return 0;
	}

	TexelFormatInfo info = {};
	if (!ResolveTexelFormat(formatCode, info)) {
		ThrowIllegalState(env, "createBufferShaderResourceViewNative: unknown texel format code.");
		return 0;
	}
	if (firstElement < 0 || elementCount <= 0
		|| (static_cast<jlong>(firstElement) + elementCount) * static_cast<jlong>(info.bytesPerElement) > static_cast<jlong>(buffer->byteWidth)) {
		ThrowIllegalState(env, "createBufferShaderResourceViewNative: element range is outside the buffer.");
		return 0;
	}

	D3D11_SHADER_RESOURCE_VIEW_DESC desc = {};
	desc.Format = info.format;
	desc.ViewDimension = D3D11_SRV_DIMENSION_BUFFER;
	desc.Buffer.FirstElement = static_cast<UINT>(firstElement);
	desc.Buffer.NumElements = static_cast<UINT>(elementCount);

	ID3D11ShaderResourceView* view = nullptr;
	if (FAILED(g_activeDevice->CreateShaderResourceView(buffer->buffer, &desc, &view)) || view == nullptr) {
		ThrowIllegalState(env, "ID3D11Device::CreateShaderResourceView failed for a buffer (format not supported as a buffer SRV on this device?).");
		return 0;
	}
	const std::string name = "texel buffer SRV [format " + std::to_string(formatCode) + ", elements "
		+ std::to_string(firstElement) + ".." + std::to_string(static_cast<jlong>(firstElement) + elementCount - 1) + "]";
	if (!SetDebugObjectName(env, view, name, "buffer shader-resource view")) {
		view->Release();
		return 0;
	}

	TextureView* wrapper = new TextureView();
	wrapper->shaderView = view;
	return PointerToHandle(wrapper);
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

// Creates a texture of any supported TEXTURE_FORMAT_* with the requested
// TEXTURE_BIND_* purposes (Milestone 4.6: render/depth target support). The
// resource is typeless for depth formats so the same texture can be both a
// DSV and an SRV; see ResolveTextureFormat. Contents undefined until
// written/cleared. RGBA8 textures can be uploaded through
// uploadTextureRgba8Native when CPU-side content needs syncing.
extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createTextureNative(
	JNIEnv* env, jclass, jint formatCode, jint width, jint height, jint arrayLayers, jint mipLevels, jint bindMask, jboolean cubemap, jstring debugLabel
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createTextureNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}

	if (width <= 0 || height <= 0 || arrayLayers <= 0 || mipLevels <= 0) {
		ThrowIllegalState(env, "createTextureNative requires positive dimensions, arrayLayers, and mipLevels.");
		return 0;
	}
	if (debugLabel == nullptr) {
		ThrowIllegalState(env, "createTextureNative requires a non-null debug label.");
		return 0;
	}
	const char* labelChars = env->GetStringUTFChars(debugLabel, nullptr);
	if (labelChars == nullptr) {
		return 0;
	}
	std::string textureLabel(labelChars);
	env->ReleaseStringUTFChars(debugLabel, labelChars);

	TextureFormatInfo info = {};
	if (!ResolveTextureFormat(formatCode, info)) {
		ThrowIllegalState(env, "createTextureNative: unknown TEXTURE_FORMAT_* code.");
		return 0;
	}

	const jint knownMask = TEXTURE_BIND_SHADER_RESOURCE | TEXTURE_BIND_RENDER_TARGET | TEXTURE_BIND_DEPTH_STENCIL;
	if (bindMask <= 0 || (bindMask & ~knownMask) != 0) {
		ThrowIllegalState(env, "createTextureNative requires a non-empty bind mask made only of TEXTURE_BIND_* bits.");
		return 0;
	}

	if (((bindMask & TEXTURE_BIND_RENDER_TARGET) != 0 && info.depth)
		|| ((bindMask & TEXTURE_BIND_DEPTH_STENCIL) != 0 && !info.depth)) {
		ThrowIllegalState(env, "createTextureNative: RENDER_TARGET needs a color format and DEPTH_STENCIL a depth format.");
		return 0;
	}

	const bool isCubemap = cubemap == JNI_TRUE;
	if ((isCubemap && (arrayLayers != 6 || width != height || info.depth || bindMask != TEXTURE_BIND_SHADER_RESOURCE))
		|| (!isCubemap && arrayLayers != 1)) {
		ThrowIllegalState(env, "createTextureNative: only square six-layer RGBA shader-resource cubemaps and single-layer 2D textures are supported.");
		return 0;
	}

	UINT bindFlags = 0;
	if ((bindMask & TEXTURE_BIND_SHADER_RESOURCE) != 0) {
		bindFlags |= D3D11_BIND_SHADER_RESOURCE;
	}
	if ((bindMask & TEXTURE_BIND_RENDER_TARGET) != 0) {
		bindFlags |= D3D11_BIND_RENDER_TARGET;
	}
	if ((bindMask & TEXTURE_BIND_DEPTH_STENCIL) != 0) {
		bindFlags |= D3D11_BIND_DEPTH_STENCIL;
	}

	D3D11_TEXTURE2D_DESC desc = {};
	desc.Width = static_cast<UINT>(width);
	desc.Height = static_cast<UINT>(height);
	desc.MipLevels = static_cast<UINT>(mipLevels);
	desc.ArraySize = static_cast<UINT>(arrayLayers);
	desc.Format = info.resource;
	desc.SampleDesc.Count = 1;
	desc.SampleDesc.Quality = 0;
	desc.Usage = D3D11_USAGE_DEFAULT;
	desc.BindFlags = bindFlags;
	desc.CPUAccessFlags = 0;
	desc.MiscFlags = isCubemap ? D3D11_RESOURCE_MISC_TEXTURECUBE : 0;

	ID3D11Texture2D* texture = nullptr;
	HRESULT hr = g_activeDevice->CreateTexture2D(&desc, nullptr, &texture);
	if (FAILED(hr) || texture == nullptr) {
		ThrowIllegalState(env, "ID3D11Device::CreateTexture2D failed (unsupported format/bind-flag combination, or out of memory).");
		return 0;
	}
	const std::string nativeDebugName = textureLabel + " [" + std::to_string(width) + "x" + std::to_string(height)
		+ ", mips=" + std::to_string(mipLevels) + ", layers=" + std::to_string(arrayLayers) + "]";
	if (!SetDebugObjectName(env, texture, nativeDebugName, "texture")) {
		texture->Release();
		return 0;
	}

	Texture* wrapper = new Texture();
	wrapper->texture = texture;
	wrapper->width = static_cast<UINT>(width);
	wrapper->height = static_cast<UINT>(height);
	wrapper->mipLevels = static_cast<UINT>(mipLevels);
	wrapper->arrayLayers = static_cast<UINT>(arrayLayers);
	wrapper->cubemap = isCubemap;
	wrapper->debugLabel = textureLabel;
	wrapper->bindFlags = bindFlags;
	wrapper->formatCode = formatCode;
	return PointerToHandle(wrapper);
}

// Creates whichever views the texture's bind flags allow over the mip range:
// an SRV over [baseMipLevel, baseMipLevel + mipLevels), and an RTV or DSV on
// mip `baseMipLevel` alone (a render attachment is one subresource -- same
// as Vulkan, where the attachment is a view's base mip). A view object may
// legitimately hold only some of the three.
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

	TextureFormatInfo info = {};
	if (!ResolveTextureFormat(texture->formatCode, info)) {
		ThrowIllegalState(env, "createTextureViewNative: texture has an unknown format code.");
		return 0;
	}

	TextureView* wrapper = new TextureView();
	auto fail = [&](const char* message) -> jlong {
		if (wrapper->shaderView != nullptr) wrapper->shaderView->Release();
		if (wrapper->renderTargetView != nullptr) wrapper->renderTargetView->Release();
		if (wrapper->depthStencilView != nullptr) wrapper->depthStencilView->Release();
		delete wrapper;
		ThrowIllegalState(env, message);
		return 0;
	};

	if ((texture->bindFlags & D3D11_BIND_SHADER_RESOURCE) != 0) {
		D3D11_SHADER_RESOURCE_VIEW_DESC desc = {};
		desc.Format = info.shaderView;
		if (texture->cubemap) {
			desc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURECUBE;
			desc.TextureCube.MostDetailedMip = static_cast<UINT>(baseMipLevel);
			desc.TextureCube.MipLevels = static_cast<UINT>(mipLevels);
		} else {
			desc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
			desc.Texture2D.MostDetailedMip = static_cast<UINT>(baseMipLevel);
			desc.Texture2D.MipLevels = static_cast<UINT>(mipLevels);
		}
		if (FAILED(g_activeDevice->CreateShaderResourceView(texture->texture, &desc, &wrapper->shaderView))) {
			return fail("ID3D11Device::CreateShaderResourceView failed.");
		}
		const std::string name = texture->debugLabel + " [SRV mips " + std::to_string(baseMipLevel)
			+ ".." + std::to_string(baseMipLevel + mipLevels - 1) + "]";
		if (!SetDebugObjectName(env, wrapper->shaderView, name, "shader-resource view")) {
			wrapper->shaderView->Release();
			wrapper->shaderView = nullptr;
			return fail("Could not assign the D3D11 debug name for the shader-resource view.");
		}
	}

	if ((texture->bindFlags & D3D11_BIND_RENDER_TARGET) != 0) {
		D3D11_RENDER_TARGET_VIEW_DESC desc = {};
		desc.Format = info.targetView;
		desc.ViewDimension = D3D11_RTV_DIMENSION_TEXTURE2D;
		desc.Texture2D.MipSlice = static_cast<UINT>(baseMipLevel);
		if (FAILED(g_activeDevice->CreateRenderTargetView(texture->texture, &desc, &wrapper->renderTargetView))) {
			return fail("ID3D11Device::CreateRenderTargetView failed.");
		}
		const std::string name = texture->debugLabel + " [RTV mip " + std::to_string(baseMipLevel) + "]";
		if (!SetDebugObjectName(env, wrapper->renderTargetView, name, "render-target view")) {
			wrapper->renderTargetView->Release();
			wrapper->renderTargetView = nullptr;
			return fail("Could not assign the D3D11 debug name for the render-target view.");
		}
	}

	if ((texture->bindFlags & D3D11_BIND_DEPTH_STENCIL) != 0) {
		D3D11_DEPTH_STENCIL_VIEW_DESC desc = {};
		desc.Format = info.targetView;
		desc.ViewDimension = D3D11_DSV_DIMENSION_TEXTURE2D;
		desc.Flags = 0;
		desc.Texture2D.MipSlice = static_cast<UINT>(baseMipLevel);
		if (FAILED(g_activeDevice->CreateDepthStencilView(texture->texture, &desc, &wrapper->depthStencilView))) {
			return fail("ID3D11Device::CreateDepthStencilView failed.");
		}
		const std::string name = texture->debugLabel + " [DSV mip " + std::to_string(baseMipLevel) + "]";
		if (!SetDebugObjectName(env, wrapper->depthStencilView, name, "depth-stencil view")) {
			wrapper->depthStencilView->Release();
			wrapper->depthStencilView = nullptr;
			return fail("Could not assign the D3D11 debug name for the depth-stencil view.");
		}
	}

	return PointerToHandle(wrapper);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseTextureView0(JNIEnv*, jclass, jlong nativeTextureViewHandle) {
	TextureView* wrapper = HandleToPointer<TextureView>(nativeTextureViewHandle);
	if (wrapper == nullptr) {
		return;
	}

	if (wrapper->shaderView != nullptr) {
		wrapper->shaderView->Release();
	}
	if (wrapper->renderTargetView != nullptr) {
		wrapper->renderTargetView->Release();
	}
	if (wrapper->depthStencilView != nullptr) {
		wrapper->depthStencilView->Release();
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

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_uploadBufferRangeNative(JNIEnv*, jclass, jlong, jint, jobject, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_readBufferNative(JNIEnv*, jclass, jlong, jint, jobject, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_copyBufferNative(JNIEnv*, jclass, jlong, jint, jlong, jint, jint) {
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createBufferShaderResourceViewNative(JNIEnv*, jclass, jlong, jint, jint, jint) {
	return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createSamplerNative(JNIEnv*, jclass, jint, jint, jboolean, jboolean, jboolean, jint, jfloat) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseSampler0(JNIEnv*, jclass, jlong) {
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createTextureNative(JNIEnv*, jclass, jint, jint, jint, jint, jint, jint, jboolean, jstring) {
	return 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createTextureViewNative(JNIEnv*, jclass, jlong, jint, jint) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releaseTextureView0(JNIEnv*, jclass, jlong) {
}

#endif
