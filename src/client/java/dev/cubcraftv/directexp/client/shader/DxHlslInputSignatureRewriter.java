package dev.cubcraftv.directexp.client.shader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes the pixel-stage input struct use the vertex stage's output ordering.
 * D3D links these signatures by register/component, so independently emitted
 * HLSL structs must declare their semantics in the same order.
 */
public final class DxHlslInputSignatureRewriter {
	private static final String INPUT_STRUCT = "SPIRV_Cross_Input";
	private static final String OUTPUT_STRUCT = "SPIRV_Cross_Output";
	private static final Pattern STRUCT = Pattern.compile(
		"struct\\s+(SPIRV_Cross_Input|SPIRV_Cross_Output)\\s*\\{([^}]*)\\}\\s*;"
	);
	private static final Pattern FIELD = Pattern.compile(
		"^([ \\t]*)((?:(?:nointerpolation|linear|centroid|noperspective|sample)\\s+)*"
			+ "[A-Za-z_]\\w*(?:<[^;\\r\\n]+>)?)\\s+([A-Za-z_]\\w*)\\s*:\\s*([A-Za-z_]\\w*)\\s*;[ \\t]*$"
	);
	private static final Pattern TYPE = Pattern.compile(
		"(?:(?:nointerpolation|linear|centroid|noperspective|sample)\\s+)*([A-Za-z_]\\w*(?:<[^;\\r\\n]+>)?)"
	);

	private DxHlslInputSignatureRewriter() {
	}

	public static String rewrite(String vertexHlsl, String fragmentHlsl) throws DxShaderInterfaceException {
		Objects.requireNonNull(vertexHlsl, "vertexHlsl");
		Objects.requireNonNull(fragmentHlsl, "fragmentHlsl");

		Struct fragmentInput = findOptionalStruct(fragmentHlsl, INPUT_STRUCT, "fragment");
		if (fragmentInput == null) {
			return fragmentHlsl;
		}
		Struct vertexOutput = findOptionalStruct(vertexHlsl, OUTPUT_STRUCT, "vertex");
		if (vertexOutput == null) {
			throw new DxShaderInterfaceException("vertex HLSL has no " + OUTPUT_STRUCT + " struct");
		}
		List<Field> vertexFields = parseFields(vertexOutput.body(), "vertex output");
		List<Field> fragmentFields = parseFields(fragmentInput.body(), "fragment input");
		Map<String, Field> fragmentBySemantic = new HashMap<>();
		for (Field field : fragmentFields) {
			String key = semanticKey(field.semantic());
			if (fragmentBySemantic.putIfAbsent(key, field) != null) {
				throw new DxShaderInterfaceException("fragment input has duplicate semantic " + field.semantic());
			}
		}

		StringBuilder body = new StringBuilder();
		for (Field vertexField : vertexFields) {
			Field fragmentField = fragmentBySemantic.remove(semanticKey(vertexField.semantic()));
			if (fragmentField == null) {
				body.append(vertexField.declaration()).append('\n');
				continue;
			}
			if (!baseType(vertexField.type()).equals(baseType(fragmentField.type()))) {
				throw new DxShaderInterfaceException(
					"vertex output " + vertexField.semantic() + " has type " + vertexField.type()
						+ " but fragment input has type " + fragmentField.type()
				);
			}
			body.append(vertexField.indent()).append(fragmentField.type()).append(' ')
				.append(fragmentField.name()).append(" : ").append(vertexField.semantic()).append(";\n");
		}
		for (Field fragmentField : fragmentFields) {
			if (!fragmentBySemantic.containsKey(semanticKey(fragmentField.semantic()))) {
				continue;
			}
			if (!semanticKey(fragmentField.semantic()).startsWith("SV_")) {
				throw new DxShaderInterfaceException(
					"fragment input semantic " + fragmentField.semantic() + " has no vertex output"
				);
			}
			body.append(fragmentField.declaration()).append('\n');
		}

		return fragmentHlsl.substring(0, fragmentInput.bodyStart())
			+ body
			+ fragmentHlsl.substring(fragmentInput.bodyEnd());
	}

	private static Struct findOptionalStruct(String hlsl, String name, String stage) throws DxShaderInterfaceException {
		Matcher matcher = STRUCT.matcher(hlsl);
		Struct found = null;
		while (matcher.find()) {
			if (!matcher.group(1).equals(name)) {
				continue;
			}
			if (found != null) {
				throw new DxShaderInterfaceException(stage + " HLSL has multiple " + name + " structs");
			}
			found = new Struct(matcher.start(2), matcher.end(2), matcher.group(2));
		}
		return found;
	}

	private static List<Field> parseFields(String body, String description) throws DxShaderInterfaceException {
		List<Field> fields = new ArrayList<>();
		for (String line : body.split("\\R")) {
			if (line.isBlank()) {
				continue;
			}
			Matcher matcher = FIELD.matcher(line);
			if (!matcher.matches()) {
				throw new DxShaderInterfaceException("could not parse " + description + " field: " + line.trim());
			}
			fields.add(new Field(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4), line));
		}
		if (fields.isEmpty()) {
			throw new DxShaderInterfaceException(description + " struct is empty");
		}
		return fields;
	}

	private static String semanticKey(String semantic) {
		return semantic.toUpperCase(Locale.ROOT);
	}

	private static String baseType(String type) throws DxShaderInterfaceException {
		Matcher matcher = TYPE.matcher(type);
		if (!matcher.matches()) {
			throw new DxShaderInterfaceException("could not parse HLSL field type " + type);
		}
		return matcher.group(1);
	}

	private record Struct(int bodyStart, int bodyEnd, String body) {
	}

	private record Field(String indent, String type, String name, String semantic, String declaration) {
	}
}
