// DirectExp native bridge - render pipeline object assembly (Milestone 4.6,
// commit 2).
//
// createPipelineNative turns two compiled shader-bytecode blobs (the 4.5
// D3DCompile output, plus an optional generated geometry shader for
// gl_PointSize point sprites) plus a flat description of the Blaze3D render state into
// the set of immutable D3D11 objects one RenderPipeline needs: vertex shader,
// pixel shader, input layout, blend state, depth-stencil state, rasterizer
// state, and the primitive topology. Nothing is bound here -- binding and
// drawing is the next commit.
//
// The description crosses JNI as plain ints/floats whose values are the
// D3D11/DXGI ABI numbers (Java's DxPipelineStateMapping owns the Blaze3D ->
// D3D11 mapping, verified against d3d11.h/dxgiformat.h). The static_asserts
// below pin every number Java relies on to the real headers, so a mismatch
// breaks the native build instead of silently mis-rendering.
//
// Input layout: SPIRV-Cross's HLSL backend names every vertex-stage input
// TEXCOORD<location> (CompilerHLSL::to_semantic, no remap table is set by
// directexp_shader_hlsl.cpp), and the Vulkan backend assigns location = the
// element's index in VertexFormat.getElements(). Each element reads from the
// vertex-buffer slot its AttribBinding names (D3D11 InputSlot), at that
// slot's rate: per-vertex, or instanced with divisor stepRate > 0 (the
// Renderpearl field the decompiled Vulkan backend maps straight onto D3D11's
// InstanceDataStepRate). The per-slot strides and profiles cross JNI as
// separate arrays; the draw path (bindVertexBuffer0) picks the stride for
// whichever slot it binds.
//
#include "directexp_common.h"

#ifdef _WIN32

#include <vector>

using namespace directexp;

// ---- ABI pins: numbers DxPipelineStateMapping (Java) hard-codes ----------
static_assert(D3D11_BLEND_ZERO == 1 && D3D11_BLEND_ONE == 2 && D3D11_BLEND_SRC_COLOR == 3
	&& D3D11_BLEND_INV_SRC_COLOR == 4 && D3D11_BLEND_SRC_ALPHA == 5 && D3D11_BLEND_INV_SRC_ALPHA == 6
	&& D3D11_BLEND_DEST_ALPHA == 7 && D3D11_BLEND_INV_DEST_ALPHA == 8 && D3D11_BLEND_DEST_COLOR == 9
	&& D3D11_BLEND_INV_DEST_COLOR == 10 && D3D11_BLEND_SRC_ALPHA_SAT == 11
	&& D3D11_BLEND_BLEND_FACTOR == 14 && D3D11_BLEND_INV_BLEND_FACTOR == 15, "D3D11_BLEND values changed");
static_assert(D3D11_BLEND_OP_ADD == 1 && D3D11_BLEND_OP_SUBTRACT == 2 && D3D11_BLEND_OP_REV_SUBTRACT == 3
	&& D3D11_BLEND_OP_MIN == 4 && D3D11_BLEND_OP_MAX == 5, "D3D11_BLEND_OP values changed");
static_assert(D3D11_COMPARISON_NEVER == 1 && D3D11_COMPARISON_LESS == 2 && D3D11_COMPARISON_EQUAL == 3
	&& D3D11_COMPARISON_LESS_EQUAL == 4 && D3D11_COMPARISON_GREATER == 5 && D3D11_COMPARISON_NOT_EQUAL == 6
	&& D3D11_COMPARISON_GREATER_EQUAL == 7 && D3D11_COMPARISON_ALWAYS == 8, "D3D11_COMPARISON values changed");
static_assert(D3D11_CULL_NONE == 1 && D3D11_CULL_BACK == 3 && D3D11_FILL_WIREFRAME == 2 && D3D11_FILL_SOLID == 3,
	"D3D11 cull/fill values changed");
static_assert(D3D_PRIMITIVE_TOPOLOGY_POINTLIST == 1 && D3D_PRIMITIVE_TOPOLOGY_LINELIST == 2
	&& D3D_PRIMITIVE_TOPOLOGY_LINESTRIP == 3 && D3D_PRIMITIVE_TOPOLOGY_TRIANGLELIST == 4
	&& D3D_PRIMITIVE_TOPOLOGY_TRIANGLESTRIP == 5, "D3D topology values changed");
// DxBridge.GEOMETRY_VIEWPORT_CBUFFER_SLOT (Java) must be a valid constant buffer slot.
static_assert(13 < D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT, "geometry viewport cbuffer slot out of range");
static_assert(D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT == 8, "D3D11 simultaneous render target count changed");
static_assert(DXGI_FORMAT_R32G32B32A32_FLOAT == 2 && DXGI_FORMAT_R32G32B32_FLOAT == 6 && DXGI_FORMAT_R32G32_FLOAT == 16
	&& DXGI_FORMAT_R32_FLOAT == 41 && DXGI_FORMAT_R8G8B8A8_UNORM == 28 && DXGI_FORMAT_R8G8B8A8_SNORM == 31
	&& DXGI_FORMAT_R16G16_SINT == 38 && DXGI_FORMAT_R32G32B32A32_UINT == 3, "DXGI_FORMAT values changed");
// DxPipelineStateDescriptor.VERTEX_BUFFER_SLOT_COUNT (Java) and
// Pipeline::MAX_VERTEX_BUFFER_SLOTS must both be D3D11's real input-slot
// count -- the vertex-stride array crosses JNI with that exact width.
static_assert(D3D11_IA_VERTEX_INPUT_RESOURCE_SLOT_COUNT == 32, "D3D11 input slot count changed");

namespace {

// Indices into the int[] state array. Keep in sync with
// DxPipelineStateDescriptor.STATE_* (Java).
enum StateIndex : int {
	STATE_TOPOLOGY = 0,
	STATE_TARGET_COUNT,
	STATE_CULL_MODE,
	STATE_FILL_MODE,
	STATE_DEPTH_TEST_ENABLE,
	STATE_DEPTH_WRITE,
	STATE_DEPTH_FUNC,
	STATE_DEPTH_BIAS,
	// One strip of TargetStateOffset entries per color target, starting here
	// (DxPipelineStateDescriptor.STATE_TARGET_BASE / TARGET_STATE_STRIP).
	STATE_TARGET_BASE
};

enum TargetStateOffset : int {
	TARGET_STATE_BLEND_ENABLE = 0,
	TARGET_STATE_SRC_COLOR,
	TARGET_STATE_DST_COLOR,
	TARGET_STATE_OP_COLOR,
	TARGET_STATE_SRC_ALPHA,
	TARGET_STATE_DST_ALPHA,
	TARGET_STATE_OP_ALPHA,
	TARGET_STATE_WRITE_MASK,
	TARGET_STATE_COUNT
};

constexpr int kTargetStateStrip = TARGET_STATE_COUNT;
constexpr int kMaxColorTargets = D3D11_SIMULTANEOUS_RENDER_TARGET_COUNT;
constexpr int kExpectedStateCount = STATE_TARGET_BASE + kTargetStateStrip * kMaxColorTargets;

// float[] state indices. Keep in sync with DxPipelineStateDescriptor.FSTATE_*.
enum FloatStateIndex : int {
	FSTATE_SLOPE_SCALED_DEPTH_BIAS = 0,
	FSTATE_COUNT
};

struct ScopedByteArray {
	JNIEnv* env;
	jbyteArray array;
	jbyte* data;
	jsize length;

	ScopedByteArray(JNIEnv* e, jbyteArray a)
		: env(e), array(a), data(a != nullptr ? e->GetByteArrayElements(a, nullptr) : nullptr),
		  length(a != nullptr ? e->GetArrayLength(a) : 0) {}
	~ScopedByteArray() {
		if (data != nullptr) {
			env->ReleaseByteArrayElements(array, data, JNI_ABORT);
		}
	}
};

struct ScopedIntArray {
	JNIEnv* env;
	jintArray array;
	jint* data;
	jsize length;

	ScopedIntArray(JNIEnv* e, jintArray a)
		: env(e), array(a), data(a != nullptr ? e->GetIntArrayElements(a, nullptr) : nullptr),
		  length(a != nullptr ? e->GetArrayLength(a) : 0) {}
	~ScopedIntArray() {
		if (data != nullptr) {
			env->ReleaseIntArrayElements(array, data, JNI_ABORT);
		}
	}
};

void ReleasePipelineObjects(Pipeline* p) {
	if (p->vertexShader != nullptr) p->vertexShader->Release();
	if (p->pixelShader != nullptr) p->pixelShader->Release();
	if (p->geometryShader != nullptr) p->geometryShader->Release();
	if (p->geometryViewportBuffer != nullptr) p->geometryViewportBuffer->Release();
	if (p->inputLayout != nullptr) p->inputLayout->Release();
	if (p->blendState != nullptr) p->blendState->Release();
	if (p->depthStencilState != nullptr) p->depthStencilState->Release();
	if (p->rasterizerState != nullptr) p->rasterizerState->Release();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createPipelineNative(
	JNIEnv* env, jclass,
	jbyteArray vertexBytecode, jbyteArray pixelBytecode, jbyteArray geometryBytecode,
	jintArray attributeFormats, jintArray attributeOffsets, jintArray attributeSlots, jintArray attributeStepRates,
	jintArray vertexStrides,
	jintArray state, jfloatArray floatState
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createPipelineNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}

	ScopedByteArray vs(env, vertexBytecode);
	ScopedByteArray ps(env, pixelBytecode);
	ScopedByteArray gs(env, geometryBytecode); // null array = no geometry stage
	ScopedIntArray formats(env, attributeFormats);
	ScopedIntArray offsets(env, attributeOffsets);
	ScopedIntArray slots(env, attributeSlots);
	ScopedIntArray stepRates(env, attributeStepRates);
	ScopedIntArray strides(env, vertexStrides);
	ScopedIntArray st(env, state);

	if (vs.data == nullptr || vs.length <= 0 || ps.data == nullptr || ps.length <= 0) {
		ThrowIllegalState(env, "createPipelineNative requires non-empty vertex and pixel shader bytecode.");
		return 0;
	}
	if (st.data == nullptr || st.length != kExpectedStateCount || floatState == nullptr || env->GetArrayLength(floatState) != FSTATE_COUNT) {
		ThrowIllegalState(env, "createPipelineNative: state arrays have the wrong length (Java/native STATE_* out of sync).");
		return 0;
	}
	const jint targetCount = st.data[STATE_TARGET_COUNT];
	if (targetCount < 1 || targetCount > kMaxColorTargets) {
		ThrowIllegalState(env, "createPipelineNative: the color-target count is out of D3D11's render-target range.");
		return 0;
	}
	for (int target = 0; target < targetCount; target++) {
		const jint base = STATE_TARGET_BASE + target * kTargetStateStrip;
		if (st.data[base + TARGET_STATE_BLEND_ENABLE] != 0 && st.data[base + TARGET_STATE_BLEND_ENABLE] != 1) {
			ThrowIllegalState(env, "createPipelineNative: a target's blend enable is not 0 or 1.");
			return 0;
		}
		const jint writeMask = st.data[base + TARGET_STATE_WRITE_MASK];
		if (writeMask < 0 || writeMask > 0xF) {
			ThrowIllegalState(env, "createPipelineNative: a target's write mask is not a D3D11_COLOR_WRITE_ENABLE mask.");
			return 0;
		}
	}
	if (formats.length != offsets.length
		|| formats.length != slots.length
		|| formats.length != stepRates.length
		|| strides.length != Pipeline::MAX_VERTEX_BUFFER_SLOTS) {
		ThrowIllegalState(env, "createPipelineNative: attribute format/offset/slot/rate arrays must match, and one stride per D3D11 input slot is required (Java/native ABI out of sync).");
		return 0;
	}
	for (jsize i = 0; i < formats.length; i++) {
		if (slots.data[i] < 0 || slots.data[i] >= D3D11_IA_VERTEX_INPUT_RESOURCE_SLOT_COUNT || stepRates.data[i] < 0) {
			ThrowIllegalState(env, "createPipelineNative: an attribute names a slot or step rate out of range.");
			return 0;
		}
		if (strides.data[slots.data[i]] <= 0) {
			ThrowIllegalState(env, "createPipelineNative: attributes read from a slot with no vertex-buffer binding (stride 0).");
			return 0;
		}
	}
	for (jsize slot = 0; slot < strides.length; slot++) {
		if (strides.data[slot] < 0) {
			ThrowIllegalState(env, "createPipelineNative: a vertex stride is negative.");
			return 0;
		}
	}

	jfloat fs[FSTATE_COUNT] = {};
	env->GetFloatArrayRegion(floatState, 0, FSTATE_COUNT, fs);

	Pipeline* pipeline = new Pipeline();
	auto fail = [&](const char* message) -> jlong {
		ReleasePipelineObjects(pipeline);
		delete pipeline;
		ThrowIllegalState(env, message);
		return 0;
	};

	if (FAILED(g_activeDevice->CreateVertexShader(vs.data, static_cast<SIZE_T>(vs.length), nullptr, &pipeline->vertexShader))) {
		return fail("ID3D11Device::CreateVertexShader failed.");
	}
	if (FAILED(g_activeDevice->CreatePixelShader(ps.data, static_cast<SIZE_T>(ps.length), nullptr, &pipeline->pixelShader))) {
		return fail("ID3D11Device::CreatePixelShader failed.");
	}

	if (gs.data != nullptr && gs.length > 0) {
		if (FAILED(g_activeDevice->CreateGeometryShader(gs.data, static_cast<SIZE_T>(gs.length), nullptr, &pipeline->geometryShader))) {
			return fail("ID3D11Device::CreateGeometryShader failed.");
		}

		// float2 viewport size (+ padding to the 16-byte constant buffer granule).
		D3D11_BUFFER_DESC constants = {};
		constants.ByteWidth = 16;
		constants.Usage = D3D11_USAGE_DYNAMIC;
		constants.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
		constants.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
		if (FAILED(g_activeDevice->CreateBuffer(&constants, nullptr, &pipeline->geometryViewportBuffer))) {
			return fail("ID3D11Device::CreateBuffer failed for the geometry shader viewport constants.");
		}
	}

	// Input layout: one element per VertexFormat element, semantic
	// TEXCOORD<index>. The element reads from the input slot its AttribBinding
	// names, at that slot's rate (per-vertex, or instanced with divisor
	// stepRate > 0 -- the Renderpearl field the decompiled Vulkan backend maps
	// straight onto D3D11's InstanceDataStepRate). A pipeline with no vertex
	// inputs (fullscreen triangle from SV_VertexID) legitimately has zero
	// elements and no layout object.
	if (formats.length > 0) {
		std::vector<D3D11_INPUT_ELEMENT_DESC> elements(static_cast<size_t>(formats.length));
		for (jsize i = 0; i < formats.length; i++) {
			D3D11_INPUT_ELEMENT_DESC& e = elements[static_cast<size_t>(i)];
			e.SemanticName = "TEXCOORD";
			e.SemanticIndex = static_cast<UINT>(i);
			e.Format = static_cast<DXGI_FORMAT>(formats.data[i]);
			e.InputSlot = static_cast<UINT>(slots.data[i]);
			e.AlignedByteOffset = static_cast<UINT>(offsets.data[i]);
			if (stepRates.data[i] > 0) {
				e.InputSlotClass = D3D11_INPUT_PER_INSTANCE_DATA;
				e.InstanceDataStepRate = static_cast<UINT>(stepRates.data[i]);
			} else {
				e.InputSlotClass = D3D11_INPUT_PER_VERTEX_DATA;
				e.InstanceDataStepRate = 0;
			}
		}
		if (FAILED(g_activeDevice->CreateInputLayout(elements.data(), static_cast<UINT>(elements.size()),
			vs.data, static_cast<SIZE_T>(vs.length), &pipeline->inputLayout))) {
			return fail("ID3D11Device::CreateInputLayout failed (vertex format doesn't match the vertex shader's input signature).");
		}
	}

	// Per-target blend state: D3D11 uses one D3D11_RENDER_TARGET_BLEND_DESC
	// per render-target slot. With IndependentBlendEnable TRUE every bound
	// target uses its own entry; with FALSE all bound targets use entry 0.
	// All slot entries are filled either way -- the blend codes in a
	// zero-initialized desc are not valid D3D11_BLEND values.
	D3D11_BLEND_DESC blend = {};
	blend.AlphaToCoverageEnable = FALSE;
	blend.IndependentBlendEnable = targetCount > 1 ? TRUE : FALSE;
	for (int target = 0; target < kMaxColorTargets; target++) {
		D3D11_RENDER_TARGET_BLEND_DESC& rt = blend.RenderTarget[target];
		const jint base = STATE_TARGET_BASE + target * kTargetStateStrip;
		if (target < targetCount) {
			rt.BlendEnable = st.data[base + TARGET_STATE_BLEND_ENABLE] != 0 ? TRUE : FALSE;
			rt.SrcBlend = static_cast<D3D11_BLEND>(st.data[base + TARGET_STATE_SRC_COLOR]);
			rt.DestBlend = static_cast<D3D11_BLEND>(st.data[base + TARGET_STATE_DST_COLOR]);
			rt.BlendOp = static_cast<D3D11_BLEND_OP>(st.data[base + TARGET_STATE_OP_COLOR]);
			rt.SrcBlendAlpha = static_cast<D3D11_BLEND>(st.data[base + TARGET_STATE_SRC_ALPHA]);
			rt.DestBlendAlpha = static_cast<D3D11_BLEND>(st.data[base + TARGET_STATE_DST_ALPHA]);
			rt.BlendOpAlpha = static_cast<D3D11_BLEND_OP>(st.data[base + TARGET_STATE_OP_ALPHA]);
			rt.RenderTargetWriteMask = static_cast<UINT8>(st.data[base + TARGET_STATE_WRITE_MASK] & 0xF);
		} else {
			// D3D11's default: no blending, all channels written (the CD3D11
			// struct helper uses the same defaults).
			rt.BlendEnable = FALSE;
			rt.SrcBlend = rt.SrcBlendAlpha = D3D11_BLEND_ONE;
			rt.DestBlend = rt.DestBlendAlpha = D3D11_BLEND_ZERO;
			rt.BlendOp = rt.BlendOpAlpha = D3D11_BLEND_OP_ADD;
			rt.RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
		}
	}
	if (FAILED(g_activeDevice->CreateBlendState(&blend, &pipeline->blendState))) {
		return fail("ID3D11Device::CreateBlendState failed.");
	}

	D3D11_DEPTH_STENCIL_DESC depth = {};
	depth.DepthEnable = st.data[STATE_DEPTH_TEST_ENABLE] != 0 ? TRUE : FALSE;
	depth.DepthWriteMask = st.data[STATE_DEPTH_WRITE] != 0 ? D3D11_DEPTH_WRITE_MASK_ALL : D3D11_DEPTH_WRITE_MASK_ZERO;
	depth.DepthFunc = static_cast<D3D11_COMPARISON_FUNC>(st.data[STATE_DEPTH_FUNC]);
	depth.StencilEnable = FALSE;
	depth.StencilReadMask = D3D11_DEFAULT_STENCIL_READ_MASK;
	depth.StencilWriteMask = D3D11_DEFAULT_STENCIL_WRITE_MASK;
	depth.FrontFace = { D3D11_STENCIL_OP_KEEP, D3D11_STENCIL_OP_KEEP, D3D11_STENCIL_OP_KEEP, D3D11_COMPARISON_ALWAYS };
	depth.BackFace = depth.FrontFace;
	if (FAILED(g_activeDevice->CreateDepthStencilState(&depth, &pipeline->depthStencilState))) {
		return fail("ID3D11Device::CreateDepthStencilState failed.");
	}

	D3D11_RASTERIZER_DESC raster = {};
	raster.FillMode = static_cast<D3D11_FILL_MODE>(st.data[STATE_FILL_MODE]);
	raster.CullMode = static_cast<D3D11_CULL_MODE>(st.data[STATE_CULL_MODE]);
	// FALSE (front face = clockwise). Vertex shaders negate gl_Position.y
	// (see EmitHlsl) so D3D framebuffer positions match Vulkan's: Blaze3D's
	// Vulkan backend uses a positive-height viewport (NDC Y=-1 -> row 0) with
	// frontFace(CLOCKWISE). With identical framebuffer positions, identical
	// winding rules apply, i.e. D3D's default clockwise front face. The earlier
	// TRUE was only right while geometry was NOT flipped.
	raster.FrontCounterClockwise = FALSE;
	raster.DepthBias = st.data[STATE_DEPTH_BIAS];
	raster.DepthBiasClamp = 0.0f;
	raster.SlopeScaledDepthBias = fs[FSTATE_SLOPE_SCALED_DEPTH_BIAS];
	raster.DepthClipEnable = TRUE;
	raster.ScissorEnable = TRUE; // Blaze3D scissor on/off is modelled as a rect (off = full target)
	raster.MultisampleEnable = FALSE;
	raster.AntialiasedLineEnable = FALSE;
	if (FAILED(g_activeDevice->CreateRasterizerState(&raster, &pipeline->rasterizerState))) {
		return fail("ID3D11Device::CreateRasterizerState failed.");
	}

	pipeline->topology = static_cast<D3D_PRIMITIVE_TOPOLOGY>(st.data[STATE_TOPOLOGY]);
	for (jsize slot = 0; slot < strides.length; slot++) {
		pipeline->vertexStrides[slot] = static_cast<UINT>(strides.data[slot]);
	}
	return PointerToHandle(pipeline);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_setPipelineViewport0(JNIEnv* env, jclass, jlong nativePipelineHandle, jint width, jint height) {
	Pipeline* pipeline = HandleToPointer<Pipeline>(nativePipelineHandle);
	if (pipeline == nullptr || pipeline->geometryViewportBuffer == nullptr) {
		return; // only point-sprite pipelines have viewport constants
	}
	if (g_activeContext == nullptr) {
		ThrowIllegalState(env, "setPipelineViewport0 called with no active DirectX device context.");
		return;
	}
	if (width <= 0 || height <= 0) {
		ThrowIllegalState(env, "setPipelineViewport0 requires a positive viewport size.");
		return;
	}

	D3D11_MAPPED_SUBRESOURCE mapped = {};
	if (FAILED(g_activeContext->Map(pipeline->geometryViewportBuffer, 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) {
		ThrowIllegalState(env, "ID3D11DeviceContext::Map failed for the geometry shader viewport constants.");
		return;
	}
	float* values = static_cast<float*>(mapped.pData);
	values[0] = static_cast<float>(width);
	values[1] = static_cast<float>(height);
	values[2] = 0.0f;
	values[3] = 0.0f;
	g_activeContext->Unmap(pipeline->geometryViewportBuffer, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releasePipeline0(JNIEnv*, jclass, jlong nativePipelineHandle) {
	Pipeline* pipeline = HandleToPointer<Pipeline>(nativePipelineHandle);
	if (pipeline == nullptr) {
		return;
	}

	ForgetBoundPipeline(pipeline);
	ReleasePipelineObjects(pipeline);
	delete pipeline;
}

#else // !_WIN32

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createPipelineNative(
	JNIEnv*, jclass, jbyteArray, jbyteArray, jbyteArray, jintArray, jintArray, jintArray, jintArray, jintArray, jintArray, jfloatArray) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_setPipelineViewport0(JNIEnv*, jclass, jlong, jint, jint) {
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releasePipeline0(JNIEnv*, jclass, jlong) {
}

#endif