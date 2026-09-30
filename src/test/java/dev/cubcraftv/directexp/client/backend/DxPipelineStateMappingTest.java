package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import dev.cubcraftv.directexp.bridge.DxPipelineStateDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxPipelineStateMappingTest {
	@Test
	void quadsLinesAndFansUseTriangleList() {
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLELIST, DxPipelineStateMapping.topology(Mode.QUADS));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLELIST, DxPipelineStateMapping.topology(Mode.LINES));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLELIST, DxPipelineStateMapping.topology(Mode.TRIANGLES));
		// D3D11 has no fan topology: the pipeline is a triangle list and the draw path expands the indices.
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLELIST, DxPipelineStateMapping.topology(Mode.TRIANGLE_FAN));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_TRIANGLESTRIP, DxPipelineStateMapping.topology(Mode.TRIANGLE_STRIP));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_LINELIST, DxPipelineStateMapping.topology(Mode.DEBUG_LINES));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_LINESTRIP, DxPipelineStateMapping.topology(Mode.DEBUG_LINE_STRIP));
		assertEquals(DxPipelineStateMapping.TOPOLOGY_POINTLIST, DxPipelineStateMapping.topology(Mode.POINTS));
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
		DxPipelineStateDescriptor d = new DxPipelineStateDescriptor(
			4, true, 5, 6, 1, 2, 6, 1, 15, true, false, 4, 3, 3, -1, 2.5F, new int[] {6, 0x1c}, new int[] {0, 12}, 16
		);
		int[] s = d.stateArray();
		assertEquals(DxPipelineStateDescriptor.STATE_COUNT, s.length);
		assertEquals(4, s[DxPipelineStateDescriptor.STATE_TOPOLOGY]);
		assertEquals(1, s[DxPipelineStateDescriptor.STATE_BLEND_ENABLE]);
		assertEquals(5, s[DxPipelineStateDescriptor.STATE_SRC_COLOR]);
		assertEquals(6, s[DxPipelineStateDescriptor.STATE_DST_COLOR]);
		assertEquals(2, s[DxPipelineStateDescriptor.STATE_SRC_ALPHA]);
		assertEquals(15, s[DxPipelineStateDescriptor.STATE_COLOR_WRITE_MASK]);
		assertEquals(1, s[DxPipelineStateDescriptor.STATE_DEPTH_TEST_ENABLE]);
		assertEquals(0, s[DxPipelineStateDescriptor.STATE_DEPTH_WRITE]);
		assertEquals(4, s[DxPipelineStateDescriptor.STATE_DEPTH_FUNC]);
		assertEquals(3, s[DxPipelineStateDescriptor.STATE_CULL_MODE]);
		assertEquals(-1, s[DxPipelineStateDescriptor.STATE_DEPTH_BIAS]);
		assertEquals(2.5F, d.floatStateArray()[DxPipelineStateDescriptor.FSTATE_SLOPE_SCALED_DEPTH_BIAS]);
	}
}
