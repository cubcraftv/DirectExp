// DirectExp native bridge - render pipeline object assembly (Milestone 4.6,
// commit 2).
//
// createPipelineNative turns two compiled shader-bytecode blobs (the 4.5
// D3DCompile output) plus a flat description of the Blaze3D render state into
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
// element's index in VertexFormat.getElements(), all in vertex buffer slot 0
// with per-vertex input rate. This file builds exactly that.
//
// NOTE ON VERIFICATION: not compiled against a real Windows toolchain by the
// authoring agent (syntax-checked only); see the Milestone 4.6 source of truth.

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
static_assert(DXGI_FORMAT_R32G32B32A32_FLOAT == 2 && DXGI_FORMAT_R32G32B32_FLOAT == 6 && DXGI_FORMAT_R32G32_FLOAT == 16
	&& DXGI_FORMAT_R32_FLOAT == 41 && DXGI_FORMAT_R8G8B8A8_UNORM == 28 && DXGI_FORMAT_R8G8B8A8_SNORM == 31
	&& DXGI_FORMAT_R16G16_SINT == 38 && DXGI_FORMAT_R32G32B32A32_UINT == 3, "DXGI_FORMAT values changed");

namespace {

// Indices into the int[] state array. Keep in sync with
// DxPipelineStateDescriptor.STATE_* (Java).
enum StateIndex : int {
	STATE_TOPOLOGY = 0,
	STATE_BLEND_ENABLE,
	STATE_SRC_COLOR,
	STATE_DST_COLOR,
	STATE_OP_COLOR,
	STATE_SRC_ALPHA,
	STATE_DST_ALPHA,
	STATE_OP_ALPHA,
	STATE_COLOR_WRITE_MASK,
	STATE_DEPTH_TEST_ENABLE,
	STATE_DEPTH_WRITE,
	STATE_DEPTH_FUNC,
	STATE_CULL_MODE,
	STATE_FILL_MODE,
	STATE_DEPTH_BIAS,
	STATE_COUNT
};

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
	if (p->inputLayout != nullptr) p->inputLayout->Release();
	if (p->blendState != nullptr) p->blendState->Release();
	if (p->depthStencilState != nullptr) p->depthStencilState->Release();
	if (p->rasterizerState != nullptr) p->rasterizerState->Release();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createPipelineNative(
	JNIEnv* env, jclass,
	jbyteArray vertexBytecode, jbyteArray pixelBytecode,
	jintArray attributeFormats, jintArray attributeOffsets, jint vertexStride,
	jintArray state, jfloatArray floatState
) {
	if (g_activeDevice == nullptr) {
		ThrowIllegalState(env, "createPipelineNative called with no active DirectX device (no surface bootstrapped yet?).");
		return 0;
	}

	ScopedByteArray vs(env, vertexBytecode);
	ScopedByteArray ps(env, pixelBytecode);
	ScopedIntArray formats(env, attributeFormats);
	ScopedIntArray offsets(env, attributeOffsets);
	ScopedIntArray st(env, state);

	if (vs.data == nullptr || vs.length <= 0 || ps.data == nullptr || ps.length <= 0) {
		ThrowIllegalState(env, "createPipelineNative requires non-empty vertex and pixel shader bytecode.");
		return 0;
	}
	if (st.data == nullptr || st.length != STATE_COUNT || floatState == nullptr || env->GetArrayLength(floatState) != FSTATE_COUNT) {
		ThrowIllegalState(env, "createPipelineNative: state arrays have the wrong length (Java/native STATE_* out of sync).");
		return 0;
	}
	if (formats.length != offsets.length || vertexStride <= 0) {
		ThrowIllegalState(env, "createPipelineNative: attribute format/offset arrays must match and vertexStride must be positive.");
		return 0;
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

	// Input layout: one element per VertexFormat element, semantic
	// TEXCOORD<index>, slot 0, per-vertex. A pipeline with no vertex inputs
	// (fullscreen triangle from SV_VertexID) legitimately has zero elements
	// and no layout object.
	if (formats.length > 0) {
		std::vector<D3D11_INPUT_ELEMENT_DESC> elements(static_cast<size_t>(formats.length));
		for (jsize i = 0; i < formats.length; i++) {
			D3D11_INPUT_ELEMENT_DESC& e = elements[static_cast<size_t>(i)];
			e.SemanticName = "TEXCOORD";
			e.SemanticIndex = static_cast<UINT>(i);
			e.Format = static_cast<DXGI_FORMAT>(formats.data[i]);
			e.InputSlot = 0;
			e.AlignedByteOffset = static_cast<UINT>(offsets.data[i]);
			e.InputSlotClass = D3D11_INPUT_PER_VERTEX_DATA;
			e.InstanceDataStepRate = 0;
		}
		if (FAILED(g_activeDevice->CreateInputLayout(elements.data(), static_cast<UINT>(elements.size()),
			vs.data, static_cast<SIZE_T>(vs.length), &pipeline->inputLayout))) {
			return fail("ID3D11Device::CreateInputLayout failed (vertex format doesn't match the vertex shader's input signature).");
		}
	}

	D3D11_BLEND_DESC blend = {};
	blend.AlphaToCoverageEnable = FALSE;
	blend.IndependentBlendEnable = FALSE;
	D3D11_RENDER_TARGET_BLEND_DESC& rt = blend.RenderTarget[0];
	rt.BlendEnable = st.data[STATE_BLEND_ENABLE] != 0 ? TRUE : FALSE;
	rt.SrcBlend = static_cast<D3D11_BLEND>(st.data[STATE_SRC_COLOR]);
	rt.DestBlend = static_cast<D3D11_BLEND>(st.data[STATE_DST_COLOR]);
	rt.BlendOp = static_cast<D3D11_BLEND_OP>(st.data[STATE_OP_COLOR]);
	rt.SrcBlendAlpha = static_cast<D3D11_BLEND>(st.data[STATE_SRC_ALPHA]);
	rt.DestBlendAlpha = static_cast<D3D11_BLEND>(st.data[STATE_DST_ALPHA]);
	rt.BlendOpAlpha = static_cast<D3D11_BLEND_OP>(st.data[STATE_OP_ALPHA]);
	rt.RenderTargetWriteMask = static_cast<UINT8>(st.data[STATE_COLOR_WRITE_MASK] & 0xF);
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
	// TRUE (front face = counter-clockwise), i.e. GL's convention, kept as
	// is -- confirmed correct, not just assumed, by Milestone 4.6 commit 3's
	// shader-interface investigation. Summary (full reasoning in the source
	// of truth doc's "Does it flip Y" section): D3D11's NDC is Y-up, the
	// same as OpenGL/GLSL's; Vulkan is the outlier with a Y-down NDC. The
	// decompiled glsl package (GlslCompiler/IntermediaryShaderModule) never
	// touches gl_Position, so Vulkan's own Y-down NDC must be compensated
	// for elsewhere (most likely a backend-specific projection matrix, not
	// in the supplied decompile) -- and Vulkan's frontFace(CLOCKWISE) is
	// that compensation's paired front-face flip, not something intrinsic
	// to GLSL->SPIR-V compilation. D3D11 needs neither: it shares GL's Y-up
	// convention already, so it also keeps GL's front face.
	raster.FrontCounterClockwise = TRUE;
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
	pipeline->vertexStride = static_cast<UINT>(vertexStride);
	return PointerToHandle(pipeline);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releasePipeline0(JNIEnv*, jclass, jlong nativePipelineHandle) {
	Pipeline* pipeline = HandleToPointer<Pipeline>(nativePipelineHandle);
	if (pipeline == nullptr) {
		return;
	}

	ReleasePipelineObjects(pipeline);
	delete pipeline;
}

#else // !_WIN32

extern "C" JNIEXPORT jlong JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_createPipelineNative(
	JNIEnv*, jclass, jbyteArray, jbyteArray, jintArray, jintArray, jint, jintArray, jfloatArray
) {
	return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_cubcraftv_directexp_bridge_NativeDxBridge_releasePipeline0(JNIEnv*, jclass, jlong) {
}

#endif
