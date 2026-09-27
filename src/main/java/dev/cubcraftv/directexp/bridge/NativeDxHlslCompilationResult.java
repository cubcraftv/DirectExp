package dev.cubcraftv.directexp.bridge;

/**
 * Mirrors the object constructed by {@code directexp_shader_hlsl.cpp}'s
 * {@code BuildHlslCompilationResult} (called from
 * {@code directexp_bridge_shader.cpp}'s {@code emitAndCompileHlsl0}).
 * Keep the constructor signature
 * ({@code (I[BLjava/lang/String;Ljava/lang/String;)V}) in sync with the
 * native side if this record's fields change.
 *
 * <p>{@code OUTCOME_EMISSION_ERROR}/{@code OUTCOME_COMPILE_ERROR} are
 * intentionally two separate outcome codes, not one generic failure code:
 * SPIRV-Cross's SPIR-V -&gt; HLSL translation and {@code D3DCompile}'s
 * HLSL -&gt; bytecode compilation are independent steps that can each
 * fail on their own (see {@link DxHlslCompilationStatus}'s javadoc).
 */
public record NativeDxHlslCompilationResult(
	int outcomeCode,
	byte[] bytecode,
	String hlsl,
	String detail
) {
	public static final int OUTCOME_READY = 0;
	public static final int OUTCOME_EMISSION_ERROR = 1;
	public static final int OUTCOME_COMPILE_ERROR = 2;
	public static final int OUTCOME_NATIVE_ERROR = 3;
}
