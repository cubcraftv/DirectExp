package dev.cubcraftv.directexp.bridge;

/**
 * Mirrors the object constructed by
 * {@code directexp_shader_reflection.cpp}'s {@code BuildReflectionResult}
 * (called from {@code directexp_bridge_shader.cpp}'s {@code reflectSpirv0}).
 * Keep the constructor signature
 * ({@code (I[Ljava/lang/String;[I[ILjava/lang/String;)V}) in sync with the
 * native side if this record's fields change.
 *
 * <p>The three arrays are parallel and, when {@code outcomeCode ==
 * OUTCOME_READY}, always the same length: {@code resourceNames[i]}/
 * {@code resourceKinds[i]}/{@code resourceSizeBytes[i]} together describe
 * one reflected resource. {@code resourceKinds[i]} is a
 * {@link DxResourceKind} ordinal encoded as documented on that enum, not
 * a Java enum instance -- see {@link DxResourceKind#fromNativeCode(int)}.
 *
 * <p>{@code OUTCOME_REFLECTION_ERROR} is intentionally its own outcome
 * code, mirroring {@code NativeDxIrCompilationResult}'s
 * {@code OUTCOME_COMPILE_ERROR}: a SPIRV-Cross reflection failure is a
 * distinct, structured outcome of this call, not the generic
 * "unimplemented stub vs. native error" split
 * {@code NativeDxBridgeProbeResult}/
 * {@code NativeDxBridgeSurfaceBootstrapResult} share.
 */
public record NativeDxSpirvReflectionResult(
	int outcomeCode,
	String[] resourceNames,
	int[] resourceKinds,
	int[] resourceSizeBytes,
	String detail
) {
	public static final int OUTCOME_READY = 0;
	public static final int OUTCOME_REFLECTION_ERROR = 1;
	public static final int OUTCOME_NATIVE_ERROR = 2;
}
