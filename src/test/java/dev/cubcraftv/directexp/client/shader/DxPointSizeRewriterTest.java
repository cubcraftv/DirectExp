package dev.cubcraftv.directexp.client.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxPointSizeRewriterTest {
	private static final String POINT_VSH = """
		#version 330
		in vec3 Position;
		in vec4 Color;
		layout(std140) uniform LineWidthBlock { float LineWidth; };
		out vec4 vertexColor;
		void main() {
		    gl_Position = vec4(Position, 1.0);
		    gl_PointSize = LineWidth;
		    vertexColor = Color;
		}
		""";

	@Test
	void replacesTheBuiltinWithAnOutputDeclaredRightAfterTheVersionLine() {
		DxPointSizeRewriter.Result result = DxPointSizeRewriter.rewrite(POINT_VSH);
		assertTrue(result.usesPointSize());
		assertFalse(result.source().contains("gl_PointSize"));
		assertTrue(result.source().startsWith("#version 330\nout float DirectExpPointSize;\n"));
		assertTrue(result.source().contains("DirectExpPointSize = LineWidth;"));
	}

	@Test
	void rewrittenOutputGetsALocationFromTheInterfaceLayoutStep() throws Exception {
		DxPointSizeRewriter.Result result = DxPointSizeRewriter.rewrite(POINT_VSH);
		String fragment = "#version 330\nin vec4 vertexColor;\nout vec4 fragColor;\nvoid main() { fragColor = vertexColor; }\n";
		DxShaderInterfaceLayout.Result laidOut = DxShaderInterfaceLayout.assign(result.source(), fragment, java.util.List.of("Position", "Color"));
		assertTrue(laidOut.vertexSource().contains("location = 0) out float DirectExpPointSize"), laidOut.vertexSource());
		// the fragment stage's varying is matched by name and still lines up
		assertTrue(laidOut.vertexSource().contains("location = 1) out vec4 vertexColor"), laidOut.vertexSource());
		assertTrue(laidOut.fragmentSource().contains("location = 1) in vec4 vertexColor"), laidOut.fragmentSource());
	}

	@Test
	void leavesShadersWithoutTheBuiltinUntouched() {
		String source = "#version 330\nvoid main() { gl_Position = vec4(0.0); }\n";
		DxPointSizeRewriter.Result result = DxPointSizeRewriter.rewrite(source);
		assertFalse(result.usesPointSize());
		assertEquals(source, result.source());
	}

	@Test
	void ignoresCommentsAndLongerIdentifiers() {
		String source = "#version 330\n// gl_PointSize in a comment\n/* gl_PointSize */\nvoid main() { float gl_PointSizeScale = 1.0; }\n";
		DxPointSizeRewriter.Result result = DxPointSizeRewriter.rewrite(source);
		assertFalse(result.usesPointSize());
		assertEquals(source, result.source());
	}

	@Test
	void leavesGlPerVertexRedeclarationsAlone() {
		String source = "#version 330\nout gl_PerVertex { vec4 gl_Position; float gl_PointSize; };\nvoid main() {}\n";
		DxPointSizeRewriter.Result result = DxPointSizeRewriter.rewrite(source);
		assertFalse(result.usesPointSize());
		assertEquals(source, result.source());
	}
}
