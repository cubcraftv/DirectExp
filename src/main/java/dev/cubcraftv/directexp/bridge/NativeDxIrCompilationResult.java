package dev.cubcraftv.directexp.bridge;

/**
 * Mirrors the object constructed by {@code directexp_shader_toolchain.cpp}'s
 * {@code BuildIrCompilationResult} (called from
 * {@code directexp_bridge_shader.cpp}'s {@code compileGlslToSpirv0}). Keep
 * the constructor signature ({@code (I[BLjava/lang/String;II)V}) in sync
 * with the native side if this record's fields change.
 *
 * <p>{@code OUTCOME_COMPILE_ERROR} is intentionally its own outcome code
 * rather than reusing {@code NativeDxBridgeProbeResult}/
 * {@code NativeDxBridgeSurfaceBootstrapResult}'s
 * {@code OUTCOME_STUB_UNIMPLEMENTED}/{@code OUTCOME_ERROR} pair: a
 * {@code shaderc} compile error is a normal, expected outcome of this call
 * (per Milestone 4.3's checklist, "the first stage where 'the shader
 * failed to compile' becomes a real, expected runtime case rather than a
 * can't-happen"), not a stub or an unexpected native-side failure.
 */
public record NativeDxIrCompilationResult(
	int outcomeCode,
	byte[] spirv,
	String detail,
	int numErrors,
	int numWarnings
) {
	public static final int OUTCOME_READY = 0;
	public static final int OUTCOME_COMPILE_ERROR = 1;
	public static final int OUTCOME_NATIVE_ERROR = 2;
}
