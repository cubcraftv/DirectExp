package dev.cubcraftv.directexp.client.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxPointSpriteGeometryShaderTest {
	/** Shape of SPIRV-Cross's HLSL vertex output for the rewritten debug_point.vsh. */
	private static final String VERTEX_HLSL = """
		cbuffer LineWidthBlock : register(b0) { float LineWidth; };

		static float4 gl_Position;
		static float4 vertexColor;
		static float DirectExpPointSize;

		struct SPIRV_Cross_Output
		{
		    float4 vertexColor : TEXCOORD1;
		    float DirectExpPointSize : TEXCOORD0;
		    float4 gl_Position : SV_Position;
		};
		""";

	@Test
	void generatedShaderCopiesTheVertexOutputStructAndReadsTheViewportSlot() throws Exception {
		String gs = DxPointSpriteGeometryShader.generate(VERTEX_HLSL, 13);
		assertTrue(gs.contains("float4 vertexColor : TEXCOORD1;"));
		assertTrue(gs.contains("float4 gl_Position : SV_Position;"));
		assertTrue(gs.contains("register(b13)"));
		assertTrue(gs.contains("[maxvertexcount(4)]"));
		assertTrue(gs.contains("point SPIRV_Cross_Output input[1]"));
		assertTrue(gs.contains("TriangleStream<SPIRV_Cross_Output>"));
	}

	@Test
	void pointSizeInPixelsBecomesAClipSpaceOffsetScaledByW() throws Exception {
		String gs = DxPointSpriteGeometryShader.generate(VERTEX_HLSL, 13);
		assertTrue(gs.contains("(input[0].DirectExpPointSize / directexp_viewport) * center.w"), gs);
	}

	@Test
	void emitsFourCornersClockwiseAndForwardsEverythingElse() throws Exception {
		String gs = DxPointSpriteGeometryShader.generate(VERTEX_HLSL, 13);
		assertEquals(4, gs.split("stream.Append\\(v\\);", -1).length - 1);
		int a = gs.indexOf("center.x - offset.x, center.y - offset.y");
		int b = gs.indexOf("center.x - offset.x, center.y + offset.y");
		int c = gs.indexOf("center.x + offset.x, center.y - offset.y");
		int d = gs.indexOf("center.x + offset.x, center.y + offset.y");
		assertTrue(a > 0 && a < b && b < c && c < d, "corner order must be (-,-) (-,+) (+,-) (+,+)");
		assertTrue(gs.contains("SPIRV_Cross_Output v = input[0];"));
	}

	@Test
	void failsClearlyWhenTheVertexOutputIsNotWhatWeExpect() {
		assertThrows(DxShaderInterfaceException.class, () -> DxPointSpriteGeometryShader.generate("float4 x;", 13));
		String noSize = "struct SPIRV_Cross_Output { float4 gl_Position : SV_Position; };";
		assertThrows(DxShaderInterfaceException.class, () -> DxPointSpriteGeometryShader.generate(noSize, 13));
	}
}