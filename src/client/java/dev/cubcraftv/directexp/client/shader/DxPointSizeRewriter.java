package dev.cubcraftv.directexp.client.shader;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Milestone 4.6 {@code gl_PointSize} step, run on the vertex GLSL before
 * {@link DxShaderInterfaceLayout} and {@code shaderc}.
 *
 * <p>SPIR-V {@code BuiltIn PointSize} has no HLSL output that SPIRV-Cross can
 * emit (the {@code Unsupported builtin in HLSL: 1} failure of the
 * {@code debug_points} pipeline). Dropping the write, or forcing a constant
 * size, would lose {@code debug_point.vsh}'s {@code gl_PointSize = LineWidth}.
 * So every {@code gl_PointSize} token becomes an ordinary
 * {@code out float} named {@link #OUTPUT_NAME}; the interface-layout step
 * gives it a location like any other varying, SPIRV-Cross emits it as a
 * vertex-output semantic, and {@link DxPointSpriteGeometryShader} consumes it
 * to expand the point to a quad of that many pixels.
 *
 * <p>Same scanner limits as {@link DxShaderInterfaceLayout}: comments are
 * skipped, conditional compilation is not evaluated. A shader that redeclares
 * {@code gl_PerVertex} is left untouched (the builtin lives in a block there
 * and a plain rewrite would be wrong); Mojang's shaders never do this.
 */
public final class DxPointSizeRewriter {
	/** Name of the replacement vertex output; the geometry shader looks it up by this name. */
	public static final String OUTPUT_NAME = "DirectExpPointSize";

	private static final String BUILTIN = "gl_PointSize";
	private static final Pattern VERSION_LINE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version\\b[^\\n]*\\n?");

	private DxPointSizeRewriter() {
	}

	/** {@code usesPointSize} is false (and {@code source} unchanged) when there was nothing to rewrite. */
	public record Result(String source, boolean usesPointSize) {
	}

	public static Result rewrite(String vertexGlsl) {
		Objects.requireNonNull(vertexGlsl, "vertexGlsl");

		StringBuilder out = new StringBuilder(vertexGlsl.length() + 64);
		boolean found = false;
		boolean perVertexBlock = false;
		int length = vertexGlsl.length();
		int i = 0;
		while (i < length) {
			char c = vertexGlsl.charAt(i);
			if (c == '/' && i + 1 < length && vertexGlsl.charAt(i + 1) == '/') {
				int end = vertexGlsl.indexOf('\n', i);
				end = end < 0 ? length : end;
				out.append(vertexGlsl, i, end);
				i = end;
			} else if (c == '/' && i + 1 < length && vertexGlsl.charAt(i + 1) == '*') {
				int end = vertexGlsl.indexOf("*/", i + 2);
				end = end < 0 ? length : end + 2;
				out.append(vertexGlsl, i, end);
				i = end;
			} else if (Character.isLetter(c) || c == '_') {
				int end = i + 1;
				while (end < length && (Character.isLetterOrDigit(vertexGlsl.charAt(end)) || vertexGlsl.charAt(end) == '_')) {
					end++;
				}
				String token = vertexGlsl.substring(i, end);
				if (token.equals(BUILTIN)) {
					out.append(OUTPUT_NAME);
					found = true;
				} else {
					if (token.equals("gl_PerVertex")) {
						perVertexBlock = true;
					}
					out.append(token);
				}
				i = end;
			} else {
				out.append(c);
				i++;
			}
		}

		if (!found || perVertexBlock) {
			return new Result(vertexGlsl, false);
		}

		String declaration = "out float " + OUTPUT_NAME + ";\n";
		Matcher version = VERSION_LINE.matcher(out);
		if (version.find()) {
			int end = version.end();
			String versionLine = out.substring(version.start(), end);
			if (!versionLine.endsWith("\n")) {
				out.insert(end, "\n");
				end++;
			}
			out.insert(end, declaration);
		} else {
			out.insert(0, declaration);
		}
		return new Result(out.toString(), true);
	}
}
