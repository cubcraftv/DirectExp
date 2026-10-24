package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendOp;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.PolygonMode;
import dev.cubcraftv.directexp.bridge.DxPipelineStateDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxPipelineStateMappingTest {
	@Test
	void quadsLinesAndFansUseTriangleList() {
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLELIST, DxPipelineStateMapping.topology(PrimitiveTopology.QUADS));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLELIST, DxPipelineStateMapping.topology(PrimitiveTopology.LINES));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLELIST, DxPipelineStateMapping.topology(PrimitiveTopology.TRIANGLES));
		// D3D11 has no fan topology: the pipeline is a triangle list and the draw path expands the indices.
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLELIST, DxPipelineStateMapping.topology(PrimitiveTopology.TRIANGLE_FAN));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLESTRIP, DxPipelineStateMapping.topology(PrimitiveTopology.TRIANGLE_STRIP));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_LINELIST, DxPipelineStateMapping.topology(PrimitiveTopology.DEBUG_LINES));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_LINESTRIP, DxPipelineStateMapping.topology(PrimitiveTopology.DEBUG_LINE_STRIP));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_POINTLIST, DxPipelineStateMapping.topology(PrimitiveTopology.POINTS));
	}

	@Test
	void alphaSlotNeverUsesColorBlendOptions() {
		for (BlendFactor factor : BlendFactor.values()) {
			int alpha = DxPipelineStateMapping.blendFactor(factor, true);
			assertFalse(alpha == DxPipelineStateMapping.BLEND_SRC_COLOR
				|| alpha == DxPipelineStateMapping.BLEND_INV_SRC_COLOR
				|| alpha == DxPipelineStateMapping.BLEND_DEST_COLOR
				|| alpha == DxPipelineStateMapping.BLEND_INV_DEST_COLOR, factor + " leaked a _COLOR option into the alpha slot");
		}
		assertEquals(DxPipelineStateMapping.BLEND_SRC_ALPHA, DxPipelineStateMapping.blendFactor(BlendFactor.SRC_COLOR, true));
		assertEquals(DxPipelineStateMapping.BLEND_SRC_COLOR, DxPipelineStateMapping.blendFactor(BlendFactor.SRC_COLOR, false));
	}

	@Test
	void everyBlendFactorOpCompareAndFillMapsToADistinctValidValue() {
		assertEquals(15, BlendFactor.values().length);
		for (BlendFactor factor : BlendFactor.values()) {
			int value = DxPipelineStateMapping.blendFactor(factor, false);
			assertTrue(value >= 1 && value <= 15 && value != 12 && value != 13);
		}
		assertEquals(DxPipelineStateMapping.OP_REV_SUBTRACT, DxPipelineStateMapping.blendOp(BlendOp.REVERSE_SUBTRACT));
		assertEquals(DxPipelineStateMapping.CMP_LESS_EQUAL, DxPipelineStateMapping.compareFunc(CompareOp.LESS_THAN_OR_EQUAL));
		assertEquals(DxPipelineStateMapping.CMP_ALWAYS, DxPipelineStateMapping.compareFunc(CompareOp.ALWAYS_PASS));
		assertEquals(DxPipelineStateMapping.CMP_NEVER, DxPipelineStateMapping.compareFunc(CompareOp.NEVER_PASS));
		assertEquals(DxPipelineStateMapping.FILL_WIREFRAME, DxPipelineStateMapping.fillMode(PolygonMode.WIREFRAME));
		assertEquals(DxPipelineStateMapping.FILL_SOLID, DxPipelineStateMapping.fillMode(PolygonMode.FILL));
	}

	@Test
	void colorWriteMaskMatchesD3d11Bits() {
		assertEquals(15, DxPipelineStateMapping.colorWriteMask(true, true, true, true));
		assertEquals(0, DxPipelineStateMapping.colorWriteMask(false, false, false, false));
		assertEquals(1 | 8, DxPipelineStateMapping.colorWriteMask(true, false, false, true));
	}

	@Test
	void vertexFormatsUsedByVanillaMapAndThreeComponentByteFormatsWiden() {
		assertEquals(6, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RGB32_FLOAT));
		assertEquals(0x10, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RG32_FLOAT));
		assertEquals(0x1c, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RGBA8_UNORM));
		assertEquals(0x1c, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RGB8_UNORM));
		assertEquals(0x1f, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RGB8_SNORM));
		assertEquals(0x26, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RG16_SINT));
		boolean rejected = false;
		try {
			DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.D32_FLOAT);
		} catch (UnsupportedOperationException expected) {
			rejected = true;
		}
		assertTrue(rejected);
	}

	@Test
	void descriptorFlattensInTheOrderTheNativeSideExpects() {
		int[] strides = new int[DxPipelineStateDescriptor.VERTEX_BUFFER_SLOT_COUNT];
		strides[0] = 16;
		DxPipelineStateDescriptor d = new DxPipelineStateDescriptor(
			4, 2,
			new int[] {1, 0},
			new int[] {5, 2},
			new int[] {6, 9},
			new int[] {1, 2},
			new int[] {2, 3}, new int[] {15, 4}, new int[] {3, 4}, new int[] {0xF, 0x0},
			true, false, 4, 3, 3, -1, 2.5F,
			new int[] {6, 0x1c}, new int[] {0, 12}, new int[] {0, 0}, new int[] {0, 0}, strides
		);
		int[] s = d.stateArray();
		assertEquals(DxPipelineStateDescriptor.STATE_COUNT, s.length);
		assertEquals(4, s[DxPipelineStateDescriptor.STATE_TOPOLOGY]);
		assertEquals(2, s[DxPipelineStateDescriptor.STATE_TARGET_COUNT]);
		assertEquals(3, s[DxPipelineStateDescriptor.STATE_CULL_MODE]);
		assertEquals(-1, s[DxPipelineStateDescriptor.STATE_DEPTH_BIAS]);
		assertEquals(4, s[DxPipelineStateDescriptor.STATE_DEPTH_FUNC]);
		assertEquals(0, s[DxPipelineStateDescriptor.STATE_DEPTH_WRITE]);
		assertEquals(1, s[DxPipelineStateDescriptor.STATE_DEPTH_TEST_ENABLE]);
		// Target 0 strip: blend on, src semi-transparent alpha, dst inv alpha.
		assertEquals(1, s[DxPipelineStateDescriptor.targetStateIndex(0, DxPipelineStateDescriptor.TARGET_STATE_BLEND_ENABLE)]);
		assertEquals(5, s[DxPipelineStateDescriptor.targetStateIndex(0, DxPipelineStateDescriptor.TARGET_STATE_SRC_COLOR)]);
		assertEquals(6, s[DxPipelineStateDescriptor.targetStateIndex(0, DxPipelineStateDescriptor.TARGET_STATE_DST_COLOR)]);
		assertEquals(2, s[DxPipelineStateDescriptor.targetStateIndex(0, DxPipelineStateDescriptor.TARGET_STATE_SRC_ALPHA)]);
		assertEquals(0xF, s[DxPipelineStateDescriptor.targetStateIndex(0, DxPipelineStateDescriptor.TARGET_STATE_WRITE_MASK)]);
		// Target 1 strip: unblended coefficient write, no-channel target indent (0xF only in target 0).
		assertEquals(0, s[DxPipelineStateDescriptor.targetStateIndex(1, DxPipelineStateDescriptor.TARGET_STATE_BLEND_ENABLE)]);
		assertEquals(0, s[DxPipelineStateDescriptor.targetStateIndex(1, DxPipelineStateDescriptor.TARGET_STATE_WRITE_MASK)]);
		assertEquals(2.5F, d.floatStateArray()[DxPipelineStateDescriptor.FSTATE_SLOPE_SCALED_DEPTH_BIAS]);
	}
}
