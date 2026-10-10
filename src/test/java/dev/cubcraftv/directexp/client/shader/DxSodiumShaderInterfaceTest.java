package dev.cubcraftv.directexp.client.shader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G4/G5/G3 at the shader-interface level: the declarations Sodium's chunk
 * shaders actually make (integer vertex inputs, a push-constant block, an
 * {@code isamplerBuffer}) rewritten for D3D. Only the declaration lines are
 * reproduced; this is not Sodium's shader source.
 */
class DxSodiumShaderInterfaceTest {
	private static final List<String> SODIUM_INPUTS = List.of("a_Position", "a_Color", "a_TexCoord", "a_LightAndData");

	private static final String VERTEX = """
		#version 450

		layout(push_constant) uniform PushConstants {
		    vec3 u_RegionOffset;
		    int u_CurrentTime;
		    uint u_RegionID;
		};

		layout(std140) uniform Projection {
		    mat4 ProjMat;
		};

		uniform isamplerBuffer u_SectionTimeInfo;

		in uvec2 a_Position;
		in vec4 a_Color;
		in uvec2 a_TexCoord;
		in uvec4 a_LightAndData;

		out vec4 v_Color;

		void main() {
		    int t = texelFetch(u_SectionTimeInfo, int(u_RegionID) * 256).r;
		    gl_Position = ProjMat * vec4(vec3(a_Position.xyx) + u_RegionOffset, 1.0);
		    v_Color = a_Color * float(a_LightAndData.x + uint(t + u_CurrentTime));
		}
		""";

	private static final String FRAGMENT = """
		#version 450

		in vec4 v_Color;
		out vec4 fragColor;

		void main() {
		    fragColor = v_Color;
		}
		""";

	@Test
	void integerVertexInputsKeepTheirTypesAndGetLocationsInFormatOrder() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(VERTEX, FRAGMENT, SODIUM_INPUTS);

		assertTrue(result.vertexSource().contains("layout(location = 0) in uvec2 a_Position;"), result.vertexSource());
		assertTrue(result.vertexSource().contains("layout(location = 1) in vec4 a_Color;"), result.vertexSource());
		assertTrue(result.vertexSource().contains("layout(location = 2) in uvec2 a_TexCoord;"), result.vertexSource());
		assertTrue(result.vertexSource().contains("layout(location = 3) in uvec4 a_LightAndData;"), result.vertexSource());
	}

	@Test
	void pushConstantBlockBecomesANumberedStd140BlockUnderTheFixedName() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(VERTEX, FRAGMENT, SODIUM_INPUTS);

		assertFalse(result.vertexSource().contains("push_constant"), result.vertexSource());
		assertTrue(result.vertexSource().contains("std140) uniform " + DxShaderInterfaceLayout.PUSH_CONSTANT_BLOCK_NAME), result.vertexSource());
		// members stay addressable by their own names
		assertTrue(result.vertexSource().contains("vec3 u_RegionOffset;"), result.vertexSource());
		assertTrue(result.vertexSource().contains("uint u_RegionID;"), result.vertexSource());
	}

	@Test
	void texelBufferGetsABindingLikeASampledResource() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(VERTEX, FRAGMENT, SODIUM_INPUTS);

		assertTrue(result.vertexSource().matches("(?s).*layout\\(binding = \\d+\\) uniform isamplerBuffer u_SectionTimeInfo;.*"), result.vertexSource());
	}
}
