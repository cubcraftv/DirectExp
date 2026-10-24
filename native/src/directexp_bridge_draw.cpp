// DirectExp native bridge - draw path (Milestone 4.6, commit 4).
//
// Everything a render pass needs to put pixels on a target: binding render /
// depth targets, clears, viewport and scissor, binding a pipeline's objects,
// vertex/index/constant buffers, shader-resource views and samplers, and the
// Draw / DrawIndexedInstanced calls themselves. Resources and pipelines were
// created by the earlier commits (directexp_bridge_resource.cpp,
// directexp_bridge_pipeline.cpp); this file only binds and issues them.
//
// Everything runs on g_activeContext, the single immediate context (see the
// comment in directexp_common.h). State is sticky per D3D11 semantics: the
// Java side (DxRenderPassBackend) decides what to re-send; a handle of 0
// where a view/buffer/sampler is expected unbinds that slot.
//
// The one piece of native-side state is the "bound pipeline": bindPipeline0
// records it so bindVertexBuffer0 can take the stride for whichever slot it
// binds from the pipeline's per-slot stride table (the stride is a property
// of the pipeline's input layout, and keeping it here avoids the client
// needing Blaze3D's vertex-size API). Pipelines call ForgetBoundPipeline
// before they are deleted; releaseSurface0 calls ResetDrawState.
//
// Geometry-shader pipelines (gl_PointSize point sprites): bindPipeline0 binds
// the pipeline's viewport constants at b13 and rewrites them when the viewport
// set by setRenderTargets0 changed, so Java never calls setPipelineViewport0
// itself.
//
#include "directexp_common.h"

#ifdef _WIN32

#include <d3dcompiler.h>
#include <algorithm>
#include <cstring>
#include <string>

using namespace directexp;

// DxBridge.STAGE_VERTEX / STAGE_FRAGMENT.
static_assert(D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT == 14, "constant buffer slot count changed");

namespace {

constexpr jint STAGE_VERTEX = 1;
constexpr jint STAGE_FRAGMENT = 2;
constexpr jint GEOMETRY_VIEWPORT_CBUFFER_SLOT = 13; // DxBridge.GEOMETRY_VIEWPORT_CBUFFER_SLOT

const Pipeline* g_boundPipeline = nullptr;
UINT g_viewportWidth = 0;
UINT g_viewportHeight = 0;

bool RequireContext(JNIEnv* env, const char* caller) {
	if (g_activeContext == nullptr) {
		ThrowIllegalState(env, std::string(caller) + " called with no active DirectX device context.");
		return false;
	}
	return true;
}

bool RequireBoundPipeline(JNIEnv* env, const char* caller) {
	if (g_boundPipeline == nullptr) {
		ThrowIllegalState(env, std::string(caller) + " called before any pipeline was bound.");
		return false;
	}
	return true;
}

bool HasStencil(DXGI_FORMAT format) {
	return format == DXGI_FORMAT_D24_UNORM_S8_UINT || format == DXGI_FORMAT_D32_FLOAT_S8X24_UINT;
}

// Rectangle depth clear (Blaze3D's clearColorAndDepthTextures(..., x, y, w, h), used
// to reset one slot of the GUI item atlas). ClearDepthStencilView has no rectangle
// and ID3D11DeviceContext1::ClearView is D3D11.1, so this draws one depth-only
// fullscreen triangle under a scissor rect: DepthFunc ALWAYS, depth write on, no
// pixel shader, z taken from a constant buffer.
struct DepthClearResources {
	ID3D11Device* owner = nullptr;
	ID3D11VertexShader* vertexShader = nullptr;
	ID3D11DepthStencilState* depthState = nullptr;
	ID3D11RasterizerState* rasterizerState = nullptr;
	ID3D11Buffer* constants = nullptr;
};
DepthClearResources g_depthClear;

void ReleaseDepthClearResourcesImpl() {
	if (g_depthClear.vertexShader != nullptr) g_depthClear.vertexShader->Release();
	if (g_depthClear.depthState != nullptr) g_depthClear.depthState->Release();
	if (g_depthClear.rasterizerState != nullptr) g_depthClear.rasterizerState->Release();
	if (g_depthClear.constants != nullptr) g_depthClear.constants->Release();
	g_depthClear = DepthClearResources{};
}

bool EnsureDepthClearResources(JNIEnv* env) {
	if (g_depthClear.owner == g_activeDevice && g_depthClear.vertexShader != nullptr) {
		return true;
	}
	ReleaseDepthClearResourcesImpl();

	static const char kSource[] =
		"cbuffer ClearConstants : register(b0) { float clearDepth; float3 padding; };\n"
		"float4 main(uint id : SV_VertexID) : SV_Position {\n"
		"\tfloat2 uv = float2((id << 1) & 2, id & 2);\n"
		"\treturn float4(uv * float2(2.0, -2.0) + float2(-1.0, 1.0), clearDepth, 1.0);\n"
		"}\n";
	ID3DBlob* code = nullptr;
	ID3DBlob* errors = nullptr;
	HRESULT hr = D3DCompile(kSource, sizeof(kSource) - 1, "directexp_depth_clear_vs", nullptr, nullptr, "main", "vs_4_0", 0, 0, &code, &errors);
	if (FAILED(hr) || code == nullptr) {
		std::string message = "depth-rect clear: D3DCompile failed";
		if (errors != nullptr) {
			message += ": ";
			message.append(static_cast<const char*>(errors->GetBufferPointer()), errors->GetBufferSize());
			errors->Release();
		}
		ThrowIllegalState(env, message);
		return false;
	}
	if (errors != nullptr) errors->Release();
	hr = g_activeDevice->CreateVertexShader(code->GetBufferPointer(), code->GetBufferSize(), nullptr, &g_depthClear.vertexShader);
	code->Release();
	if (FAILED(hr)) {
		ReleaseDepthClearResourcesImpl();
		ThrowIllegalState(env, "depth-rect clear: CreateVertexShader failed.");
		return false;
	}

	D3D11_DEPTH_STENCIL_DESC depth = {};
	depth.DepthEnable = TRUE;
	depth.DepthWriteMask = D3D11_DEPTH_WRITE_MASK_ALL;
	depth.DepthFunc = D3D11_COMPARISON_ALWAYS;
	depth.StencilEnable = FALSE;
	D3D11_RASTERIZER_DESC raster = {};
	raster.FillMode = D3D11_FILL_SOLID;
	raster.CullMode = D3D11_CULL_NONE;
	raster.DepthClipEnable = FALSE;
	raster.ScissorEnable = TRUE;
	D3D11_BUFFER_DESC cb = {};
	cb.ByteWidth = 16;
	cb.Usage = D3D11_USAGE_DEFAULT;
	cb.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
	if (FAILED(g_activeDevice->CreateDepthStencilState(&depth, &g_depthClear.depthState))
		|| FAILED(g_activeDevice->CreateRasterizerState(&raster, &g_depthClear.rasterizerState))
		|| FAILED(g_activeDevice->CreateBuffer(&cb, nullptr, &g_depthClear.constants))) {
		ReleaseDepthClearResourcesImpl();
		ThrowIllegalState(env, "depth-rect clear: could not create fixed-function state.");
		return false;
	}
	g_depthClear.owner = g_activeDevice;
	return true;
}

} // namespace

namespace directexp {

void ForgetBoundPipeline(const Pipeline* pipeline) {
	if (g_boundPipeline == pipeline) {
		g_boundPipeline = nullptr;
	}
}

void ResetDrawState() {
	ReleaseDepthClearResourcesImpl();
	g_boundPipeline = nullptr;
	g_viewportWidth = 0;
	g_viewportHeight = 0;
}

} // namespace directexp

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_setRenderTargets0(
	JNIEnv* env, jclass, jlong colorViewHandle, jlong depthViewHandle, jint width, jint height
) {
	if (!RequireContext(env, "setRenderTargets0")) {
		return;
	}
	if (width <= 0 || height <= 0) {
		ThrowIllegalState(env, "setRenderTargets0 requires a positive target size.");
		return;
	}

	ID3D11RenderTargetView* renderTargetView = nullptr;
	ID3D11DepthStencilView* depthStencilView = nullptr;

	if (colorViewHandle != 0) {
		TextureView* color = HandleToPointer<TextureView>(colorViewHandle);
		if (color == nullptr || color->renderTargetView == nullptr) {
			ThrowIllegalState(env, "setRenderTargets0: the color view handle has no render-target view.");
			return;
		}
		renderTargetView = color->renderTargetView;
	}
	if (depthViewHandle != 0) {
		TextureView* depth = HandleToPointer<TextureView>(depthViewHandle);
		if (depth == nullptr || depth->depthStencilView == nullptr) {
			ThrowIllegalState(env, "setRenderTargets0: the depth view handle has no depth-stencil view.");
			return;
		}
		depthStencilView = depth->depthStencilView;
	}

	g_activeContext->OMSetRenderTargets(renderTargetView != nullptr ? 1 : 0, renderTargetView != nullptr ? &renderTargetView : nullptr, depthStencilView);

	D3D11_VIEWPORT viewport = {};
	viewport.TopLeftX = 0.0f;
	viewport.TopLeftY = 0.0f;
	viewport.Width = static_cast<float>(width);
	viewport.Height = static_cast<float>(height);
	viewport.MinDepth = 0.0f;
	viewport.MaxDepth = 1.0f;
	g_activeContext->RSSetViewports(1, &viewport);

	g_viewportWidth = static_cast<UINT>(width);
	g_viewportHeight = static_cast<UINT>(height);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearRenderTarget0(
	JNIEnv* env, jclass, jlong colorViewHandle, jfloat red, jfloat green, jfloat blue, jfloat alpha
) {
	if (!RequireContext(env, "clearRenderTarget0")) {
		return;
	}
	TextureView* color = HandleToPointer<TextureView>(colorViewHandle);
	if (color == nullptr || color->renderTargetView == nullptr) {
		ThrowIllegalState(env, "clearRenderTarget0: the view handle has no render-target view.");
		return;
	}
	const FLOAT components[4] = { red, green, blue, alpha };
	g_activeContext->ClearRenderTargetView(color->renderTargetView, components);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearDepthStencil0(JNIEnv* env, jclass, jlong depthViewHandle, jfloat depth) {
	if (!RequireContext(env, "clearDepthStencil0")) {
		return;
	}
	TextureView* view = HandleToPointer<TextureView>(depthViewHandle);
	if (view == nullptr || view->depthStencilView == nullptr) {
		ThrowIllegalState(env, "clearDepthStencil0: the view handle has no depth-stencil view.");
		return;
	}

	// Only ask to clear stencil when the format has one; D3D11 otherwise
	// complains under the debug layer.
	D3D11_DEPTH_STENCIL_VIEW_DESC desc = {};
	view->depthStencilView->GetDesc(&desc);
	UINT flags = D3D11_CLEAR_DEPTH;
	if (HasStencil(desc.Format)) {
		flags |= D3D11_CLEAR_STENCIL;
	}
	g_activeContext->ClearDepthStencilView(view->depthStencilView, flags, depth, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearDepthTexture0(JNIEnv* env, jclass, jlong nativeTextureHandle, jfloat depth) {
	if (!RequireContext(env, "clearDepthTexture0")) {
		return;
	}
	Texture* texture = HandleToPointer<Texture>(nativeTextureHandle);
	if (texture == nullptr || texture->texture == nullptr || (texture->bindFlags & D3D11_BIND_DEPTH_STENCIL) == 0) {
		ThrowIllegalState(env, "clearDepthTexture0: the handle is not a live depth-stencil texture.");
		return;
	}
	TextureFormatInfo info = {};
	if (!ResolveTextureFormat(texture->formatCode, info) || !info.depth) {
		ThrowIllegalState(env, "clearDepthTexture0: the texture has no depth format.");
		return;
	}

	D3D11_DEPTH_STENCIL_VIEW_DESC desc = {};
	desc.Format = info.targetView;
	desc.ViewDimension = D3D11_DSV_DIMENSION_TEXTURE2D;
	desc.Texture2D.MipSlice = 0;
	ID3D11DepthStencilView* transient = nullptr;
	if (FAILED(g_activeDevice->CreateDepthStencilView(texture->texture, &desc, &transient)) || transient == nullptr) {
		ThrowIllegalState(env, "clearDepthTexture0: ID3D11Device::CreateDepthStencilView failed.");
		return;
	}

	UINT flags = D3D11_CLEAR_DEPTH;
	if (HasStencil(info.targetView)) {
		flags |= D3D11_CLEAR_STENCIL;
	}
	g_activeContext->ClearDepthStencilView(transient, flags, depth, 0);
	transient->Release();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearDepthRegion0(
	JNIEnv* env, jclass, jlong nativeTextureHandle, jint x, jint y, jint width, jint height, jfloat depth
) {
	if (!RequireContext(env, "clearDepthRegion0")) {
		return;
	}
	Texture* texture = HandleToPointer<Texture>(nativeTextureHandle);
	if (texture == nullptr || texture->texture == nullptr || (texture->bindFlags & D3D11_BIND_DEPTH_STENCIL) == 0) {
		ThrowIllegalState(env, "clearDepthRegion0: the handle is not a live depth-stencil texture.");
		return;
	}
	TextureFormatInfo info = {};
	if (!ResolveTextureFormat(texture->formatCode, info) || !info.depth) {
		ThrowIllegalState(env, "clearDepthRegion0: the texture has no depth format.");
		return;
	}
	const LONG left = std::max<LONG>(0, x);
	const LONG top = std::max<LONG>(0, y);
	const LONG right = std::min<LONG>(static_cast<LONG>(texture->width), static_cast<LONG>(x) + static_cast<LONG>(width));
	const LONG bottom = std::min<LONG>(static_cast<LONG>(texture->height), static_cast<LONG>(y) + static_cast<LONG>(height));
	if (right <= left || bottom <= top) {
		return; // nothing to clear
	}
	if (!EnsureDepthClearResources(env)) {
		return;
	}

	D3D11_DEPTH_STENCIL_VIEW_DESC desc = {};
	desc.Format = info.targetView;
	desc.ViewDimension = D3D11_DSV_DIMENSION_TEXTURE2D;
	desc.Texture2D.MipSlice = 0;
	ID3D11DepthStencilView* transient = nullptr;
	if (FAILED(g_activeDevice->CreateDepthStencilView(texture->texture, &desc, &transient)) || transient == nullptr) {
		ThrowIllegalState(env, "clearDepthRegion0: ID3D11Device::CreateDepthStencilView failed.");
		return;
	}

	const float constants[4] = { std::max(0.0f, std::min(1.0f, depth)), 0.0f, 0.0f, 0.0f };
	ID3D11DeviceContext* context = g_activeContext;
	context->UpdateSubresource(g_depthClear.constants, 0, nullptr, constants, 0, 0);

	context->OMSetRenderTargets(0, nullptr, transient);
	D3D11_VIEWPORT viewport = {};
	viewport.Width = static_cast<float>(texture->width);
	viewport.Height = static_cast<float>(texture->height);
	viewport.MinDepth = 0.0f;
	viewport.MaxDepth = 1.0f;
	context->RSSetViewports(1, &viewport);
	const RECT scissor = { left, top, right, bottom };
	context->RSSetScissorRects(1, &scissor);
	context->RSSetState(g_depthClear.rasterizerState);
	context->OMSetDepthStencilState(g_depthClear.depthState, 0);
	const FLOAT blendFactor[4] = { 0.0f, 0.0f, 0.0f, 0.0f };
	context->OMSetBlendState(nullptr, blendFactor, 0xFFFFFFFFu);
	context->IASetInputLayout(nullptr);
	context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
	context->VSSetShader(g_depthClear.vertexShader, nullptr, 0);
	context->VSSetConstantBuffers(0, 1, &g_depthClear.constants);
	context->GSSetShader(nullptr, nullptr, 0);
	context->PSSetShader(nullptr, nullptr, 0);
	context->Draw(3, 0);

	// Leave nothing of this helper bound: the next render pass rebinds targets,
	// viewport, scissor, pipeline and buffers from scratch.
	context->OMSetRenderTargets(0, nullptr, nullptr);
	transient->Release();
	g_boundPipeline = nullptr;
	g_viewportWidth = static_cast<UINT>(texture->width);
	g_viewportHeight = static_cast<UINT>(texture->height);
}

// Samples a texture through controlled shaders and restores the immediate context afterward.

// Replays the just-issued draw into a scratch target, swapping only the pixel shader.

// Diagnostics: describes the pipeline state the D3D11 context currently holds.

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_setScissor0(JNIEnv* env, jclass, jint x, jint y, jint width, jint height) {
	if (!RequireContext(env, "setScissor0")) {
		return;
	}
	if (width < 0 || height < 0) {
		ThrowIllegalState(env, "setScissor0 requires a non-negative size.");
		return;
	}
	RECT rect = {};
	rect.left = static_cast<LONG>(x);
	rect.top = static_cast<LONG>(y);
	rect.right = static_cast<LONG>(x) + static_cast<LONG>(width);
	rect.bottom = static_cast<LONG>(y) + static_cast<LONG>(height);
	g_activeContext->RSSetScissorRects(1, &rect);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindPipeline0(JNIEnv* env, jclass, jlong nativePipelineHandle) {
	if (!RequireContext(env, "bindPipeline0")) {
		return;
	}
	Pipeline* pipeline = HandleToPointer<Pipeline>(nativePipelineHandle);
	if (pipeline == nullptr || pipeline->vertexShader == nullptr || pipeline->pixelShader == nullptr) {
		ThrowIllegalState(env, "bindPipeline0 called with an invalid or already-released pipeline handle.");
		return;
	}

	ID3D11DeviceContext* context = g_activeContext;
	context->IASetInputLayout(pipeline->inputLayout); // null for a pipeline with no vertex inputs
	context->IASetPrimitiveTopology(pipeline->topology);
	context->VSSetShader(pipeline->vertexShader, nullptr, 0);
	context->PSSetShader(pipeline->pixelShader, nullptr, 0);
	context->GSSetShader(pipeline->geometryShader, nullptr, 0); // null clears a previous pipeline's stage

	if (pipeline->geometryShader != nullptr && pipeline->geometryViewportBuffer != nullptr) {
		if (g_viewportWidth != 0 && (pipeline->geometryViewportWidth != g_viewportWidth || pipeline->geometryViewportHeight != g_viewportHeight)) {
			D3D11_MAPPED_SUBRESOURCE mapped = {};
			if (FAILED(context->Map(pipeline->geometryViewportBuffer, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
				ThrowIllegalState(env, "bindPipeline0: Map failed for the geometry shader viewport constants.");
				return;
			}
			float* values = static_cast<float*>(mapped.pData);
			values[0] = static_cast<float>(g_viewportWidth);
			values[1] = static_cast<float>(g_viewportHeight);
			values[2] = 0.0f;
			values[3] = 0.0f;
			context->Unmap(pipeline->geometryViewportBuffer, 0);
			pipeline->geometryViewportWidth = g_viewportWidth;
			pipeline->geometryViewportHeight = g_viewportHeight;
		}
		ID3D11Buffer* viewportBuffer = pipeline->geometryViewportBuffer;
		context->GSSetConstantBuffers(static_cast<UINT>(GEOMETRY_VIEWPORT_CBUFFER_SLOT), 1, &viewportBuffer);
	}

	const FLOAT blendFactor[4] = { 0.0f, 0.0f, 0.0f, 0.0f };
	context->OMSetBlendState(pipeline->blendState, blendFactor, 0xFFFFFFFFu);
	context->OMSetDepthStencilState(pipeline->depthStencilState, 0);
	context->RSSetState(pipeline->rasterizerState);

	g_boundPipeline = pipeline;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindVertexBuffer0(JNIEnv* env, jclass, jint slot, jlong nativeBufferHandle, jint byteOffset) {
	if (!RequireContext(env, "bindVertexBuffer0") || !RequireBoundPipeline(env, "bindVertexBuffer0")) {
		return;
	}
	if (slot < 0 || slot >= D3D11_IA_VERTEX_INPUT_RESOURCE_SLOT_COUNT || byteOffset < 0) {
		ThrowIllegalState(env, "bindVertexBuffer0: slot or offset out of range.");
		return;
	}

	ID3D11Buffer* buffer = nullptr;
	UINT stride = 0;
	UINT offset = static_cast<UINT>(byteOffset);
	if (nativeBufferHandle != 0) {
		Buffer* wrapper = HandleToPointer<Buffer>(nativeBufferHandle);
		if (wrapper == nullptr || wrapper->buffer == nullptr || (wrapper->bindFlags & D3D11_BIND_VERTEX_BUFFER) == 0) {
			ThrowIllegalState(env, "bindVertexBuffer0: the handle is not a live vertex buffer.");
			return;
		}
		if (slot >= Pipeline::MAX_VERTEX_BUFFER_SLOTS) {
			ThrowIllegalState(env, "bindVertexBuffer0: the slot is out of the pipeline's stride table range.");
			return;
		}
		stride = g_boundPipeline->vertexStrides[slot];
		if (stride == 0) {
			// The pipeline's input layout declares no binding for this slot;
			// binding the buffer anyway would mis-stride the fetch.
			ThrowIllegalState(env, "bindVertexBuffer0: the bound pipeline declares no vertex stride for that slot.");
			return;
		}
		buffer = wrapper->buffer;
	}
	g_activeContext->IASetVertexBuffers(static_cast<UINT>(slot), 1, &buffer, &stride, &offset);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindIndexBuffer0(JNIEnv* env, jclass, jlong nativeBufferHandle, jboolean wideIndices, jint byteOffset) {
	if (!RequireContext(env, "bindIndexBuffer0")) {
		return;
	}
	if (byteOffset < 0) {
		ThrowIllegalState(env, "bindIndexBuffer0: offset out of range.");
		return;
	}

	ID3D11Buffer* buffer = nullptr;
	if (nativeBufferHandle != 0) {
		Buffer* wrapper = HandleToPointer<Buffer>(nativeBufferHandle);
		if (wrapper == nullptr || wrapper->buffer == nullptr || (wrapper->bindFlags & D3D11_BIND_INDEX_BUFFER) == 0) {
			ThrowIllegalState(env, "bindIndexBuffer0: the handle is not a live index buffer.");
			return;
		}
		buffer = wrapper->buffer;
	}
	g_activeContext->IASetIndexBuffer(buffer, wideIndices ? DXGI_FORMAT_R32_UINT : DXGI_FORMAT_R16_UINT, static_cast<UINT>(byteOffset));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindConstantBuffer0(JNIEnv* env, jclass, jint slot, jlong nativeBufferHandle, jint stageMask) {
	if (!RequireContext(env, "bindConstantBuffer0")) {
		return;
	}
	if (slot < 0 || slot >= D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT) {
		ThrowIllegalState(env, "bindConstantBuffer0: slot out of range.");
		return;
	}

	ID3D11Buffer* buffer = nullptr;
	if (nativeBufferHandle != 0) {
		Buffer* wrapper = HandleToPointer<Buffer>(nativeBufferHandle);
		if (wrapper == nullptr || wrapper->buffer == nullptr || (wrapper->bindFlags & D3D11_BIND_CONSTANT_BUFFER) == 0) {
			ThrowIllegalState(env, "bindConstantBuffer0: the handle is not a live constant buffer.");
			return;
		}
		buffer = wrapper->buffer;
	}
	if ((stageMask & STAGE_VERTEX) != 0) {
		g_activeContext->VSSetConstantBuffers(static_cast<UINT>(slot), 1, &buffer);
	}
	if ((stageMask & STAGE_FRAGMENT) != 0) {
		g_activeContext->PSSetConstantBuffers(static_cast<UINT>(slot), 1, &buffer);
	}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindShaderResource0(JNIEnv* env, jclass, jint slot, jlong nativeTextureViewHandle, jint stageMask) {
	if (!RequireContext(env, "bindShaderResource0")) {
		return;
	}
	if (slot < 0 || slot >= D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT) {
		ThrowIllegalState(env, "bindShaderResource0: slot out of range.");
		return;
	}

	ID3D11ShaderResourceView* view = nullptr;
	if (nativeTextureViewHandle != 0) {
		TextureView* wrapper = HandleToPointer<TextureView>(nativeTextureViewHandle);
		if (wrapper == nullptr || wrapper->shaderView == nullptr) {
			ThrowIllegalState(env, "bindShaderResource0: the handle has no shader-resource view.");
			return;
		}
		view = wrapper->shaderView;
	}
	if ((stageMask & STAGE_VERTEX) != 0) {
		g_activeContext->VSSetShaderResources(static_cast<UINT>(slot), 1, &view);
	}
	if ((stageMask & STAGE_FRAGMENT) != 0) {
		g_activeContext->PSSetShaderResources(static_cast<UINT>(slot), 1, &view);
	}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindSampler0(JNIEnv* env, jclass, jint slot, jlong nativeSamplerHandle, jint stageMask) {
	if (!RequireContext(env, "bindSampler0")) {
		return;
	}
	if (slot < 0 || slot >= D3D11_COMMONSHADER_SAMPLER_SLOT_COUNT) {
		ThrowIllegalState(env, "bindSampler0: slot out of range.");
		return;
	}

	ID3D11SamplerState* state = nullptr;
	if (nativeSamplerHandle != 0) {
		Sampler* wrapper = HandleToPointer<Sampler>(nativeSamplerHandle);
		if (wrapper == nullptr || wrapper->state == nullptr) {
			ThrowIllegalState(env, "bindSampler0: the handle is not a live sampler.");
			return;
		}
		state = wrapper->state;
	}
	if ((stageMask & STAGE_VERTEX) != 0) {
		g_activeContext->VSSetSamplers(static_cast<UINT>(slot), 1, &state);
	}
	if ((stageMask & STAGE_FRAGMENT) != 0) {
		g_activeContext->PSSetSamplers(static_cast<UINT>(slot), 1, &state);
	}
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_draw0(JNIEnv* env, jclass, jint vertexCount, jint firstVertex) {
	if (!RequireContext(env, "draw0") || !RequireBoundPipeline(env, "draw0")) {
		return;
	}
	if (vertexCount < 0 || firstVertex < 0) {
		ThrowIllegalState(env, "draw0 requires non-negative counts.");
		return;
	}
	g_activeContext->Draw(static_cast<UINT>(vertexCount), static_cast<UINT>(firstVertex));
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_drawIndexed0(JNIEnv* env, jclass, jint indexCount, jint instanceCount, jint firstIndex, jint baseVertex) {
	if (!RequireContext(env, "drawIndexed0") || !RequireBoundPipeline(env, "drawIndexed0")) {
		return;
	}
	if (indexCount < 0 || instanceCount < 0 || firstIndex < 0) {
		ThrowIllegalState(env, "drawIndexed0 requires non-negative counts.");
		return;
	}
	// baseVertex may legitimately be negative in D3D11 (INT), though Blaze3D never sends one.
	g_activeContext->DrawIndexedInstanced(static_cast<UINT>(indexCount), static_cast<UINT>(instanceCount), static_cast<UINT>(firstIndex), static_cast<INT>(baseVertex), 0);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_multiDrawIndexed0(JNIEnv* env, jclass, jobject drawParameters, jint instanceCount, jint drawCount) {
	if (!RequireContext(env, "multiDrawIndexed0") || !RequireBoundPipeline(env, "multiDrawIndexed0")) {
		return;
	}
	if (instanceCount < 0 || drawCount < 0) {
		ThrowIllegalState(env, "multiDrawIndexed0 requires non-negative instance and draw counts.");
		return;
	}
	const jlong requiredElements = static_cast<jlong>(drawCount) * 3;
	const jlong capacity = env->GetDirectBufferCapacity(drawParameters);
	const jint* parameters = static_cast<const jint*>(env->GetDirectBufferAddress(drawParameters));
	if (parameters == nullptr || capacity < requiredElements) {
		ThrowIllegalState(env, "multiDrawIndexed0 requires a direct IntBuffer with three values per draw.");
		return;
	}

	// Validate the entire batch before issuing any draws, so malformed input
	// cannot leave a partially rendered region.
	for (jint draw = 0; draw < drawCount; ++draw) {
		const jint firstIndex = parameters[draw * 3];
		const jint indexCount = parameters[draw * 3 + 1];
		if (firstIndex < 0 || indexCount < 0) {
			ThrowIllegalState(env, "multiDrawIndexed0 requires non-negative index offsets and counts.");
			return;
		}
	}
	for (jint draw = 0; draw < drawCount; ++draw) {
		const jint firstIndex = parameters[draw * 3];
		const jint indexCount = parameters[draw * 3 + 1];
		const jint baseVertex = parameters[draw * 3 + 2];
		g_activeContext->DrawIndexedInstanced(
			static_cast<UINT>(indexCount),
			static_cast<UINT>(instanceCount),
			static_cast<UINT>(firstIndex),
			static_cast<INT>(baseVertex),
			0
		);
	}
}

#else // !_WIN32

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_setRenderTargets0(JNIEnv*, jclass, jlong, jlong, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearRenderTarget0(JNIEnv*, jclass, jlong, jfloat, jfloat, jfloat, jfloat) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearDepthStencil0(JNIEnv*, jclass, jlong, jfloat) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearDepthTexture0(JNIEnv*, jclass, jlong, jfloat) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearDepthRegion0(JNIEnv*, jclass, jlong, jint, jint, jint, jint, jfloat) {
}




extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_setScissor0(JNIEnv*, jclass, jint, jint, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindPipeline0(JNIEnv*, jclass, jlong) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindVertexBuffer0(JNIEnv*, jclass, jint, jlong, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindIndexBuffer0(JNIEnv*, jclass, jlong, jboolean, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindConstantBuffer0(JNIEnv*, jclass, jint, jlong, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindShaderResource0(JNIEnv*, jclass, jint, jlong, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_bindSampler0(JNIEnv*, jclass, jint, jlong, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_draw0(JNIEnv*, jclass, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_drawIndexed0(JNIEnv*, jclass, jint, jint, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_multiDrawIndexed0(JNIEnv*, jclass, jobject, jint, jint) {
}

#endif
