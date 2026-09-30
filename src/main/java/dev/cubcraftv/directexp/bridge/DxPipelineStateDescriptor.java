package dev.cubcraftv.directexp.bridge;

import java.util.Objects;

/**
 * Flat, Minecraft-free description of one render pipeline's fixed-function
 * state, in the numbering {@code d3d11.h}/{@code dxgiformat.h} use (the
 * native side pins every number Java relies on with {@code static_assert}s,
 * so a header/ABI drift fails the native build). The Blaze3D -> D3D11
 * mapping that fills this in lives client-side in {@code DxPipelineStateMapping};
 * this record and {@link DxBridge#createPipeline} only carry the result across
 * the bridge.
 *
 * <p>{@link #stateArray()} / {@link #floatStateArray()} flatten the record for
 * JNI; their index order must match the {@code StateIndex} /
 * {@code FloatStateIndex} enums in {@code directexp_bridge_pipeline.cpp}
 * (the native side also checks array lengths, so a forgotten field fails loudly).
 */
public record DxPipelineStateDescriptor(
	int topology,
	boolean blendEnable,
	int srcColorBlend,
	int dstColorBlend,
	int colorBlendOp,
	int srcAlphaBlend,
	int dstAlphaBlend,
	int alphaBlendOp,
	int colorWriteMask,
	boolean depthTestEnable,
	boolean depthWrite,
	int depthFunc,
	int cullMode,
	int fillMode,
	int depthBias,
	float slopeScaledDepthBias,
	int[] attributeFormats,
	int[] attributeOffsets,
	int vertexStride
) {
	public static final int STATE_TOPOLOGY = 0;
	public static final int STATE_BLEND_ENABLE = 1;
	public static final int STATE_SRC_COLOR = 2;
	public static final int STATE_DST_COLOR = 3;
	public static final int STATE_OP_COLOR = 4;
	public static final int STATE_SRC_ALPHA = 5;
	public static final int STATE_DST_ALPHA = 6;
	public static final int STATE_OP_ALPHA = 7;
	public static final int STATE_COLOR_WRITE_MASK = 8;
	public static final int STATE_DEPTH_TEST_ENABLE = 9;
	public static final int STATE_DEPTH_WRITE = 10;
	public static final int STATE_DEPTH_FUNC = 11;
	public static final int STATE_CULL_MODE = 12;
	public static final int STATE_FILL_MODE = 13;
	public static final int STATE_DEPTH_BIAS = 14;
	public static final int STATE_COUNT = 15;

	public static final int FSTATE_SLOPE_SCALED_DEPTH_BIAS = 0;
	public static final int FSTATE_COUNT = 1;

	public DxPipelineStateDescriptor {
		Objects.requireNonNull(attributeFormats, "attributeFormats");
		Objects.requireNonNull(attributeOffsets, "attributeOffsets");
		if (attributeFormats.length != attributeOffsets.length) {
			throw new IllegalArgumentException("attributeFormats and attributeOffsets must have the same length.");
		}
		// A pipeline with an empty VertexFormat (a full-screen-quad/blit
		// shader that generates its own positions from gl_VertexIndex and
		// binds no vertex buffer -- vanilla has several: blit, clouds,
		// sprite-animation, lightmap, outline shaders) legitimately has
		// vertexStride 0, matching attributeFormats/attributeOffsets also
		// being empty (VertexFormat.getVertexSize() is 0 with no elements;
		// see DxPipelineStateMapping). Only reject stride 0 when there IS
		// at least one attribute to stride through -- that combination
		// really would be a degenerate/buggy vertex format.
		if (vertexStride <= 0 && attributeFormats.length > 0) {
			throw new IllegalArgumentException("vertexStride must be positive when attributeFormats is non-empty.");
		}
		if (vertexStride < 0) {
			throw new IllegalArgumentException("vertexStride must not be negative.");
		}
		attributeFormats = attributeFormats.clone();
		attributeOffsets = attributeOffsets.clone();
	}

	public int[] stateArray() {
		int[] state = new int[STATE_COUNT];
		state[STATE_TOPOLOGY] = this.topology;
		state[STATE_BLEND_ENABLE] = this.blendEnable ? 1 : 0;
		state[STATE_SRC_COLOR] = this.srcColorBlend;
		state[STATE_DST_COLOR] = this.dstColorBlend;
		state[STATE_OP_COLOR] = this.colorBlendOp;
		state[STATE_SRC_ALPHA] = this.srcAlphaBlend;
		state[STATE_DST_ALPHA] = this.dstAlphaBlend;
		state[STATE_OP_ALPHA] = this.alphaBlendOp;
		state[STATE_COLOR_WRITE_MASK] = this.colorWriteMask;
		state[STATE_DEPTH_TEST_ENABLE] = this.depthTestEnable ? 1 : 0;
		state[STATE_DEPTH_WRITE] = this.depthWrite ? 1 : 0;
		state[STATE_DEPTH_FUNC] = this.depthFunc;
		state[STATE_CULL_MODE] = this.cullMode;
		state[STATE_FILL_MODE] = this.fillMode;
		state[STATE_DEPTH_BIAS] = this.depthBias;
		return state;
	}

	public float[] floatStateArray() {
		float[] state = new float[FSTATE_COUNT];
		state[FSTATE_SLOPE_SCALED_DEPTH_BIAS] = this.slopeScaledDepthBias;
		return state;
	}

	// Record equality on arrays is identity; nothing in this project compares
	// descriptors, so no equals/hashCode override is provided on purpose.
}
