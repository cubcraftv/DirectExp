package dev.cubcraftv.directexp.client.shader;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-Java tests for {@link DxShaderInterfaceLayout}: the text it produces
 * for the shapes Mojang's shaders actually use, and the cases it must refuse.
 * (Separately, the produced text was fed to a real {@code glslangValidator}
 * with Vulkan semantics during authoring; that is not something a JVM unit
 * test can do, so it is noted in the milestone doc rather than asserted here.)
 */
class DxShaderInterfaceLayoutTest {
	private static final List<String> POSITION_COLOR = List.of("Position", "Color");
	private static final List<String> POSITION_TEX_COLOR = List.of("Position", "UV0", "Color");

	private static final String GUI_VERTEX = """
		#version 330

		layout(std140) uniform DynamicTransforms {
		    mat4 ModelViewMat;
		    vec4 ColorModulator;
		};

		layout(std140) uniform Projection {
		    mat4 ProjMat;
		};

		in vec3 Position;
		in vec4 Color;

		out vec4 vertexColor;

		void main() {
		    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
		    vertexColor = Color * ColorModulator;
		}
		""";

	private static final String GUI_FRAGMENT = """
		#version 330

		layout(std140) uniform DynamicTransforms {
		    mat4 ModelViewMat;
		    vec4 ColorModulator;
		};

		in vec4 vertexColor;

		out vec4 fragColor;

		void main() {
		    fragColor = vertexColor;
		}
		""";

	@Test
	void vertexInputsGetTheirVertexFormatElementIndex() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\nin vec4 Color;\nin vec3 Position;\nvoid main() {}\n",
			"#version 330\nout vec4 fragColor;\nvoid main() {}\n",
			POSITION_COLOR
		);

		// Declared Color-then-Position, but the format order decides.
		assertTrue(result.vertexSource().contains("layout(location = 1) in vec4 Color;"), result.vertexSource());
		assertTrue(result.vertexSource().contains("layout(location = 0) in vec3 Position;"), result.vertexSource());
	}

	@Test
	void blockCommentBeforeLineDirectiveDoesNotHideNextInput() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\n/*#moj_import <minecraft:projection.glsl>*/#line 6 0\nin vec3 Position;\nvoid main() {}\n",
			"#version 330\nout vec4 fragColor;\nvoid main() {}\n",
			List.of("Position")
		);

		assertTrue(result.vertexSource().contains("layout(location = 0) in vec3 Position;"), result.vertexSource());
	}

	@Test
	void fragmentInputsAreMatchedToVertexOutputsByNameNotByOrder() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\nout vec2 texCoord0;\nout vec4 vertexColor;\nout float fogDistance;\nvoid main() {}\n",
			"#version 330\nin float fogDistance;\nin vec2 texCoord0;\nout vec4 fragColor;\nvoid main() {}\n",
			List.of()
		);

		assertTrue(result.vertexSource().contains("layout(location = 0) out vec2 texCoord0;"));
		assertTrue(result.vertexSource().contains("layout(location = 1) out vec4 vertexColor;"));
		assertTrue(result.vertexSource().contains("layout(location = 2) out float fogDistance;"));
		// Fragment declares them in a different order and skips vertexColor.
		assertTrue(result.fragmentSource().contains("layout(location = 2) in float fogDistance;"), result.fragmentSource());
		assertTrue(result.fragmentSource().contains("layout(location = 0) in vec2 texCoord0;"), result.fragmentSource());
		assertTrue(result.fragmentSource().contains("layout(location = 0) out vec4 fragColor;"));
	}

	@Test
	void interpolationQualifiersStayAfterTheInsertedLayout() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\nin ivec2 UV2;\nflat out ivec2 lightUv;\nvoid main() {}\n",
			"#version 330\nflat in ivec2 lightUv;\nout vec4 fragColor;\nvoid main() {}\n",
			List.of("Position", "UV2")
		);

		assertTrue(result.vertexSource().contains("layout(location = 1) in ivec2 UV2;"));
		assertTrue(result.vertexSource().contains("layout(location = 0) flat out ivec2 lightUv;"));
		assertTrue(result.fragmentSource().contains("layout(location = 0) flat in ivec2 lightUv;"));
	}

	@Test
	void uniformBlocksAndSamplersShareOneBindingSpaceVertexStageFirst() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\n"
				+ "layout(std140) uniform DynamicTransforms { mat4 M; };\n"
				+ "layout(std140) uniform Projection { mat4 P; };\n"
				+ "uniform sampler2D Sampler2;\n"
				+ "void main() {}\n",
			"#version 330\n"
				+ "uniform sampler2D Sampler0;\n"
				+ "layout(std140) uniform Projection { mat4 P; };\n"
				+ "layout(std140) uniform Fog { vec4 c; };\n"
				+ "void main() {}\n",
			List.of()
		);

		// Vertex stage first: DynamicTransforms=0, Projection=1, Sampler2=2; then fragment adds Sampler0=3, Fog=4.
		assertTrue(result.vertexSource().contains("layout(binding = 0, std140) uniform DynamicTransforms"), result.vertexSource());
		assertTrue(result.vertexSource().contains("layout(binding = 1, std140) uniform Projection"));
		assertTrue(result.vertexSource().contains("layout(binding = 2) uniform sampler2D Sampler2;"));
		assertTrue(result.fragmentSource().contains("layout(binding = 3) uniform sampler2D Sampler0;"));
		// Same name in both stages keeps the same binding.
		assertTrue(result.fragmentSource().contains("layout(binding = 1, std140) uniform Projection"), result.fragmentSource());
		assertTrue(result.fragmentSource().contains("layout(binding = 4, std140) uniform Fog"));
	}

	@Test
	void texelBuffersAndOtherSamplerTypesAreNumberedToo() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\nuniform samplerBuffer Chunks;\nuniform samplerCube Env;\nuniform isampler2D Ids;\nvoid main() {}\n",
			"#version 330\nvoid main() {}\n",
			List.of()
		);

		assertTrue(result.vertexSource().contains("layout(binding = 0) uniform samplerBuffer Chunks;"));
		assertTrue(result.vertexSource().contains("layout(binding = 1) uniform samplerCube Env;"));
		assertTrue(result.vertexSource().contains("layout(binding = 2) uniform isampler2D Ids;"));
	}

	@Test
	void headerAddsExtensionsAndBuiltinDefinesAfterVersionAndKeepsLineNumbers() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(GUI_VERTEX, GUI_FRAGMENT, POSITION_COLOR);
		String vertex = result.vertexSource();

		assertTrue(vertex.startsWith("#version 330\n#extension GL_ARB_shading_language_420pack : require\n"
			+ "#extension GL_ARB_separate_shader_objects : require\n"
			+ "#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n#line 2\n"), vertex);

		// Every original line is still present, once, in the same order.
		String withoutInsertions = vertex.replaceAll("layout\\((location|binding) = \\d+\\) ", "").replaceAll("(binding|location) = \\d+, ", "");
		assertTrue(withoutInsertions.endsWith(GUI_VERTEX.substring(GUI_VERTEX.indexOf('\n') + 1)), withoutInsertions);
	}

	@Test
	void versionWithoutTrailingNewlineStillGetsAWellFormedHeader() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign("#version 330", "#version 330\nvoid main() {}\n", List.of());
		assertTrue(result.vertexSource().startsWith("#version 330\n#extension"), result.vertexSource());
	}

	@Test
	void commentsPreprocessorLinesFunctionBodiesAndStructsAreIgnored() throws Exception {
		String vertex = """
			#version 330
			#define IN in vec3 Ghost;
			// in vec3 LineComment;
			/* in vec3 BlockComment;
			   out vec4 alsoBlock; */
			struct Light { vec3 direction; };
			vec4 helper(in vec4 a, out vec4 b);
			vec4 helper(in vec4 a, out vec4 b) {
			    b = a;
			    return a;
			}
			in vec3 Position;
			void main() {
			    vec3 in_local = Position;
			}
			""";
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(vertex, "#version 330\nvoid main() {}\n", List.of("Position"));

		String out = result.vertexSource();
		assertEquals(1, count(out, "layout(location"), out);
		assertTrue(out.contains("layout(location = 0) in vec3 Position;"));
		assertTrue(out.contains("vec4 helper(in vec4 a, out vec4 b) {"), "function parameters must not be touched");
		assertFalse(out.contains("layout(location = 0) in vec3 Ghost"));
	}

	@Test
	void bothBranchesOfAnIfdefAgreeBecauseNumbersComeFromNames() throws Exception {
		String vertex = """
			#version 330
			in vec3 Position;
			#ifdef WITH_NORMAL
			in vec3 Normal;
			out vec3 lightDir;
			#else
			out vec3 lightDir;
			#endif
			out vec4 vertexColor;
			void main() {}
			""";
		String fragment = """
			#version 330
			in vec4 vertexColor;
			#ifdef WITH_NORMAL
			in vec3 lightDir;
			#endif
			out vec4 fragColor;
			void main() {}
			""";
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(vertex, fragment, List.of("Position", "Normal"));

		assertEquals(2, count(result.vertexSource(), "out vec3 lightDir;"));
		assertEquals(2, count(result.vertexSource(), "layout(location = 0) out vec3 lightDir;"), "both branches, same location");
		assertTrue(result.vertexSource().contains("layout(location = 1) out vec4 vertexColor;"));
		assertTrue(result.fragmentSource().contains("layout(location = 1) in vec4 vertexColor;"));
		assertTrue(result.fragmentSource().contains("layout(location = 0) in vec3 lightDir;"));
	}

	@Test
	void unmatchedNamesGetUnusedNumbersAndAreReportedAsNotesNotErrors() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\nin vec3 Position;\nin vec3 Normal;\nout vec4 a;\nvoid main() {}\n",
			"#version 330\nin vec4 a;\nin vec4 orphan;\nout vec4 fragColor;\nvoid main() {}\n",
			List.of("Position")
		);

		assertTrue(result.vertexSource().contains("layout(location = 1) in vec3 Normal;"), "first free number after the format's elements");
		assertTrue(result.fragmentSource().contains("layout(location = 1) in vec4 orphan;"), "first free number after the vertex outputs");
		assertEquals(2, result.notes().size(), result.notes().toString());
		assertTrue(result.notes().get(0).contains("Normal"));
		assertTrue(result.notes().get(1).contains("orphan"));
	}

	@Test
	void multipleFragmentOutputsAreNumberedInOrder() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\nvoid main() {}\n",
			"#version 330\nout vec4 color;\nout vec4 bloom;\nvoid main() {}\n",
			List.of()
		);
		assertTrue(result.fragmentSource().contains("layout(location = 0) out vec4 color;"));
		assertTrue(result.fragmentSource().contains("layout(location = 1) out vec4 bloom;"));
	}

	@Test
	void plainNonOpaqueUniformsAreLeftForTheCompilerToReport() throws Exception {
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(
			"#version 330\nuniform float Legacy;\nvoid main() {}\n", "#version 330\nvoid main() {}\n", List.of()
		);
		assertTrue(result.vertexSource().contains("\nuniform float Legacy;\n"));
		assertEquals(0, count(result.vertexSource(), "binding"));
	}

	@Test
	void refusesSourceWithoutVersion() {
		DxShaderInterfaceException error = assertThrows(DxShaderInterfaceException.class,
			() -> DxShaderInterfaceLayout.assign("void main() {}", "#version 330\nvoid main() {}\n", List.of()));
		assertTrue(error.getMessage().contains("vertex"));
		assertTrue(error.getMessage().contains("#version"));
	}

	@Test
	void refusesExplicitLocationsAndBindings() {
		assertThrows(DxShaderInterfaceException.class, () -> DxShaderInterfaceLayout.assign(
			"#version 330\nlayout(location = 3) in vec3 Position;\nvoid main() {}\n", "#version 330\nvoid main() {}\n", List.of("Position")));
		assertThrows(DxShaderInterfaceException.class, () -> DxShaderInterfaceLayout.assign(
			"#version 330\nvoid main() {}\n", "#version 330\nlayout(binding = 2) uniform sampler2D S;\nvoid main() {}\n", List.of()));
		assertThrows(DxShaderInterfaceException.class, () -> DxShaderInterfaceLayout.assign(
			"#version 330\nlayout(std140, binding = 0) uniform B { float x; };\nvoid main() {}\n", "#version 330\nvoid main() {}\n", List.of()));
	}

	@Test
	void refusesArraysAndCommaListsInStageInterfaceDeclarations() {
		assertThrows(DxShaderInterfaceException.class, () -> DxShaderInterfaceLayout.assign(
			"#version 330\nout float weights[4];\nvoid main() {}\n", "#version 330\nvoid main() {}\n", List.of()));
		assertThrows(DxShaderInterfaceException.class, () -> DxShaderInterfaceLayout.assign(
			"#version 330\nin vec2 a, b;\nvoid main() {}\n", "#version 330\nvoid main() {}\n", List.of("a", "b")));
		assertThrows(DxShaderInterfaceException.class, () -> DxShaderInterfaceLayout.assign(
			"#version 330\nuniform sampler2D Tex[2];\nvoid main() {}\n", "#version 330\nvoid main() {}\n", List.of()));
	}

	@Test
	void texturedVanillaStylePairEndToEnd() throws Exception {
		String vertex = """
			#version 330
			layout(std140) uniform DynamicTransforms { mat4 ModelViewMat; vec4 ColorModulator; };
			layout(std140) uniform Projection { mat4 ProjMat; };
			in vec3 Position;
			in vec2 UV0;
			in vec4 Color;
			out vec2 texCoord0;
			out vec4 vertexColor;
			void main() { gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0); }
			""";
		String fragment = """
			#version 330
			uniform sampler2D Sampler0;
			in vec2 texCoord0;
			in vec4 vertexColor;
			out vec4 fragColor;
			void main() { fragColor = texture(Sampler0, texCoord0) * vertexColor; }
			""";
		DxShaderInterfaceLayout.Result result = DxShaderInterfaceLayout.assign(vertex, fragment, POSITION_TEX_COLOR);

		assertTrue(result.vertexSource().contains("layout(location = 0) in vec3 Position;"));
		assertTrue(result.vertexSource().contains("layout(location = 1) in vec2 UV0;"));
		assertTrue(result.vertexSource().contains("layout(location = 2) in vec4 Color;"));
		assertTrue(result.fragmentSource().contains("layout(binding = 2) uniform sampler2D Sampler0;"));
		assertTrue(result.notes().isEmpty(), result.notes().toString());
	}

	private static int count(String haystack, String needle) {
		int count = 0;
		for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + needle.length())) {
			count++;
		}
		return count;
	}
}
