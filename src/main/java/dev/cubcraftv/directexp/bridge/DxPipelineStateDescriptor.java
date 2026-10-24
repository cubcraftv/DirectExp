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
	int[] attributeSlots,
	int[] attributeStepRates,
	int[] vertexStrides
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

	/**
	 * D3D11_IA_VERTEX_INPUT_RESOURCE_SLOT_COUNT (d3d11.h, pinned by
	 * static_assert natively): how wide {@link #vertexStrides} is, and the
	 * upper bound for every {@code bufferSlot}.
	 */
	public static final int VERTEX_BUFFER_SLOT_COUNT = 32;

	/**
	 * A positive stepRate marks a vertex-buffer binding instanced (the value
	 * is the instance divisor, Renderpearl/Vulkan semantics); 0 is per-vertex.
	 * An attribute inherits the rate of the slot it reads from.
	 */
	public static final int PER_VERTEX_STEP_RATE = 0;

	public DxPipelineStateDescriptor {
		Objects.requireNonNull(attributeFormats, "attributeFormats");
		Objects.requireNonNull(attributeOffsets, "attributeOffsets");
		Objects.requireNonNull(attributeSlots, "attributeSlots");
		Objects.requireNonNull(attributeStepRates, "attributeStepRates");
		Objects.requireNonNull(vertexStrides, "vertexStrides");
		if (attributeFormats.length != attributeOffsets.length
			|| attributeFormats.length != attributeSlots.length
			|| attributeFormats.length != attributeStepRates.length) {
			throw new IllegalArgumentException("attribute arrays must all have the same length.");
		}
		if (vertexStrides.length != VERTEX_BUFFER_SLOT_COUNT) {
			throw new IllegalArgumentException("vertexStrides must have one entry per D3D11 input slot.");
		}
		for (int slot : attributeSlots) {
			if (slot < 0 || slot >= VERTEX_BUFFER_SLOT_COUNT) {
				throw new IllegalArgumentException("attribute slot out of D3D11's input-slot range.");
			}
		}
		for (int stride : vertexStrides) {
			if (stride < 0) {
				throw new IllegalArgumentException("vertex strides must not be negative.");
			}
		}
		for (int stepRate : attributeStepRates) {
			// The native side accepts any UINT the D3D11 InstanceDataStepRate
			// field does; the guard here only rejects the nonsense sign.
			if (stepRate < 0) {
				throw new IllegalArgumentException("attribute step rates must not be negative.");
			}
		}
		// A pipeline with an empty VertexFormat (a full-screen-quad/blit
		// shader that generates its own positions from gl_VertexIndex and
		// binds no vertex buffer -- vanilla has several: blit, clouds,
		// sprite-animation, lightmap, outline shaders) legitimately has
		// zero attributes and no used slots. Only reject stride 0 where the
		// attributes need it -- each declaring slot is validated by its own
		// stride entry in DxPipelineStateMapping.
		attributeFormats = attributeFormats.clone();
		attributeOffsets = attributeOffsets.clone();
		attributeSlots = attributeSlots.clone();
		attributeStepRates = attributeStepRates.clone();
		vertexStrides = vertexStrides.clone();
	}

	/** Stride of one vertex-buffer slot; 0 means the pipeline declares no binding for it. */
	public int vertexStrideOf(int slot) {
		return this.vertexStrides[slot];
	}

	/** True when a slot carries instanced data (any positive step rate among its attributes). */
	public boolean slotIsInstanced(int slot) {
		for (int index = 0; index < this.attributeSlots.length; index++) {
			if (this.attributeSlots[index] == slot && this.attributeStepRates[index] > PER_VERTEX_STEP_RATE) {
				return true;
			}
		}
		return false;
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
