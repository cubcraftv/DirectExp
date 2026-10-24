package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import dev.cubcraftv.directexp.bridge.DxPipelineStateDescriptor;


/**
 * Renderpearl backend {@code BackendRenderPipeline.CreateInfo} state -> {@link DxPipelineStateDescriptor}
 * (D3D11/DXGI ABI numbers, pinned to the real headers by static_asserts in
 * {@code directexp_bridge_pipeline.cpp}). The rules mirror the decompiled
 * Vulkan backend's {@code VulkanRenderPipeline}/{@code VulkanConst}:
 * <ul>
 *   <li>depth test is on iff {@code getDepthStencilState() != null}; depth
 *       write / function come from it; depth bias is enabled only when both
 *       the constant and the scale factor are non-zero;</li>
 *   <li>cull = back when {@code isCull()}, else none;</li>
 *   <li>blend enabled iff {@code blendFunction()} is present; color write
 *       mask from the four write flags;</li>
 *   <li>topology: {@code QUADS} and {@code LINES} arrive as already-expanded
 *       triangle indices (Vulkan maps both to a triangle list), so they use
 *       TRIANGLELIST; {@code TRIANGLE_FAN} has no D3D11 topology, so the
 *       immutable pipeline is also TRIANGLELIST and the draw path must
 *       expand each fan's indices ({@link DxTriangleFanExpander});</li>
 *   <li>vertex inputs: one element per {@code VertexFormat} element, in order
 *       (the element index is also the shader location), slot 0, per-vertex.</li>
 * </ul>
 * Known approximations, all recorded in the Milestone 4.6 source of truth:
 * D3D11 has no 3-component 8/16-bit vertex formats so {@code RGB8_*}/{@code RGB16_*}
 * are widened to 4 components (the shader only reads three); constant-color
 * blend factors collapse to D3D11's single blend factor (which we leave at
 * zero, exactly like Vulkan's default blend constants); alpha-slot
 * {@code *_COLOR} factors are rewritten to their {@code *_ALPHA} twins because
 * D3D11 forbids {@code _COLOR} options in the alpha slot; Vulkan's float
 * depth-bias constant is rounded into D3D11's integer bias.
 */
final class DxPipelineStateMapping {
	// D3D11 / DXGI / D3D ABI numbers (see the static_asserts natively).
	static final int BLEND_ZERO = 1;
	static final int BLEND_ONE = 2;
	static final int BLEND_SRC_COLOR = 3;
	static final int BLEND_INV_SRC_COLOR = 4;
	static final int BLEND_SRC_ALPHA = 5;
	static final int BLEND_INV_SRC_ALPHA = 6;
	static final int BLEND_DEST_ALPHA = 7;
	static final int BLEND_INV_DEST_ALPHA = 8;
	static final int BLEND_DEST_COLOR = 9;
	static final int BLEND_INV_DEST_COLOR = 10;
	static final int BLEND_SRC_ALPHA_SAT = 11;
	static final int BLEND_FACTOR = 14;
	static final int BLEND_INV_FACTOR = 15;

	static final int OP_ADD = 1;
	static final int OP_SUBTRACT = 2;
	static final int OP_REV_SUBTRACT = 3;
	static final int OP_MIN = 4;
	static final int OP_MAX = 5;

	static final int CMP_NEVER = 1;
	static final int CMP_LESS = 2;
	static final int CMP_EQUAL = 3;
	static final int CMP_LESS_EQUAL = 4;
	static final int CMP_GREATER = 5;
	static final int CMP_NOT_EQUAL = 6;
	static final int CMP_GREATER_EQUAL = 7;
	static final int CMP_ALWAYS = 8;

	static final int CULL_NONE = 1;
	static final int CULL_BACK = 3;
	static final int FILL_WIREFRAME = 2;
	static final int FILL_SOLID = 3;

	static final int TOPOLOGY_POINTLIST = 1;
	static final int TOPOLOGY_LINELIST = 2;
	static final int TOPOLOGY_LINESTRIP = 3;
	static final int TOPOLOGY_TRIANGLELIST = 4;
	static final int TOPOLOGY_TRIANGLESTRIP = 5;

	static final int DXGI_FORMAT_R32_FLOAT = 0x29;

	static final int WRITE_RED = 1;
	static final int WRITE_GREEN = 2;
	static final int WRITE_BLUE = 4;
	static final int WRITE_ALPHA = 8;

	private DxPipelineStateMapping() {
	}

	/**
	 * Builds the descriptor for a pipeline from the backend create info the
	 * 26.3 frontend hands over. Throws {@link UnsupportedOperationException}
	 * (with a message naming the offending feature) for state D3D11 cannot
	 * express yet -- the caller marks that one pipeline invalid rather than failing.
	 *
	 * <p>Vertex input element index == shader location (the native input layout
	 * names each element {@code TEXCOORD<index>}, as SPIRV-Cross does). The
	 * frontend only reports attributes the vertex shader actually reads, at the
	 * locations the shader declares, so locations the shader skips are filled
	 * with an unused 32-bit float element, which D3D11 accepts in an input layout
	 * that is a superset of the shader's inputs.
	 */
	static DxPipelineStateDescriptor describe(BackendRenderPipeline.CreateInfo info) {
		int vertexStride = 0;
		for (BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer : info.vertexBuffers()) {
			if (vertexBuffer.bufferSlot() != 0) {
				throw new UnsupportedOperationException("multiple vertex-buffer bindings are not supported yet");
			}
			if (vertexBuffer.stepRate() != 0) {
				throw new UnsupportedOperationException("instanced vertex buffers are not supported yet");
			}
			vertexStride = vertexBuffer.stride();
		}

		int elementCount = 0;
		for (BackendRenderPipeline.CreateInfo.AttribBinding attrib : info.attribBindings()) {
			elementCount = Math.max(elementCount, attrib.location() + 1);
		}
		int[] formats = new int[elementCount];
		int[] offsets = new int[elementCount];
		java.util.Arrays.fill(formats, DXGI_FORMAT_R32_FLOAT);
		for (BackendRenderPipeline.CreateInfo.AttribBinding attrib : info.attribBindings()) {
			formats[attrib.location()] = vertexAttributeFormat(attrib.format());
			offsets[attrib.location()] = attrib.offset();
		}
		if (elementCount > 0 && vertexStride == 0) {
			throw new UnsupportedOperationException("vertex attributes without a vertex buffer binding");
		}

		DepthStencilState depthStencil = info.depthStencilState();
		boolean depthTest = depthStencil != null;
		boolean depthWrite = depthTest && depthStencil.writeDepth();
		int depthFunc = depthTest ? compareFunc(depthStencil.depthTest()) : CMP_ALWAYS;
		float biasConstant = depthTest ? depthStencil.depthBiasConstant() : 0.0F;
		float biasScale = depthTest ? depthStencil.depthBiasScaleFactor() : 0.0F;
		boolean biasEnabled = biasConstant != 0.0F && biasScale != 0.0F;

		// The native pipeline has one render target; the first used color target
		// decides blend/write mask. (Multi-target pipelines only exist for the
		// order-independent transparency path, which this device does not offer.)
		ColorTargetState colorTarget = null;
		int usedTargets = 0;
		for (ColorTargetState state : info.colorTargetStates()) {
			if (state != null) {
				usedTargets++;
				if (colorTarget == null) {
					colorTarget = state;
				}
			}
		}
		if (usedTargets > 1) {
			throw new UnsupportedOperationException("multiple color targets are not supported yet");
		}
		BlendFunction blend = colorTarget == null ? null : colorTarget.blendFunction().orElse(null);
		int writeMask = colorTarget == null ? 0 : colorWriteMask(colorTarget.writeRed(), colorTarget.writeGreen(), colorTarget.writeBlue(), colorTarget.writeAlpha());
		int[] colorFactors = {BLEND_ONE, BLEND_ZERO};
		int[] alphaFactors = {BLEND_ONE, BLEND_ZERO};
		int colorOp = OP_ADD;
		int alphaOp = OP_ADD;
		if (blend != null) {
			colorFactors = new int[] {blendFactor(blend.color().sourceFactor(), false), blendFactor(blend.color().destFactor(), false)};
			alphaFactors = new int[] {blendFactor(blend.alpha().sourceFactor(), true), blendFactor(blend.alpha().destFactor(), true)};
			colorOp = blendOp(blend.color().op());
			alphaOp = blendOp(blend.alpha().op());
		}

		return new DxPipelineStateDescriptor(
			topology(info.primitiveTopology()),
			blend != null,
			colorFactors[0], colorFactors[1], colorOp,
			alphaFactors[0], alphaFactors[1], alphaOp,
			writeMask,
			depthTest, depthWrite, depthFunc,
			info.cull() ? CULL_BACK : CULL_NONE,
			fillMode(info.polygonMode()),
			biasEnabled ? Math.round(biasConstant) : 0,
			biasEnabled ? biasScale : 0.0F,
			formats, offsets, vertexStride
		);
	}

	static int colorWriteMask(boolean red, boolean green, boolean blue, boolean alpha) {
		return (red ? WRITE_RED : 0) | (green ? WRITE_GREEN : 0) | (blue ? WRITE_BLUE : 0) | (alpha ? WRITE_ALPHA : 0);
	}

	static int blendFactor(BlendFactor factor, boolean alphaSlot) {
		return switch (factor) {
			case ZERO -> BLEND_ZERO;
			case ONE -> BLEND_ONE;
			// D3D11 forbids *_COLOR options in the alpha slot; use the alpha twins.
			case SRC_COLOR -> alphaSlot ? BLEND_SRC_ALPHA : BLEND_SRC_COLOR;
			case ONE_MINUS_SRC_COLOR -> alphaSlot ? BLEND_INV_SRC_ALPHA : BLEND_INV_SRC_COLOR;
			case DST_COLOR -> alphaSlot ? BLEND_DEST_ALPHA : BLEND_DEST_COLOR;
			case ONE_MINUS_DST_COLOR -> alphaSlot ? BLEND_INV_DEST_ALPHA : BLEND_INV_DEST_COLOR;
			case SRC_ALPHA -> BLEND_SRC_ALPHA;
			case ONE_MINUS_SRC_ALPHA -> BLEND_INV_SRC_ALPHA;
			case DST_ALPHA -> BLEND_DEST_ALPHA;
			case ONE_MINUS_DST_ALPHA -> BLEND_INV_DEST_ALPHA;
			case SRC_ALPHA_SATURATE -> BLEND_SRC_ALPHA_SAT;
			// D3D11 has one blend factor (RGBA) with no color/alpha split.
			case CONSTANT_COLOR, CONSTANT_ALPHA -> BLEND_FACTOR;
			case ONE_MINUS_CONSTANT_COLOR, ONE_MINUS_CONSTANT_ALPHA -> BLEND_INV_FACTOR;
		};
	}

	static int blendOp(BlendOp op) {
		return switch (op) {
			case ADD -> OP_ADD;
			case SUBTRACT -> OP_SUBTRACT;
			case REVERSE_SUBTRACT -> OP_REV_SUBTRACT;
			case MIN -> OP_MIN;
			case MAX -> OP_MAX;
		};
	}

	static int compareFunc(CompareOp op) {
		return switch (op) {
			case ALWAYS_PASS -> CMP_ALWAYS;
			case LESS_THAN -> CMP_LESS;
			case LESS_THAN_OR_EQUAL -> CMP_LESS_EQUAL;
			case EQUAL -> CMP_EQUAL;
			case NOT_EQUAL -> CMP_NOT_EQUAL;
			case GREATER_THAN_OR_EQUAL -> CMP_GREATER_EQUAL;
			case GREATER_THAN -> CMP_GREATER;
			case NEVER_PASS -> CMP_NEVER;
		};
	}

	static int fillMode(PolygonMode mode) {
		return switch (mode) {
			case FILL -> FILL_SOLID;
			case WIREFRAME -> FILL_WIREFRAME;
		};
	}

	static int topology(PrimitiveTopology topology) {
		return switch (topology) {
			case POINTS -> TOPOLOGY_POINTLIST;
			case DEBUG_LINES -> TOPOLOGY_LINELIST;
			case DEBUG_LINE_STRIP -> TOPOLOGY_LINESTRIP;
			case TRIANGLES, QUADS, LINES, TRIANGLE_FAN -> TOPOLOGY_TRIANGLELIST;
			case TRIANGLE_STRIP -> TOPOLOGY_TRIANGLESTRIP;
		};
	}

	/** DXGI_FORMAT number for a vertex attribute format; 3-component 8/16-bit formats widen to 4 components. */
	static int vertexAttributeFormat(GpuFormat format) {
		return switch (format) {
			case R8_UNORM -> 0x3d;
			case R8_SNORM -> 0x3f;
			case RG8_UNORM -> 0x31;
			case RG8_SNORM -> 0x33;
			case RGB8_UNORM, RGBA8_UNORM -> 0x1c;
			case RGB8_SNORM, RGBA8_SNORM -> 0x1f;
			case R16_UNORM -> 0x38;
			case R16_SNORM -> 0x3a;
			case RG16_UNORM -> 0x23;
			case RG16_SNORM -> 0x25;
			case RGB16_UNORM, RGBA16_UNORM -> 0x0b;
			case RGB16_SNORM, RGBA16_SNORM -> 0x0d;
			case R8_UINT -> 0x3e;
			case R8_SINT -> 0x40;
			case RG8_UINT -> 0x32;
			case RG8_SINT -> 0x34;
			case RGB8_UINT, RGBA8_UINT -> 0x1e;
			case RGB8_SINT, RGBA8_SINT -> 0x20;
			case R16_UINT -> 0x39;
			case R16_SINT -> 0x3b;
			case RG16_UINT -> 0x24;
			case RG16_SINT -> 0x26;
			case RGB16_UINT, RGBA16_UINT -> 0x0c;
			case RGB16_SINT, RGBA16_SINT -> 0x0e;
			case R32_UINT -> 0x2a;
			case R32_SINT -> 0x2b;
			case RG32_UINT -> 0x11;
			case RG32_SINT -> 0x12;
			case RGB32_UINT -> 0x07;
			case RGB32_SINT -> 0x08;
			case RGBA32_UINT -> 0x03;
			case RGBA32_SINT -> 0x04;
			case R16_FLOAT -> 0x36;
			case RG16_FLOAT -> 0x22;
			case RGB16_FLOAT, RGBA16_FLOAT -> 0x0a;
			case R32_FLOAT -> 0x29;
			case RG32_FLOAT -> 0x10;
			case RGB32_FLOAT -> 0x06;
			case RGBA32_FLOAT -> 0x02;
			case RGB10A2_UNORM -> 0x18;
			case RGB10A2_UINT -> 0x19;
			case RG11B10_FLOAT -> 0x1a;
			default -> throw new UnsupportedOperationException("Format " + format + " is not a valid vertex attribute format.");
		};
	}
}
