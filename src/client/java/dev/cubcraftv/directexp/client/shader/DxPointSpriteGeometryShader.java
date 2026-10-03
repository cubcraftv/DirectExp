package dev.cubcraftv.directexp.client.shader;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates the HLSL geometry shader that gives {@code POINTS} pipelines
 * their authored {@code gl_PointSize}: D3D11 has no point-size output, so
 * each point is expanded to a screen-aligned quad whose side is the point
 * size in pixels (GL point semantics), with every other vertex output
 * forwarded unchanged.
 *
 * <p>Input is the vertex shader's HLSL as SPIRV-Cross emitted it (after
 * {@link DxPointSizeRewriter} turned the builtin into an ordinary output). The
 * shader is generated from that text rather than from a fixed template because
 * its input/output struct must match the vertex stage's output signature
 * exactly, and that varies per pipeline. Only the emitted
 * {@code SPIRV_Cross_Output} struct is read: it is copied verbatim and the
 * members {@code gl_Position} and {@link DxPointSizeRewriter#OUTPUT_NAME} are
 * looked up by name.
 *
 * <p>Pixel-to-clip conversion: NDC spans 2 units over the viewport, so a
 * half-side of {@code size / 2} pixels is {@code size / viewport} NDC units,
 * and multiplying by clip-space {@code w} gives the clip-space offset. The
 * viewport size comes from a constant buffer at
 * {@code viewportCbufferSlot} that the native pipeline owns
 * ({@code DxBridge#setPipelineViewport}).
 *
 * <p>Corner order is (-,-), (-,+), (+,-), (+,+): a clockwise first
 * triangle, matching the rasterizer's {@code FrontCounterClockwise = FALSE}
 * so the quad is not back-face culled. (The vertex stage negates
 * {@code gl_Position.y} to emulate Vulkan's orientation, which also reverses
 * the winding Blaze3D's CCW front faces end up with in D3D.)
 */
public final class DxPointSpriteGeometryShader {
	static final String VERTEX_OUTPUT_STRUCT = "SPIRV_Cross_Output";

	private static final Pattern OUTPUT_STRUCT = Pattern.compile(
		"struct\\s+" + VERTEX_OUTPUT_STRUCT + "\\s*\\{([^}]*)\\}\\s*;"
	);

	private DxPointSpriteGeometryShader() {
	}

	/**
	 * @param vertexHlsl            the vertex stage's emitted HLSL
	 * @param viewportCbufferSlot   {@code b#} register for the viewport size constants
	 * @throws DxShaderInterfaceException if the vertex output struct or the members the expansion needs are missing
	 */
	public static String generate(String vertexHlsl, int viewportCbufferSlot) throws DxShaderInterfaceException {
		Objects.requireNonNull(vertexHlsl, "vertexHlsl");

		Matcher struct = OUTPUT_STRUCT.matcher(vertexHlsl);
		if (!struct.find()) {
			throw new DxShaderInterfaceException("vertex HLSL has no " + VERTEX_OUTPUT_STRUCT + " struct to build a point-sprite geometry shader from");
		}
		String body = struct.group(1);
		if (!hasMember(body, "gl_Position") || !hasMember(body, DxPointSizeRewriter.OUTPUT_NAME)) {
			throw new DxShaderInterfaceException(
				VERTEX_OUTPUT_STRUCT + " is missing gl_Position or " + DxPointSizeRewriter.OUTPUT_NAME + " (the point size output)"
			);
		}

		String size = "input[0]." + DxPointSizeRewriter.OUTPUT_NAME;
		StringBuilder hlsl = new StringBuilder(1024);
		hlsl.append(struct.group()).append("\n\n");
		hlsl.append("cbuffer DirectExpPointViewport : register(b").append(viewportCbufferSlot).append(")\n{\n");
		hlsl.append("\tfloat2 directexp_viewport;\n\tfloat2 directexp_viewport_pad;\n};\n\n");
		hlsl.append("[maxvertexcount(4)]\n");
		hlsl.append("void main(point ").append(VERTEX_OUTPUT_STRUCT).append(" input[1], inout TriangleStream<")
			.append(VERTEX_OUTPUT_STRUCT).append("> stream)\n{\n");
		hlsl.append("\tfloat4 center = input[0].gl_Position;\n");
		hlsl.append("\tfloat2 offset = (").append(size).append(" / directexp_viewport) * center.w;\n");
		hlsl.append("\t").append(VERTEX_OUTPUT_STRUCT).append(" v = input[0];\n");
		float[][] corners = {{-1F, -1F}, {-1F, 1F}, {1F, -1F}, {1F, 1F}};
		for (float[] corner : corners) {
			hlsl.append("\tv.gl_Position = float4(center.x ").append(corner[0] < 0 ? "-" : "+")
				.append(" offset.x, center.y ").append(corner[1] < 0 ? "-" : "+")
				.append(" offset.y, center.z, center.w);\n");
			hlsl.append("\tstream.Append(v);\n");
		}
		hlsl.append("}\n");
		return hlsl.toString();
	}

	private static boolean hasMember(String structBody, String name) {
		return Pattern.compile("\\b" + Pattern.quote(name) + "\\s*:").matcher(structBody).find();
	}
}