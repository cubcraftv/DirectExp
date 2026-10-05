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

#include <d3dcompiler.h>
#include <algorithm>
#include <cstring>
#include <cstdio>
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
extern "C" JNIEXPORT jstring JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_runSamplingExperiment0(JNIEnv* env, jclass, jlong textureViewHandle) {
	if (g_activeContext == nullptr || g_activeDevice == nullptr) {
		return env->NewStringUTF("no active device");
	}
	TextureView* viewWrapper = HandleToPointer<TextureView>(textureViewHandle);
	if (viewWrapper == nullptr || viewWrapper->shaderView == nullptr) {
		return env->NewStringUTF("no shader view");
	}
	D3D11_SHADER_RESOURCE_VIEW_DESC viewDesc = {};
	viewWrapper->shaderView->GetDesc(&viewDesc);
	if (viewDesc.ViewDimension != D3D11_SRV_DIMENSION_TEXTURE2D) {
		return env->NewStringUTF("sampling experiment requires a Texture2D shader view");
	}

	static const char kVertexSource[] =
		"struct O { float4 p : SV_Position; float2 uv : TEXCOORD0; };\n"
		"O main(uint id : SV_VertexID) { O o; float2 t = float2((id << 1) & 2, id & 2);\n"
		"o.p = float4(t * float2(2, -2) + float2(-1, 1), 0, 1); o.uv = float2(0.5, 0.5); return o; }\n";
	static const char* kPixelSources[] = {
		"Texture2D<float4> T : register(t0); SamplerState S : register(s0);\n"
		"float4 main(float4 p : SV_Position, float2 uv : TEXCOORD0) : SV_Target0 { return T.SampleLevel(S, uv, 0); }\n",
		"Texture2D<float4> T : register(t0); SamplerState S : register(s0);\n"
		"float4 main(float4 p : SV_Position, float2 uv : TEXCOORD0) : SV_Target0 { return T.SampleLevel(S, uv, 0); }\n",
		"Texture2D<float4> T : register(t0);\n"
		"float4 main(float4 p : SV_Position, float2 uv : TEXCOORD0) : SV_Target0 { uint w, h; T.GetDimensions(w, h); return T.Load(int3(w / 2, h / 2, 0)); }\n",
		"float4 main(float4 p : SV_Position, float2 uv : TEXCOORD0) : SV_Target0 { return float4(uv, 0, 1); }\n",
	};
	static const char* kNames[] = {
		"SampleLevel(ownPointClampSampler)",
		"SampleLevel(currentlyBoundSampler)",
		"Load(center)",
		"uvOutput"
	};

	auto compileShader = [](const char* source, const char* profile, ID3DBlob** bytecode, std::string& errorText) {
		ID3DBlob* errors = nullptr;
		HRESULT result = D3DCompile(source, std::strlen(source), "directexp_sampling_experiment", nullptr, nullptr, "main", profile, 0, 0, bytecode, &errors);
		if (errors != nullptr) {
			if (FAILED(result)) {
				errorText.assign(static_cast<const char*>(errors->GetBufferPointer()), errors->GetBufferSize());
			}
			errors->Release();
		}
		return SUCCEEDED(result) && *bytecode != nullptr;
	};

	ID3D11DeviceContext* context = g_activeContext;
	ID3D11Device* device = g_activeDevice;

	ID3D11RenderTargetView* savedRenderTargets[D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT] = {};
	ID3D11DepthStencilView* savedDepthTarget = nullptr;
	context->OMGetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, savedRenderTargets, &savedDepthTarget);

	D3D11_VIEWPORT savedViewports[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE] = {};
	UINT savedViewportCount = D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE;
	context->RSGetViewports(&savedViewportCount, savedViewports);
	RECT savedScissors[D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE] = {};
	UINT savedScissorCount = D3D11_VIEWPORT_AND_SCISSORRECT_OBJECT_COUNT_PER_PIPELINE;
	context->RSGetScissorRects(&savedScissorCount, savedScissors);

	ID3D11RasterizerState* savedRasterizer = nullptr;
	context->RSGetState(&savedRasterizer);
	ID3D11BlendState* savedBlend = nullptr;
	FLOAT savedBlendFactor[4] = {};
	UINT savedSampleMask = 0;
	context->OMGetBlendState(&savedBlend, savedBlendFactor, &savedSampleMask);
	ID3D11DepthStencilState* savedDepthState = nullptr;
	UINT savedStencilReference = 0;
	context->OMGetDepthStencilState(&savedDepthState, &savedStencilReference);
	ID3D11VertexShader* savedVertexShader = nullptr;
	context->VSGetShader(&savedVertexShader, nullptr, nullptr);
	ID3D11PixelShader* savedPixelShader = nullptr;
	context->PSGetShader(&savedPixelShader, nullptr, nullptr);
	ID3D11GeometryShader* savedGeometryShader = nullptr;
	context->GSGetShader(&savedGeometryShader, nullptr, nullptr);
	ID3D11InputLayout* savedInputLayout = nullptr;
	context->IAGetInputLayout(&savedInputLayout);
	D3D11_PRIMITIVE_TOPOLOGY savedTopology = D3D11_PRIMITIVE_TOPOLOGY_UNDEFINED;
	context->IAGetPrimitiveTopology(&savedTopology);
	ID3D11ShaderResourceView* savedResource = nullptr;
	context->PSGetShaderResources(0, 1, &savedResource);
	ID3D11SamplerState* savedSampler = nullptr;
	context->PSGetSamplers(0, 1, &savedSampler);
	ID3D11Predicate* savedPredicate = nullptr;
	BOOL savedPredicateValue = FALSE;
	context->GetPredication(&savedPredicate, &savedPredicateValue);

	std::string resultText;
	std::string compileError;
	ID3DBlob* vertexBytecode = nullptr;
	ID3D11VertexShader* vertexShader = nullptr;
	ID3D11SamplerState* pointSampler = nullptr;
	ID3D11RasterizerState* rasterizer = nullptr;
	if (!compileShader(kVertexSource, "vs_4_0", &vertexBytecode, compileError)) {
		resultText = "experiment VS compile failed: " + compileError;
	} else if (FAILED(device->CreateVertexShader(vertexBytecode->GetBufferPointer(), vertexBytecode->GetBufferSize(), nullptr, &vertexShader))) {
		resultText = "experiment VS creation failed";
	} else {
		D3D11_SAMPLER_DESC samplerDesc = {};
		samplerDesc.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
		samplerDesc.AddressU = D3D11_TEXTURE_ADDRESS_CLAMP;
		samplerDesc.AddressV = D3D11_TEXTURE_ADDRESS_CLAMP;
		samplerDesc.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
		samplerDesc.ComparisonFunc = D3D11_COMPARISON_NEVER;
		samplerDesc.MaxLOD = D3D11_FLOAT32_MAX;
		D3D11_RASTERIZER_DESC rasterizerDesc = {};
		rasterizerDesc.FillMode = D3D11_FILL_SOLID;
		rasterizerDesc.CullMode = D3D11_CULL_NONE;
		rasterizerDesc.DepthClipEnable = FALSE;

		if (FAILED(device->CreateSamplerState(&samplerDesc, &pointSampler))) {
			resultText = "experiment point sampler creation failed";
		} else if (FAILED(device->CreateRasterizerState(&rasterizerDesc, &rasterizer))) {
			resultText = "experiment rasterizer creation failed";
		} else {
			for (int variant = 0; variant < 4; ++variant) {
				ID3DBlob* pixelBytecode = nullptr;
				ID3D11PixelShader* pixelShader = nullptr;
				ID3D11Texture2D* target = nullptr;
				ID3D11RenderTargetView* targetView = nullptr;
				ID3D11Texture2D* staging = nullptr;
				compileError.clear();
				bool ready = compileShader(kPixelSources[variant], "ps_4_0", &pixelBytecode, compileError);
				if (ready) {
					ready = SUCCEEDED(device->CreatePixelShader(pixelBytecode->GetBufferPointer(), pixelBytecode->GetBufferSize(), nullptr, &pixelShader));
				}

				D3D11_TEXTURE2D_DESC textureDesc = {};
				textureDesc.Width = 1;
				textureDesc.Height = 1;
				textureDesc.MipLevels = 1;
				textureDesc.ArraySize = 1;
				textureDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
				textureDesc.SampleDesc.Count = 1;
				textureDesc.Usage = D3D11_USAGE_DEFAULT;
				textureDesc.BindFlags = D3D11_BIND_RENDER_TARGET;
				if (ready) {
					ready = SUCCEEDED(device->CreateTexture2D(&textureDesc, nullptr, &target));
				}
				if (ready) {
					ready = SUCCEEDED(device->CreateRenderTargetView(target, nullptr, &targetView));
				}

				textureDesc.Usage = D3D11_USAGE_STAGING;
				textureDesc.BindFlags = 0;
				textureDesc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
				if (ready) {
					ready = SUCCEEDED(device->CreateTexture2D(&textureDesc, nullptr, &staging));
				}
				if (ready) {
					const FLOAT clearColor[4] = { 1.0f, 0.0f, 1.0f, 0.0f };
					context->ClearRenderTargetView(targetView, clearColor);
					context->OMSetRenderTargets(1, &targetView, nullptr);
					const D3D11_VIEWPORT experimentViewport = { 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 1.0f };
					context->RSSetViewports(1, &experimentViewport);
					context->RSSetState(rasterizer);
					context->OMSetBlendState(nullptr, savedBlendFactor, 0xFFFFFFFFu);
					context->OMSetDepthStencilState(nullptr, 0);
					context->SetPredication(nullptr, FALSE);
					context->IASetInputLayout(nullptr);
					context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
					context->VSSetShader(vertexShader, nullptr, 0);
					context->GSSetShader(nullptr, nullptr, 0);
					context->PSSetShader(pixelShader, nullptr, 0);
					ID3D11ShaderResourceView* sourceView = viewWrapper->shaderView;
					context->PSSetShaderResources(0, 1, &sourceView);
					ID3D11SamplerState* selectedSampler = variant == 1 ? savedSampler : pointSampler;
					context->PSSetSamplers(0, 1, &selectedSampler);
					context->Draw(3, 0);
					context->OMSetRenderTargets(0, nullptr, nullptr);
					context->CopyResource(staging, target);

					D3D11_MAPPED_SUBRESOURCE mapped = {};
					if (SUCCEEDED(context->Map(staging, 0, D3D11_MAP_READ, 0, &mapped)) && mapped.pData != nullptr) {
						const unsigned char* rgba = static_cast<const unsigned char*>(mapped.pData);
						char pixelText[64];
						std::snprintf(pixelText, sizeof(pixelText), "%s=%02x%02x%02x%02x; ", kNames[variant], rgba[0], rgba[1], rgba[2], rgba[3]);
						resultText += pixelText;
						context->Unmap(staging, 0);
					} else {
						resultText += kNames[variant];
						resultText += "=map-failed; ";
					}
				} else {
					resultText += kNames[variant];
					resultText += compileError.empty() ? "=setup-failed; " : ("=compile-failed: " + compileError + "; ");
				}

				if (staging != nullptr) staging->Release();
				if (targetView != nullptr) targetView->Release();
				if (target != nullptr) target->Release();
				if (pixelShader != nullptr) pixelShader->Release();
				if (pixelBytecode != nullptr) pixelBytecode->Release();
			}
		}
	}

	if (rasterizer != nullptr) rasterizer->Release();
	if (pointSampler != nullptr) pointSampler->Release();
	if (vertexShader != nullptr) vertexShader->Release();
	if (vertexBytecode != nullptr) vertexBytecode->Release();

	context->PSSetSamplers(0, 1, &savedSampler);
	context->PSSetShaderResources(0, 1, &savedResource);
	context->IASetPrimitiveTopology(savedTopology);
	context->IASetInputLayout(savedInputLayout);
	context->GSSetShader(savedGeometryShader, nullptr, 0);
	context->PSSetShader(savedPixelShader, nullptr, 0);
	context->VSSetShader(savedVertexShader, nullptr, 0);
	context->OMSetDepthStencilState(savedDepthState, savedStencilReference);
	context->OMSetBlendState(savedBlend, savedBlendFactor, savedSampleMask);
	context->SetPredication(savedPredicate, savedPredicateValue);
	context->RSSetState(savedRasterizer);
	context->RSSetScissorRects(savedScissorCount, savedScissors);
	context->RSSetViewports(savedViewportCount, savedViewports);
	context->OMSetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, savedRenderTargets, savedDepthTarget);

	for (ID3D11RenderTargetView* savedTarget : savedRenderTargets) {
		if (savedTarget != nullptr) savedTarget->Release();
	}
	if (savedDepthTarget != nullptr) savedDepthTarget->Release();
	if (savedRasterizer != nullptr) savedRasterizer->Release();
	if (savedBlend != nullptr) savedBlend->Release();
	if (savedDepthState != nullptr) savedDepthState->Release();
	if (savedVertexShader != nullptr) savedVertexShader->Release();
	if (savedPixelShader != nullptr) savedPixelShader->Release();
	if (savedGeometryShader != nullptr) savedGeometryShader->Release();
	if (savedInputLayout != nullptr) savedInputLayout->Release();
	if (savedResource != nullptr) savedResource->Release();
	if (savedSampler != nullptr) savedSampler->Release();
	if (savedPredicate != nullptr) savedPredicate->Release();

	return env->NewStringUTF(resultText.c_str());
}

// Replays the just-issued draw into a scratch target, swapping only the pixel shader.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_runPixelSubstitutionProbe0(
	JNIEnv* env, jclass, jint probeX, jint probeY, jint vertexCount, jint firstVertex
) {
	if (g_activeContext == nullptr || g_activeDevice == nullptr) {
		return env->NewStringUTF("no active device");
	}
	if (probeX < 0 || probeY < 0 || vertexCount <= 0 || firstVertex < 0) {
		return env->NewStringUTF("bad probe arguments");
	}

	ID3D11DeviceContext* context = g_activeContext;
	ID3D11Device* device = g_activeDevice;

	D3D11_VIEWPORT viewport = {};
	UINT viewportCount = 1;
	context->RSGetViewports(&viewportCount, &viewport);
	if (viewportCount == 0 || viewport.TopLeftX != 0.0f || viewport.TopLeftY != 0.0f) {
		return env->NewStringUTF("viewport is not origin-anchored; probe skipped");
	}
	const UINT width = static_cast<UINT>(viewport.Width + 0.5f);
	const UINT height = static_cast<UINT>(viewport.Height + 0.5f);
	if (width == 0 || height == 0 || static_cast<UINT>(probeX) >= width || static_cast<UINT>(probeY) >= height) {
		return env->NewStringUTF("probe texel is outside the viewport; probe skipped");
	}

	const bool shaderModel5 = g_activeFeatureLevel >= D3D_FEATURE_LEVEL_11_0;
	const char* profile = shaderModel5 ? "ps_5_0" : "ps_4_1";
	struct Variant {
		const char* name;
		const char* source;
	};
	#define DX_PROBE_DECL "Texture2D<float4> Sprite : register(t0);\n SamplerState S : register(s0);\n"
	static const Variant kVariants[] = {
		{ "realPS", nullptr },
		{ "uvOnly(TEXCOORD1)",
			"float4 main(float2 uv : TEXCOORD1) : SV_Target0 { return float4(uv, 0, 1); }\n" },
		{ "uvFullSignature",
			"float4 main(float4 p : SV_Position, float a : TEXCOORD0, float2 uv : TEXCOORD1) : SV_Target0 { return float4(uv, 0, 1); }\n" },
		{ "fixedUvSample",
			DX_PROBE_DECL "float4 main(float2 uv : TEXCOORD1) : SV_Target0 { return Sprite.SampleLevel(S, float2(0.5, 0.5), 0); }\n" },
		{ "sampleTexcoord1Mip0",
			DX_PROBE_DECL "float4 main(float2 uv : TEXCOORD1) : SV_Target0 { return Sprite.SampleLevel(S, uv, 0); }\n" },
		{ "sampleFullSignature",
			DX_PROBE_DECL "float4 main(float4 p : SV_Position, float a : TEXCOORD0, float2 uv : TEXCOORD1) : SV_Target0 { return Sprite.SampleLevel(S, uv, 0); }\n" },
		{ "sampleExactVertexSignatureStruct",
			DX_PROBE_DECL
			"struct In { float a : TEXCOORD0; float2 uv : TEXCOORD1; float4 p : SV_Position; };\n"
			"float4 main(In i) : SV_Target0 { return Sprite.SampleLevel(S, i.uv, 0); }\n" },
		{ "dumpedHlslCopy",
			"cbuffer SpriteAnimationInfo : register(b0)\n"
			"{\n"
			"    row_major float4x4 _24_ProjectionMatrix : packoffset(c0);\n"
			"    row_major float4x4 _24_SpriteMatrix : packoffset(c4);\n"
			"    float _24_UPadding : packoffset(c8);\n"
			"    float _24_VPadding : packoffset(c8.y);\n"
			"    int _24_MipMapLevel : packoffset(c8.z);\n"
			"};\n"
			"Texture2D<float4> Sprite : register(t0);\n"
			"SamplerState _Sprite_sampler : register(s0);\n"
			"static float2 texCoord0;\n"
			"static float4 fragColor;\n"
			"struct SPIRV_Cross_Input { float2 texCoord0 : TEXCOORD1; };\n"
			"struct SPIRV_Cross_Output { float4 fragColor : SV_Target0; };\n"
			"void frag_main()\n"
			"{\n"
			"    float4 color = Sprite.SampleLevel(_Sprite_sampler, texCoord0, float(_24_MipMapLevel));\n"
			"    fragColor = color;\n"
			"}\n"
			"SPIRV_Cross_Output main(SPIRV_Cross_Input stage_input)\n"
			"{\n"
			"    texCoord0 = stage_input.texCoord0;\n"
			"    frag_main();\n"
			"    SPIRV_Cross_Output stage_output;\n"
			"    stage_output.fragColor = fragColor;\n"
			"    return stage_output;\n"
			"}\n" },
	};
	#undef DX_PROBE_DECL

	ID3D11RenderTargetView* savedRenderTargets[D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT] = {};
	ID3D11DepthStencilView* savedDepthTarget = nullptr;
	context->OMGetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, savedRenderTargets, &savedDepthTarget);
	ID3D11PixelShader* savedPixelShader = nullptr;
	context->PSGetShader(&savedPixelShader, nullptr, nullptr);
	ID3D11Predicate* savedPredicate = nullptr;
	BOOL savedPredicateValue = FALSE;
	context->GetPredication(&savedPredicate, &savedPredicateValue);

	std::string resultText;
	ID3D11Texture2D* target = nullptr;
	ID3D11RenderTargetView* targetView = nullptr;
	ID3D11Texture2D* staging = nullptr;
	D3D11_TEXTURE2D_DESC textureDesc = {};
	textureDesc.Width = width;
	textureDesc.Height = height;
	textureDesc.MipLevels = 1;
	textureDesc.ArraySize = 1;
	textureDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
	textureDesc.SampleDesc.Count = 1;
	textureDesc.Usage = D3D11_USAGE_DEFAULT;
	textureDesc.BindFlags = D3D11_BIND_RENDER_TARGET;
	bool ready = SUCCEEDED(device->CreateTexture2D(&textureDesc, nullptr, &target))
		&& SUCCEEDED(device->CreateRenderTargetView(target, nullptr, &targetView));
	if (ready) {
		D3D11_TEXTURE2D_DESC stagingDesc = textureDesc;
		stagingDesc.Width = 1;
		stagingDesc.Height = 1;
		stagingDesc.Usage = D3D11_USAGE_STAGING;
		stagingDesc.BindFlags = 0;
		stagingDesc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
		ready = SUCCEEDED(device->CreateTexture2D(&stagingDesc, nullptr, &staging));
	}
	if (!ready) {
		resultText = "scratch target / staging creation failed";
	}

	for (const Variant& variant : kVariants) {
		if (!ready) {
			break;
		}
		ID3DBlob* bytecode = nullptr;
		ID3D11PixelShader* pixelShader = nullptr;
		const bool useSaved = variant.source == nullptr;
		std::string failure;
		if (useSaved) {
			if (savedPixelShader == nullptr) {
				failure = "no bound pixel shader";
			}
		} else {
			ID3DBlob* errors = nullptr;
			HRESULT compiled = D3DCompile(variant.source, std::strlen(variant.source), "directexp_pixel_substitution", nullptr, nullptr, "main", profile, 0, 0, &bytecode, &errors);
			if (FAILED(compiled) || bytecode == nullptr) {
				failure = "compile failed";
				if (errors != nullptr) {
					failure += ": ";
					failure.append(static_cast<const char*>(errors->GetBufferPointer()), errors->GetBufferSize());
				}
			} else if (FAILED(device->CreatePixelShader(bytecode->GetBufferPointer(), bytecode->GetBufferSize(), nullptr, &pixelShader))) {
				failure = "CreatePixelShader failed";
			}
			if (errors != nullptr) errors->Release();
		}

		if (!failure.empty()) {
			resultText += variant.name;
			resultText += "=" + failure + "; ";
		} else {
			const FLOAT clearColor[4] = { 1.0f, 0.0f, 1.0f, 0.0f };
			context->ClearRenderTargetView(targetView, clearColor);
			context->OMSetRenderTargets(1, &targetView, nullptr);
			context->PSSetShader(useSaved ? savedPixelShader : pixelShader, nullptr, 0);
			context->SetPredication(nullptr, FALSE);
			context->Draw(static_cast<UINT>(vertexCount), static_cast<UINT>(firstVertex));
			context->OMSetRenderTargets(0, nullptr, nullptr);

			const D3D11_BOX box = {
				static_cast<UINT>(probeX), static_cast<UINT>(probeY), 0,
				static_cast<UINT>(probeX) + 1, static_cast<UINT>(probeY) + 1, 1
			};
			context->CopySubresourceRegion(staging, 0, 0, 0, 0, target, 0, &box);
			D3D11_MAPPED_SUBRESOURCE mapped = {};
			char pixelText[96];
			if (SUCCEEDED(context->Map(staging, 0, D3D11_MAP_READ, 0, &mapped)) && mapped.pData != nullptr) {
				const unsigned char* rgba = static_cast<const unsigned char*>(mapped.pData);
				std::snprintf(pixelText, sizeof(pixelText), "%s=%02x%02x%02x%02x; ", variant.name, rgba[0], rgba[1], rgba[2], rgba[3]);
				context->Unmap(staging, 0);
			} else {
				std::snprintf(pixelText, sizeof(pixelText), "%s=map-failed; ", variant.name);
			}
			resultText += pixelText;
		}

		if (pixelShader != nullptr) pixelShader->Release();
		if (bytecode != nullptr) bytecode->Release();
	}

	context->PSSetShader(savedPixelShader, nullptr, 0);
	context->SetPredication(savedPredicate, savedPredicateValue);
	context->OMSetRenderTargets(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT, savedRenderTargets, savedDepthTarget);
	if (staging != nullptr) staging->Release();
	if (targetView != nullptr) targetView->Release();
	if (target != nullptr) target->Release();
	for (ID3D11RenderTargetView* savedTarget : savedRenderTargets) {
		if (savedTarget != nullptr) savedTarget->Release();
	}
	if (savedDepthTarget != nullptr) savedDepthTarget->Release();
	if (savedPixelShader != nullptr) savedPixelShader->Release();
	if (savedPredicate != nullptr) savedPredicate->Release();

	return env->NewStringUTF(resultText.c_str());
}

// Diagnostics: describes the pipeline state the D3D11 context currently holds.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_describeDrawState0(JNIEnv* env, jclass, jint srvSlot, jlong expectedTextureHandle) {
	if (g_activeContext == nullptr) {
		return env->NewStringUTF("no active context");
	}
	if (srvSlot < 0 || srvSlot >= D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT) {
		ThrowIllegalState(env, "describeDrawState0: SRV slot out of range.");
		return nullptr;
	}
	ID3D11DeviceContext* ctx = g_activeContext;
	char buffer[2400];
	std::string out;
	auto append = [&](const char* format, auto... args) {
		std::snprintf(buffer, sizeof(buffer), format, args...);
		out += buffer;
	};

	ID3D11ShaderResourceView* srv = nullptr;
	ctx->PSGetShaderResources(static_cast<UINT>(srvSlot), 1, &srv);
	if (srv == nullptr) {
		out += "PS SRV[slot]=NULL (a null SRV samples as 0,0,0,1); ";
	} else {
		ID3D11Resource* resource = nullptr;
		srv->GetResource(&resource);
		Texture* expected = HandleToPointer<Texture>(expectedTextureHandle);
		const bool same = expected != nullptr && resource == static_cast<ID3D11Resource*>(expected->texture);
		D3D11_SHADER_RESOURCE_VIEW_DESC desc = {};
		srv->GetDesc(&desc);
		append("PS SRV[slot]=bound resourceMatchesSprite=%s fmt=%d dim=%d", same ? "yes" : "NO",
			static_cast<int>(desc.Format), static_cast<int>(desc.ViewDimension));
		if (desc.ViewDimension == D3D11_SRV_DIMENSION_TEXTURE2D) {
			append(" mostDetailedMip=%u mipLevels=%u", desc.Texture2D.MostDetailedMip, desc.Texture2D.MipLevels);
		}
		out += "; ";
		if (resource != nullptr) resource->Release();
		srv->Release();
	}

	ID3D11SamplerState* sampler = nullptr;
	ctx->PSGetSamplers(static_cast<UINT>(srvSlot), 1, &sampler);
	if (sampler == nullptr) {
		out += "PS sampler[slot]=NULL; ";
	} else {
		D3D11_SAMPLER_DESC desc = {};
		sampler->GetDesc(&desc);
		append("PS sampler=filter 0x%x addrU=%d minLOD=%.2f maxLOD=%.2f; ", static_cast<unsigned>(desc.Filter), static_cast<int>(desc.AddressU), desc.MinLOD, desc.MaxLOD);
		sampler->Release();
	}

	ID3D11PixelShader* ps = nullptr;
	ctx->PSGetShader(&ps, nullptr, nullptr);
	append("PS=%s; ", ps != nullptr ? "set" : "NULL");
	if (ps != nullptr) ps->Release();

	ID3D11RasterizerState* rs = nullptr;
	ctx->RSGetState(&rs);
	if (rs != nullptr) {
		D3D11_RASTERIZER_DESC desc = {};
		rs->GetDesc(&desc);
		append("RS cull=%d(1=none,2=front,3=back) frontCCW=%d scissor=%d depthClip=%d; ",
			static_cast<int>(desc.CullMode), desc.FrontCounterClockwise ? 1 : 0, desc.ScissorEnable ? 1 : 0, desc.DepthClipEnable ? 1 : 0);
		rs->Release();
	}
	D3D11_VIEWPORT viewport = {};
	UINT viewportCount = 1;
	ctx->RSGetViewports(&viewportCount, &viewport);
	RECT scissor = {};
	UINT scissorCount = 1;
	ctx->RSGetScissorRects(&scissorCount, &scissor);
	append("viewport=(%.0f,%.0f %.0fx%.0f z%.1f-%.1f) scissor=(%ld,%ld,%ld,%ld); ",
		viewport.TopLeftX, viewport.TopLeftY, viewport.Width, viewport.Height, viewport.MinDepth, viewport.MaxDepth,
		scissor.left, scissor.top, scissor.right, scissor.bottom);

	ID3D11BlendState* blend = nullptr;
	FLOAT factor[4] = {};
	UINT sampleMask = 0;
	ctx->OMGetBlendState(&blend, factor, &sampleMask);
	if (blend != nullptr) {
		D3D11_BLEND_DESC desc = {};
		blend->GetDesc(&desc);
		append("blend[0]: enable=%d src=%d dst=%d writeMask=0x%x; ",
			desc.RenderTarget[0].BlendEnable ? 1 : 0, static_cast<int>(desc.RenderTarget[0].SrcBlend),
			static_cast<int>(desc.RenderTarget[0].DestBlend), static_cast<unsigned>(desc.RenderTarget[0].RenderTargetWriteMask));
		blend->Release();
	} else {
		out += "blend=default(opaque, all channels); ";
	}

	ID3D11RenderTargetView* rtv = nullptr;
	ID3D11DepthStencilView* dsv = nullptr;
	ctx->OMGetRenderTargets(1, &rtv, &dsv);
	if (rtv != nullptr) {
		D3D11_RENDER_TARGET_VIEW_DESC desc = {};
		rtv->GetDesc(&desc);
		append("RTV fmt=%d", static_cast<int>(desc.Format));
		if (desc.ViewDimension == D3D11_RTV_DIMENSION_TEXTURE2D) {
			append(" mipSlice=%u", desc.Texture2D.MipSlice);
		}
		out += "; ";
		rtv->Release();
	} else {
		out += "RTV=NULL; ";
	}
	if (dsv != nullptr) dsv->Release();
	// Read back the vertex-stage b0 bytes to compare the GPU copy with the CPU-side uniform.
	ID3D11Buffer* cb0 = nullptr;
	ctx->VSGetConstantBuffers(0, 1, &cb0);
	if (cb0 != nullptr) {
		D3D11_BUFFER_DESC cbDesc = {};
		cb0->GetDesc(&cbDesc);
		append("VS cb0: byteWidth=%u usage=%d", cbDesc.ByteWidth, static_cast<int>(cbDesc.Usage));
		ID3D11Device* device = nullptr;
		ctx->GetDevice(&device);
		if (device != nullptr) {
			D3D11_BUFFER_DESC stagingDesc = cbDesc;
			stagingDesc.Usage = D3D11_USAGE_STAGING;
			stagingDesc.BindFlags = 0;
			stagingDesc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
			stagingDesc.MiscFlags = 0;
			ID3D11Buffer* staging = nullptr;
			if (SUCCEEDED(device->CreateBuffer(&stagingDesc, nullptr, &staging)) && staging != nullptr) {
				ctx->CopyResource(staging, cb0);
				D3D11_MAPPED_SUBRESOURCE mapped = {};
				if (SUCCEEDED(ctx->Map(staging, 0, D3D11_MAP_READ, 0, &mapped)) && mapped.pData != nullptr) {
					if (cbDesc.ByteWidth >= 140) {
						const float* f = static_cast<const float*>(mapped.pData);
						const int* i = static_cast<const int*>(mapped.pData);
						append(" gpu[0..3]=%.6g %.6g %.6g %.6g gpu[16..19]=%.6g %.6g %.6g %.6g gpu[20..23]=%.6g %.6g %.6g %.6g gpu[28..31]=%.6g %.6g %.6g %.6g UPadding=%.6g VPadding=%.6g MipMapLevel=%d; ",
							f[0], f[1], f[2], f[3], f[16], f[17], f[18], f[19], f[20], f[21], f[22], f[23], f[28], f[29], f[30], f[31], f[32], f[33], i[34]);
					} else {
						out += " (buffer too short to decode SpriteAnimationInfo); ";
					}
					ctx->Unmap(staging, 0);
				} else {
					out += " (could not map staging copy); ";
				}
				staging->Release();
			} else {
				out += " (could not create staging buffer); ";
			}
			device->Release();
		} else {
			out += " (could not get D3D11 device); ";
		}
		cb0->Release();
	} else {
		out += "VS cb0=NULL; ";
	}
	D3D11_PRIMITIVE_TOPOLOGY topology = D3D11_PRIMITIVE_TOPOLOGY_UNDEFINED;
	ctx->IAGetPrimitiveTopology(&topology);
	append("topology=%d", static_cast<int>(topology));
	return env->NewStringUTF(out.c_str());
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
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_clearDepthRegion0(JNIEnv*, jclass, jlong, jint, jint, jint, jint, jfloat) {
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_describeDrawState0(JNIEnv*, jclass, jint, jlong) {
	return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_runSamplingExperiment0(JNIEnv*, jclass, jlong) {
	return nullptr;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_runPixelSubstitutionProbe0(JNIEnv*, jclass, jint, jint, jint, jint) {
	return nullptr;
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
