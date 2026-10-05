package dev.cubcraftv.directexp.client.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxHlslInputSignatureRewriterTest {
	private static final String VERTEX_HLSL = """
		struct SPIRV_Cross_Output
		{
		    float animation : TEXCOORD0;
		    float2 vertexUv : TEXCOORD1;
		    float4 gl_Position : SV_Position;
		};
		""";

	@Test
	void ordersFragmentInputLikeVertexOutputAndKeepsPixelMemberNames() throws Exception {
		String fragment = """
			struct SPIRV_Cross_Input
			{
			    float2 texCoord0 : TEXCOORD1;
			};
			void main()
			{
			    float2 uv = texCoord0;
			}
			""";

		String rewritten = DxHlslInputSignatureRewriter.rewrite(VERTEX_HLSL, fragment);

		int animation = rewritten.indexOf("float animation : TEXCOORD0;");
		int uv = rewritten.indexOf("float2 texCoord0 : TEXCOORD1;");
		int position = rewritten.indexOf("float4 gl_Position : SV_Position;");
		assertTrue(animation >= 0 && animation < uv);
		assertTrue(uv < position);
		assertTrue(rewritten.contains("float2 uv = texCoord0;"));
	}

	@Test
	void rejectsFragmentSemanticsMissingFromVertexOutput() {
		String fragment = "struct SPIRV_Cross_Input { float2 uv : TEXCOORD7; };";

		assertThrows(
			DxShaderInterfaceException.class,
			() -> DxHlslInputSignatureRewriter.rewrite(VERTEX_HLSL, fragment)
		);
	}

	@Test
	void rejectsMismatchedFieldTypes() {
		String fragment = "struct SPIRV_Cross_Input { float4 uv : TEXCOORD1; };";

		assertThrows(
			DxShaderInterfaceException.class,
			() -> DxHlslInputSignatureRewriter.rewrite(VERTEX_HLSL, fragment)
		);
	}

	@Test
	void preservesPixelSystemInputsThatAreNotVertexVaryings() throws Exception {
		String fragment = """
			struct SPIRV_Cross_Input
			{
			    bool frontFacing : SV_IsFrontFace;
			    float2 uv : TEXCOORD1;
			};
			""";

		String rewritten = DxHlslInputSignatureRewriter.rewrite(VERTEX_HLSL, fragment);

		assertTrue(rewritten.contains("bool frontFacing : SV_IsFrontFace;"));
		assertTrue(rewritten.indexOf("float2 uv : TEXCOORD1;") < rewritten.indexOf("bool frontFacing : SV_IsFrontFace;"));
	}

	@Test
	void leavesFragmentHlslWithoutAnInputStructUnchanged() throws Exception {
		String fragment = "float4 main() : SV_Target0 { return 1.0f.xxxx; }";

		assertSame(fragment, DxHlslInputSignatureRewriter.rewrite(VERTEX_HLSL, fragment));
	}
}
