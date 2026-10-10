package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * G5: Sodium's compact chunk vertex format ({@code CompactChunkVertex}, 20 bytes
 * per vertex) through DirectExp's vertex-attribute mapping, including the three
 * integer attributes. The element list is what Sodium builds for its pipelines:
 *
 * <pre>
 *   a_Position     RG32_UINT    offset  0   (two packed 32-bit words)
 *   a_Color        RGBA8_UNORM  offset  8
 *   a_TexCoord     RG16_UINT    offset 12
 *   a_LightAndData RGBA8_UINT   offset 16
 * </pre>
 *
 * The DXGI numbers are asserted literally (the mapping's own table is what ends
 * up in the D3D11 input layout): R32G32_UINT = 17, R8G8B8A8_UNORM = 28,
 * R16G16_UINT = 36, R8G8B8A8_UINT = 30. The UINT-versus-UNORM distinction is the
 * point: an integer shader input ({@code uvec2}/{@code uvec4}) fed a normalised
 * format, or the reverse, makes D3D11 reject the input layout.
 */
class DxSodiumVertexFormatTest {
	private static VertexFormat sodiumCompactFormat() {
		return VertexFormat.builder(0)
			.addAttribute("a_Position", GpuFormat.RG32_UINT)
			.addAttribute("a_Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("a_TexCoord", GpuFormat.RG16_UINT)
			.addAttribute("a_LightAndData", GpuFormat.RGBA8_UINT)
			.build();
	}

	@Test
	void compactFormatLaysOutTwentyBytesWithTheOffsetsSodiumsShaderAssumes() {
		VertexFormat format = sodiumCompactFormat();

		assertEquals(20, format.getVertexSize());
		List<VertexFormatElement> elements = format.getElements();
		assertEquals(4, elements.size());
		assertEquals(0, elements.get(0).offset());
		assertEquals(8, elements.get(1).offset());
		assertEquals(12, elements.get(2).offset());
		assertEquals(16, elements.get(3).offset());
	}

	@Test
	void everyCompactAttributeMapsToTheMatchingDxgiFormat() {
		List<VertexFormatElement> elements = sodiumCompactFormat().getElements();

		assertEquals(0x11, DxPipelineStateMapping.vertexAttributeFormat(elements.get(0).format()), "a_Position (uvec2)");
		assertEquals(0x1c, DxPipelineStateMapping.vertexAttributeFormat(elements.get(1).format()), "a_Color (vec4, normalised)");
		assertEquals(0x24, DxPipelineStateMapping.vertexAttributeFormat(elements.get(2).format()), "a_TexCoord (uvec2)");
		assertEquals(0x1e, DxPipelineStateMapping.vertexAttributeFormat(elements.get(3).format()), "a_LightAndData (uvec4)");
	}

	@Test
	void integerAttributesStayUnsignedIntegerFormatsAndColorStaysNormalised() {
		// Guards against someone "fixing" the table by collapsing UINT and UNORM cases.
		assertEquals(0x1e, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RGBA8_UINT));
		assertEquals(0x1c, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RGBA8_UNORM));
		assertEquals(0x24, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RG16_UINT));
		assertEquals(0x23, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RG16_UNORM));
		assertEquals(0x11, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RG32_UINT));
		assertEquals(0x10, DxPipelineStateMapping.vertexAttributeFormat(GpuFormat.RG32_FLOAT));
	}
}
