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
// records it so bindVertexBuffer0 can take the vertex stride from it (the
// stride is a property of the pipeline's input layout, and keeping it here
// avoids the client needing Blaze3D's vertex-size API). Pipelines call
// ForgetBoundPipeline before they are deleted; releaseSurface0 calls
// ResetDrawState.
//
// Geometry-shader pipelines (gl_PointSize point sprites): bindPipeline0 binds
// the pipeline's viewport constants at b13 and rewrites them when the viewport
// set by setRenderTargets0 changed, so Java never calls setPipelineViewport0
// itself.
//
// NOTE ON VERIFICATION: not compiled by the authoring agent (Linux sandbox,
// no Windows SDK); every call shape follows the D3D11 documentation. See the
// Milestone 4.6 source of truth for the actual build status.

#include "directexp_common.h"

#ifdef _WIN32

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

} // namespace

namespace directexp {

void ForgetBoundPipeline(const Pipeline* pipeline) {
	if (g_boundPipeline == pipeline) {
		g_boundPipeline = nullptr;
	}
}

void ResetDrawState() {
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
		buffer = wrapper->buffer;
		stride = g_boundPipeline->vertexStride;
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

#endif
