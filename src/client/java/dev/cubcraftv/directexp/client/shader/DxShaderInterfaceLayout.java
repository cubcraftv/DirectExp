package dev.cubcraftv.directexp.client.shader;

import dev.cubcraftv.directexp.DirectExpMod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Milestone 4.6 (pipeline assembly), between the 4.2 source stage and the
 * 4.3 IR stage: gives every shader interface variable an explicit
 * {@code location} / {@code binding} so {@code shaderc} accepts Mojang's
 * GLSL, and so the numbers mean what the rest of DirectExp assumes.
 *
 * <h2>Why this exists</h2>
 * Mojang's shaders are GL-style ({@code #version 330}): {@code in vec3
 * Position;}, {@code layout(std140) uniform Projection {...};},
 * {@code uniform sampler2D Sampler0;} -- none of them carry a location or
 * binding. {@code shaderc} compiles with Vulkan semantics, where those are
 * mandatory, so the 4.3 compile failed on every pipeline with three
 * error kinds (2244 / 968 / 180 occurrences in the first {@code runClient}
 * log): "SPIR-V requires location for user input/output", "uniform/buffer
 * blocks require layout(binding=X)", "sampler/texture/image requires
 * layout(binding=X)". A fourth kind, {@code gl_VertexID} undeclared, hit
 * the full-screen-quad shaders.
 *
 * <h2>What Vulkan does (decompiled 26.2 snapshot 1) and how this mirrors it</h2>
 * {@code GlslCompiler} sets shaderc's auto-map-locations and
 * auto-bind-uniforms so the compile succeeds, then
 * {@code IntermediaryShaderModule.rebind} patches the Location/Binding
 * operands directly in the SPIR-V words:
 * <ul>
 *   <li>vertex input named {@code vertexInputs[i]} (i.e.
 *   {@code VertexFormat.getElementAttributeNames()}) gets location
 *   {@code i};</li>
 *   <li>vertex output number {@code i} (order of appearance) gets location
 *   {@code i}; a fragment input is matched to the vertex output of the same
 *   <em>name</em>; fragment outputs get {@code 0, 1, ...};</li>
 *   <li>uniform blocks, texel buffers and samplers share one binding space
 *   whose numbering is the position in a bind-group entry list built vertex
 *   stage first, then fragment stage, de-duplicated by name (all in
 *   descriptor set 0).</li>
 * </ul>
 * DirectExp does not patch SPIR-V (its reflection only reads it), so the same
 * assignment is applied to the GLSL text instead, by inserting
 * {@code layout(location = N)} / {@code layout(binding = N)}. The numbering
 * rules above are reproduced exactly, with one deliberate difference:
 * numbering follows <em>textual</em> declaration order rather than SPIR-V
 * reflection order, because it has to be decided before compiling. Numbers
 * only need to be consistent and unique, and this scheme is, so the
 * difference is invisible to everything downstream: the 4.4 layout is keyed
 * by resource <em>name</em>, and 4.5's {@code add_hlsl_resource_binding} is
 * keyed by {@code (stage, set, binding)} (unique here).
 *
 * <h2>Why the {@code #extension} lines</h2>
 * Under {@code #version 330}, glslang rejects {@code layout(binding=...)}
 * and {@code layout(location=...)} on in/out ("not supported for this
 * version or the enabled extensions"; checked against glslang 15.1.0). The
 * minimal working pair is {@code GL_ARB_shading_language_420pack} (binding)
 * plus {@code GL_ARB_separate_shader_objects} (location on in/out); either
 * alone still fails. {@code gl_VertexID}/{@code gl_InstanceID} are not
 * declared under Vulkan semantics, so the same two defines Vulkan's
 * {@code GlslCompiler} injects are injected here too.
 *
 * <h2>Push constants</h2>
 * Some shaders (Sodium's terrain shader, under {@code #ifdef VULKAN}, which
 * {@code shaderc} defines itself for Vulkan semantics) declare a
 * {@code layout(push_constant) uniform Name { ... };} block for values that
 * change per draw. D3D11 has no push constants, and numbering such a block like
 * an ordinary uniform block makes glslang fail ("'binding' : cannot be used
 * with push_constant"). This stage therefore rewrites it to an ordinary
 * {@code layout(binding = N, std140) uniform DxPushConstants { ... }} block:
 * the {@code push_constant} qualifier is dropped, a binding is assigned in the
 * shared binding space, and the block <em>type</em> name is replaced by
 * {@link #PUSH_CONSTANT_BLOCK_NAME} (members stay addressable by their own
 * names, so the shader body is untouched). Downstream it is just a constant
 * buffer under that fixed name, which the draw path fills from the bytes the
 * caller handed to {@code DxPushConstants.setPushConstants}.
 * <p>
 * Push-constant blocks default to {@code std430} while the rewritten block is
 * {@code std140}; the two agree for scalars, vectors, {@code mat3}/{@code mat4}
 * and the like, and disagree for arrays and {@code matCx2}. Rather than risk
 * silently shifted data, any block member that is not a plain scalar, vector or
 * non-2-row matrix is refused with a {@link DxShaderInterfaceException}.
 *
 * <h2>Scope and limits</h2>
 * A small tokenizer, not a GLSL parser: it skips comments and
 * preprocessor lines and looks at declaration statements at brace depth
 * zero only (so {@code in}/{@code out} function parameters are never
 * touched). Conditional compilation is <em>not</em> evaluated: declarations
 * inside inactive {@code #ifdef} branches are numbered too. That is
 * harmless because every number is derived from the variable's
 * <em>name</em>, so both branches of an {@code #ifdef} agree, and unused
 * numbers just leave gaps. Unsupported shapes (arrays or comma lists in
 * in/out declarations, or a declaration that already has an explicit
 * {@code location}/{@code binding}) fail loudly with a
 * {@link DxShaderInterfaceException} rather than being guessed at.
 */
public final class DxShaderInterfaceLayout {
	/**
	 * Inserted right after the {@code #version} line. Both are needed, see
	 * the class javadoc.
	 */
	static final String EXTENSIONS =
		"#extension GL_ARB_shading_language_420pack : require\n"
			+ "#extension GL_ARB_separate_shader_objects : require\n";

	/** The same two defines Vulkan's {@code GlslCompiler} injects globally. */
	/**
	 * The fixed block name every {@code push_constant} block is renamed to, and
	 * therefore the name its constant buffer has in the reflected resource
	 * layout. Vulkan allows one push-constant block per pipeline, so one name
	 * is enough; both stages declaring it merge into one entry.
	 */
	public static final String PUSH_CONSTANT_BLOCK_NAME = "DxPushConstants";

	static final String VULKAN_BUILTIN_DEFINES =
		"#define gl_VertexID gl_VertexIndex\n"
			+ "#define gl_InstanceID gl_InstanceIndex\n";

	private static final Pattern VERSION_LINE = Pattern.compile("(?m)^[ \\t]*#[ \\t]*version\\b[^\\n]*\\n?");
	private static final Pattern SAMPLER_TYPE = Pattern.compile("[iu]?sampler(1D|2D|3D|Cube|2DRect|1DArray|2DArray|CubeArray|Buffer|2DMS|2DMSArray)(Shadow)?");
	private static final Set<String> QUALIFIERS = Set.of(
		"in", "out", "inout", "uniform", "buffer", "const", "flat", "smooth", "noperspective", "centroid",
		"sample", "patch", "invariant", "precise", "highp", "mediump", "lowp", "coherent", "volatile",
		"restrict", "readonly", "writeonly"
	);

	private DxShaderInterfaceLayout() {
	}

	/**
	 * Both stages after assignment, plus human-readable notes about anything
	 * that did not line up (e.g. a vertex input the vertex format does not
	 * provide). Notes are diagnostics only; they never fail the compile,
	 * because an unmatched name can legitimately sit in an inactive
	 * {@code #ifdef} branch.
	 */
	public record Result(String vertexSource, String fragmentSource, List<String> notes) {
	}

	/**
	 * Adapter for the real pipeline path: rewrites the
	 * {@code preprocessedSource} of both stages (define injection has already
	 * happened) and keeps id/type/rawSource untouched. The SPIR-V cache keys on
	 * the preprocessed text, so pipelines that share a shader but differ in
	 * vertex format correctly get distinct cache entries.
	 *
	 * @param vertexInputNames attribute names in the first vertex binding's element order
	 */
	public static DxPreprocessedShaderPipelineSource apply(DxPreprocessedShaderPipelineSource source, List<String> vertexInputNames) throws DxShaderInterfaceException {
		Objects.requireNonNull(source, "source");
		Result result = assign(source.vertex().preprocessedSource(), source.fragment().preprocessedSource(), vertexInputNames);

		for (String note : result.notes()) {
			DirectExpMod.LOGGER.debug("Shader interface note for pipeline {}: {}", source.pipelineDebugLabel(), note);
		}

		DxPreprocessedShaderSource vertex = source.vertex();
		DxPreprocessedShaderSource fragment = source.fragment();
		return new DxPreprocessedShaderPipelineSource(
			source.pipelineDebugLabel(),
			new DxPreprocessedShaderSource(vertex.id(), vertex.type(), vertex.rawSource(), result.vertexSource()),
			new DxPreprocessedShaderSource(fragment.id(), fragment.type(), fragment.rawSource(), result.fragmentSource())
		);
	}

	/** Minecraft-free core: see the class javadoc for the exact rules. */
	public static Result assign(String vertexGlsl, String fragmentGlsl, List<String> vertexInputNames) throws DxShaderInterfaceException {
		Objects.requireNonNull(vertexGlsl, "vertexGlsl");
		Objects.requireNonNull(fragmentGlsl, "fragmentGlsl");
		Objects.requireNonNull(vertexInputNames, "vertexInputNames");

		List<Decl> vertexDecls = parse(vertexGlsl, "vertex");
		List<Decl> fragmentDecls = parse(fragmentGlsl, "fragment");
		List<String> notes = new ArrayList<>();

		// Vertex inputs: location = element index in the vertex format.
		Map<String, Integer> inputLocations = new HashMap<>();
		for (int i = 0; i < vertexInputNames.size(); i++) {
			inputLocations.putIfAbsent(vertexInputNames.get(i), i);
		}
		int nextUnmatchedInput = vertexInputNames.size();

		// Vertex outputs: location = order of first appearance; fragment
		// inputs are matched to them by name.
		Map<String, Integer> varyingLocations = new LinkedHashMap<>();
		for (Decl decl : vertexDecls) {
			if (decl.kind == Kind.OUT) {
				varyingLocations.computeIfAbsent(decl.name, name -> varyingLocations.size());
			}
		}

		// One binding space for blocks and samplers, vertex stage first.
		Map<String, Integer> bindings = new LinkedHashMap<>();
		for (List<Decl> decls : List.of(vertexDecls, fragmentDecls)) {
			for (Decl decl : decls) {
				if (decl.kind == Kind.UNIFORM_BLOCK || decl.kind == Kind.SAMPLER || decl.kind == Kind.PUSH_CONSTANT_BLOCK) {
					bindings.computeIfAbsent(decl.name, name -> bindings.size());
				}
			}
		}

		List<Edit> vertexEdits = new ArrayList<>();
		for (Decl decl : vertexDecls) {
			switch (decl.kind) {
				case IN -> {
					Integer location = inputLocations.get(decl.name);
					if (location == null) {
						location = nextUnmatchedInput++;
						inputLocations.put(decl.name, location);
						notes.add("vertex input '" + decl.name + "' is not in the vertex format; assigned unused location " + location);
					}
					vertexEdits.add(decl.edit("location", location));
				}
				case OUT -> vertexEdits.add(decl.edit("location", varyingLocations.get(decl.name)));
				case UNIFORM_BLOCK, SAMPLER -> vertexEdits.add(decl.edit("binding", bindings.get(decl.name)));
				case PUSH_CONSTANT_BLOCK -> vertexEdits.addAll(decl.pushConstantEdits(bindings.get(decl.name)));
			}
		}

		List<Edit> fragmentEdits = new ArrayList<>();
		Map<String, Integer> fragmentOutputs = new LinkedHashMap<>();
		int nextUnmatchedVarying = varyingLocations.size();
		for (Decl decl : fragmentDecls) {
			switch (decl.kind) {
				case IN -> {
					Integer location = varyingLocations.get(decl.name);
					if (location == null) {
						location = nextUnmatchedVarying++;
						varyingLocations.put(decl.name, location);
						notes.add("fragment input '" + decl.name + "' has no matching vertex output; assigned unused location " + location);
					}
					fragmentEdits.add(decl.edit("location", location));
				}
				case OUT -> fragmentEdits.add(decl.edit("location", fragmentOutputs.computeIfAbsent(decl.name, name -> fragmentOutputs.size())));
				case UNIFORM_BLOCK, SAMPLER -> fragmentEdits.add(decl.edit("binding", bindings.get(decl.name)));
				case PUSH_CONSTANT_BLOCK -> fragmentEdits.addAll(decl.pushConstantEdits(bindings.get(decl.name)));
			}
		}

		return new Result(
			rewrite(vertexGlsl, vertexEdits, "vertex"),
			rewrite(fragmentGlsl, fragmentEdits, "fragment"),
			List.copyOf(notes)
		);
	}

	// ---------------------------------------------------------------------
	// Rewriting
	// ---------------------------------------------------------------------

	private enum Kind {
		IN, OUT, UNIFORM_BLOCK, SAMPLER, PUSH_CONSTANT_BLOCK
	}

	/**
	 * One text edit at a character offset of the original source: the
	 * {@code deleteLength} characters at {@code position} (usually 0, a pure
	 * insertion) are replaced by {@code text}.
	 */
	private record Edit(int position, int deleteLength, String text) {
	}

	/**
	 * What a {@code layout(push_constant)} block rewrite needs: the character
	 * range of the first {@code layout(...)} body, the qualifiers that survive
	 * the rewrite, and the range of the block's type name.
	 */
	private record PushBlock(int layoutBodyStart, int layoutBodyEnd, List<String> keptQualifiers, int nameStart, int nameEnd) {
	}

	/**
	 * A declaration to number. {@code layoutBodyStart} is the offset just
	 * after the opening parenthesis of an existing {@code layout(...)}
	 * qualifier (so {@code std140} can be merged into
	 * {@code layout(binding = 0, std140)}), or -1 if there is none.
	 */
	private record Decl(Kind kind, String name, int statementStart, int layoutBodyStart, PushBlock push) {
		Decl(Kind kind, String name, int statementStart, int layoutBodyStart) {
			this(kind, name, statementStart, layoutBodyStart, null);
		}

		Edit edit(String qualifier, int value) {
			if (this.layoutBodyStart >= 0) {
				return new Edit(this.layoutBodyStart, 0, qualifier + " = " + value + ", ");
			}
			return new Edit(this.statementStart, 0, "layout(" + qualifier + " = " + value + ") ");
		}

		/** The two edits (layout qualifiers, block name) that turn a push-constant block into a numbered std140 uniform block. */
		List<Edit> pushConstantEdits(int binding) {
			StringBuilder body = new StringBuilder("binding = ").append(binding);
			for (String qualifier : this.push.keptQualifiers) {
				body.append(", ").append(qualifier);
			}
			if (this.push.keptQualifiers.stream().noneMatch(DxShaderInterfaceLayout::isBlockLayoutQualifier)) {
				body.append(", std140");
			}
			return List.of(
				new Edit(this.push.layoutBodyStart, this.push.layoutBodyEnd - this.push.layoutBodyStart, body.toString()),
				new Edit(this.push.nameStart, this.push.nameEnd - this.push.nameStart, PUSH_CONSTANT_BLOCK_NAME)
			);
		}
	}

	private static boolean isBlockLayoutQualifier(String qualifier) {
		return qualifier.equals("std140") || qualifier.equals("std430") || qualifier.equals("shared") || qualifier.equals("packed") || qualifier.equals("scalar");
	}

	private static String rewrite(String source, List<Edit> edits, String stageName) throws DxShaderInterfaceException {
		Matcher version = VERSION_LINE.matcher(source);
		if (!version.find()) {
			throw new DxShaderInterfaceException("The " + stageName + " shader has no #version directive.");
		}

		// Everything inserted after #version is followed by a #line so
		// compiler diagnostics keep the line numbers of the text this stage
		// was given (the numbers already seen in earlier logs).
		int afterVersion = version.end();
		int nextLine = 1;
		for (int i = 0; i < afterVersion; i++) {
			if (source.charAt(i) == '\n') {
				nextLine++;
			}
		}
		boolean versionEndsWithNewline = version.group().endsWith("\n");
		String header = (versionEndsWithNewline ? "" : "\n") + EXTENSIONS + VULKAN_BUILTIN_DEFINES + "#line " + nextLine + "\n";

		List<Edit> all = new ArrayList<>(edits);
		all.add(new Edit(afterVersion, 0, header));
		// Apply back to front so earlier offsets stay valid; stable for equal
		// positions is not needed (no two edits share an offset, and a
		// replacement never overlaps another edit's range).
		all.sort((a, b) -> Integer.compare(b.position, a.position));

		StringBuilder builder = new StringBuilder(source);
		for (Edit edit : all) {
			builder.replace(edit.position, edit.position + edit.deleteLength, edit.text);
		}
		return builder.toString();
	}

	// ---------------------------------------------------------------------
	// Scanning
	// ---------------------------------------------------------------------

	private record Token(String text, int start, int end) {
		boolean is(String value) {
			return this.text.equals(value);
		}

		boolean isIdentifier() {
			char first = this.text.charAt(0);
			return Character.isLetter(first) || first == '_';
		}
	}

	private enum StatementKind {
		DECLARATION, BLOCK_HEADER
	}

	/** {@code body} holds the tokens between a block's braces (empty for plain declarations). */
	private record Statement(StatementKind kind, List<Token> tokens, List<Token> body) {
	}

	private static List<Decl> parse(String source, String stageName) throws DxShaderInterfaceException {
		List<Decl> decls = new ArrayList<>();
		for (Statement statement : splitTopLevelStatements(tokenize(source))) {
			Decl decl = analyze(statement, stageName, source);
			if (decl != null) {
				decls.add(decl);
			}
		}
		return decls;
	}

	private static List<Token> tokenize(String source) {
		List<Token> tokens = new ArrayList<>();
		int length = source.length();
		boolean atLineStart = true;
		int i = 0;
		while (i < length) {
			char c = source.charAt(i);
			if (c == '\n') {
				atLineStart = true;
				i++;
			} else if (Character.isWhitespace(c)) {
				i++;
			} else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
				while (i < length && source.charAt(i) != '\n') {
					i++;
				}
			} else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
				int close = source.indexOf("*/", i + 2);
				int newline = source.indexOf('\n', i + 2);
				if (newline >= 0 && (close < 0 || newline < close)) {
					atLineStart = true;
				}
				i = close < 0 ? length : close + 2;
			} else if (c == '#' && atLineStart) {
				// Preprocessor directive: the rest of the line, honoring
				// backslash continuations.
				while (i < length) {
					char d = source.charAt(i);
					if (d == '\n') {
						boolean continued = i > 0 && (source.charAt(i - 1) == '\\'
							|| (i > 1 && source.charAt(i - 1) == '\r' && source.charAt(i - 2) == '\\'));
						if (!continued) {
							break;
						}
					}
					i++;
				}
			} else if (Character.isLetter(c) || c == '_') {
				int start = i;
				while (i < length && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
					i++;
				}
				tokens.add(new Token(source.substring(start, i), start, i));
				atLineStart = false;
			} else if (Character.isDigit(c)) {
				int start = i;
				while (i < length && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '.')) {
					i++;
				}
				tokens.add(new Token(source.substring(start, i), start, i));
				atLineStart = false;
			} else {
				tokens.add(new Token(String.valueOf(c), i, i + 1));
				i++;
				atLineStart = false;
			}
		}
		return tokens;
	}

	/**
	 * Splits the token stream into statements that end at a top-level
	 * {@code ;}, and block headers (the tokens before a top-level {@code {}
	 * that is not a function body). Function bodies, block bodies and
	 * anything nested deeper are skipped entirely.
	 */
	private static List<Statement> splitTopLevelStatements(List<Token> tokens) {
		List<Statement> statements = new ArrayList<>();
		List<Token> current = new ArrayList<>();
		int braceDepth = 0;
		int parenDepth = 0;
		boolean skipTrailerUntilSemicolon = false;
		boolean lastBodyWasFunction = false;
		// Filled while a non-function body is scanned; it belongs to the
		// BLOCK_HEADER statement added when the body's '{' was seen.
		List<Token> currentBody = null;

		for (Token token : tokens) {
			if (braceDepth > 0) {
				boolean closesBody = false;
				if (token.is("{")) {
					braceDepth++;
				} else if (token.is("}")) {
					braceDepth--;
					closesBody = braceDepth == 0;
					if (braceDepth == 0) {
						// A function body ends the statement; a block or
						// struct body is followed by an optional instance
						// name and a semicolon that must be swallowed.
						skipTrailerUntilSemicolon = !lastBodyWasFunction;
					}
				}
				if (currentBody != null && !closesBody) {
					currentBody.add(token);
				}
				continue;
			}

			if (skipTrailerUntilSemicolon) {
				if (token.is(";")) {
					skipTrailerUntilSemicolon = false;
				}
				continue;
			}

			if (token.is("(")) {
				parenDepth++;
			} else if (token.is(")")) {
				parenDepth--;
			}

			if (token.is("{") && parenDepth == 0) {
				boolean isFunction = current.stream().anyMatch(t -> t.is("(")) && !startsWithLayoutOnlyParens(current);
				currentBody = null;
				if (!isFunction) {
					currentBody = new ArrayList<>();
					statements.add(new Statement(StatementKind.BLOCK_HEADER, List.copyOf(current), currentBody));
				}
				lastBodyWasFunction = isFunction;
				current = new ArrayList<>();
				braceDepth = 1;
				continue;
			}

			if (token.is(";") && parenDepth == 0) {
				if (!current.isEmpty()) {
					statements.add(new Statement(StatementKind.DECLARATION, List.copyOf(current), List.of()));
				}
				current = new ArrayList<>();
				continue;
			}

			current.add(token);
		}
		return statements;
	}

	/**
	 * True when every parenthesis in the header belongs to a leading
	 * {@code layout(...)} qualifier, i.e. this is {@code layout(std140)
	 * uniform Name}, not {@code vec4 f(int x)}.
	 */
	private static boolean startsWithLayoutOnlyParens(List<Token> header) {
		int i = 0;
		while (i < header.size()) {
			Token token = header.get(i);
			if (token.is("layout") && i + 1 < header.size() && header.get(i + 1).is("(")) {
				i = matchingParen(header, i + 1) + 1;
			} else if (QUALIFIERS.contains(token.text)) {
				i++;
			} else {
				break;
			}
		}
		for (int j = i; j < header.size(); j++) {
			if (header.get(j).is("(")) {
				return false;
			}
		}
		return true;
	}

	private static int matchingParen(List<Token> tokens, int openIndex) {
		int depth = 0;
		for (int i = openIndex; i < tokens.size(); i++) {
			if (tokens.get(i).is("(")) {
				depth++;
			} else if (tokens.get(i).is(")")) {
				depth--;
				if (depth == 0) {
					return i;
				}
			}
		}
		return tokens.size() - 1;
	}

	/** Returns the declaration to number, or null if this statement is not one. */
	private static Decl analyze(Statement statement, String stageName, String source) throws DxShaderInterfaceException {
		List<Token> tokens = statement.tokens();
		if (tokens.isEmpty()) {
			return null;
		}

		boolean in = false;
		boolean out = false;
		boolean uniform = false;
		boolean hasLocation = false;
		boolean hasBinding = false;
		boolean pushConstant = false;
		int layoutBodyStart = -1;
		int layoutBodyEnd = -1;
		List<String> firstLayoutQualifiers = List.of();

		int i = 0;
		while (i < tokens.size()) {
			Token token = tokens.get(i);
			if (token.is("layout") && i + 1 < tokens.size() && tokens.get(i + 1).is("(")) {
				int close = matchingParen(tokens, i + 1);
				boolean firstGroup = layoutBodyStart < 0;
				if (firstGroup) {
					layoutBodyStart = tokens.get(i + 1).end();
					layoutBodyEnd = tokens.get(close).start();
					firstLayoutQualifiers = splitLayoutQualifiers(tokens, i + 2, close, source);
				}
				for (int k = i + 2; k < close; k++) {
					hasLocation |= tokens.get(k).is("location");
					hasBinding |= tokens.get(k).is("binding");
					if (tokens.get(k).is("push_constant")) {
						pushConstant = true;
						if (!firstGroup) {
							throw unsupported(stageName, "push_constant", "push_constant in a second layout(...) group");
						}
					}
				}
				i = close + 1;
			} else if (QUALIFIERS.contains(token.text)) {
				in |= token.is("in");
				out |= token.is("out");
				uniform |= token.is("uniform");
				i++;
			} else {
				break;
			}
		}

		if (i >= tokens.size()) {
			return null;
		}
		int statementStart = tokens.get(0).start();
		Token first = tokens.get(i);

		if (statement.kind() == StatementKind.BLOCK_HEADER) {
			// Only `uniform Name {` blocks are numbered. `in`/`out`
			// interface blocks are left alone; shaderc will report them.
			if (!uniform || in || out || !first.isIdentifier()) {
				return null;
			}
			if (hasBinding) {
				throw explicit(stageName, first.text, "binding");
			}
			if (pushConstant) {
				return pushConstantDecl(stageName, first, statement.body(), layoutBodyStart, layoutBodyEnd, firstLayoutQualifiers, statementStart);
			}
			return new Decl(Kind.UNIFORM_BLOCK, first.text, statementStart, layoutBodyStart);
		}

		if (uniform) {
			if (in || out || !SAMPLER_TYPE.matcher(first.text).matches()) {
				return null; // a plain `uniform float x;` etc.: not ours to number
			}
			Token name = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
			if (name == null || !name.isIdentifier()) {
				throw unsupported(stageName, first.text, "unrecognised sampler declaration");
			}
			if (i + 2 < tokens.size()) {
				throw unsupported(stageName, name.text, "arrays or lists of samplers");
			}
			if (hasBinding) {
				throw explicit(stageName, name.text, "binding");
			}
			return new Decl(Kind.SAMPLER, name.text, statementStart, layoutBodyStart);
		}

		if (in == out) {
			return null; // neither (or `in` and `out` together): not a stage interface variable
		}

		Token name = i + 1 < tokens.size() ? tokens.get(i + 1) : null;
		if (name == null || !name.isIdentifier() || first.is("struct")) {
			throw unsupported(stageName, first.text, "unrecognised " + (in ? "in" : "out") + " declaration");
		}
		if (i + 2 < tokens.size()) {
			throw unsupported(stageName, name.text, "arrays or comma-separated declarators");
		}
		if (hasLocation) {
			throw explicit(stageName, name.text, "location");
		}
		return new Decl(in ? Kind.IN : Kind.OUT, name.text, statementStart, layoutBodyStart);
	}

	/** The comma-separated entries of one {@code layout(...)} body, as source text (tokens {@code [from, to)}). */
	private static List<String> splitLayoutQualifiers(List<Token> tokens, int from, int to, String source) {
		List<String> parts = new ArrayList<>();
		int partStart = from;
		int depth = 0;
		for (int k = from; k <= to; k++) {
			boolean end = k == to;
			if (!end) {
				if (tokens.get(k).is("(")) {
					depth++;
				} else if (tokens.get(k).is(")")) {
					depth--;
				}
			}
			if (end || (depth == 0 && tokens.get(k).is(","))) {
				if (k > partStart) {
					parts.add(source.substring(tokens.get(partStart).start(), tokens.get(k - 1).end()));
				}
				partStart = k + 1;
			}
		}
		return parts;
	}

	/**
	 * Built-in member types whose std430 (push constant) and std140 (rewritten
	 * uniform block) layouts agree. A {@code matCxR} has C column vectors of R
	 * components: R = 2 (so {@code mat2}, {@code mat3x2}, {@code mat4x2}) is
	 * strided by 8 bytes under std430 but 16 under std140, hence left out.
	 */
	private static final Pattern PUSH_MEMBER_TYPE = Pattern.compile("(?:[iub]?vec[234]|float|int|uint|bool|mat[34]|mat[34]x[34]|mat2x[34])");

	private static Decl pushConstantDecl(
		String stageName, Token nameToken, List<Token> body, int layoutBodyStart, int layoutBodyEnd, List<String> layoutQualifiers, int statementStart
	) throws DxShaderInterfaceException {
		List<String> kept = new ArrayList<>();
		for (String qualifier : layoutQualifiers) {
			if (qualifier.equals("push_constant")) {
				continue;
			}
			if (qualifier.equals("std430") || qualifier.equals("scalar")) {
				throw unsupported(stageName, nameToken.text, "push_constant block with layout '" + qualifier + "'; it is rewritten to std140");
			}
			kept.add(qualifier);
		}

		// Every member must lay out identically under std430 and std140.
		List<Token> member = new ArrayList<>();
		for (Token token : body) {
			if (token.is(";")) {
				checkPushConstantMember(stageName, nameToken.text, member);
				member = new ArrayList<>();
			} else {
				member.add(token);
			}
		}
		checkPushConstantMember(stageName, nameToken.text, member);

		return new Decl(
			Kind.PUSH_CONSTANT_BLOCK, PUSH_CONSTANT_BLOCK_NAME, statementStart, layoutBodyStart,
			new PushBlock(layoutBodyStart, layoutBodyEnd, List.copyOf(kept), nameToken.start(), nameToken.end())
		);
	}

	private static void checkPushConstantMember(String stageName, String blockName, List<Token> member) throws DxShaderInterfaceException {
		if (member.isEmpty()) {
			return;
		}
		int i = 0;
		while (i < member.size()) {
			Token token = member.get(i);
			if (token.is("layout") && i + 1 < member.size() && member.get(i + 1).is("(")) {
				i = matchingParen(member, i + 1) + 1;
			} else if (QUALIFIERS.contains(token.text)) {
				i++;
			} else {
				break;
			}
		}
		if (i >= member.size()) {
			return;
		}
		String type = member.get(i).text;
		if (!PUSH_MEMBER_TYPE.matcher(type).matches()) {
			throw unsupported(stageName, blockName, "push_constant member of type '" + type + "' (std430 and std140 layouts can differ for it)");
		}
		for (Token token : member) {
			if (token.is("[")) {
				throw unsupported(stageName, blockName, "push_constant member arrays (std430 and std140 strides differ)");
			}
		}
	}

	private static DxShaderInterfaceException explicit(String stage, String name, String qualifier) {
		return new DxShaderInterfaceException(
			"The " + stage + " shader declares '" + name + "' with an explicit " + qualifier
				+ "; DirectExp assigns these itself and refuses to guess which one is authoritative."
		);
	}

	private static DxShaderInterfaceException unsupported(String stage, String name, String what) {
		return new DxShaderInterfaceException(
			"The " + stage + " shader's declaration of '" + name + "' uses an unsupported form (" + what + ")."
		);
	}
}
