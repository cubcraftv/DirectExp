package dev.cubcraftv.directexp.bridge;

/**
 * Result of one {@link DxBridge#compileGlslToSpirv} call: either the
 * compiled SPIR-V bytes ({@code status == READY}), a structured compile
 * error from {@code shaderc} ({@code status == COMPILE_ERROR}, see
 * {@link #detail}/{@link #numErrors}/{@link #numWarnings}), or a
 * bridge-level failure that never reached {@code shaderc} at all (library
 * not loaded, native entrypoint missing, etc).
 *
 * <p>Follows the same record+status pair shape as {@code DxBridgeProbe}.
 */
public record DxIrCompilationResult(
	DxIrCompilationStatus status,
	byte[] spirv,
	String detail,
	int numErrors,
	int numWarnings
) {
	public boolean isReady() {
		return this.status == DxIrCompilationStatus.READY && this.spirv != null;
	}

	/**
	 * True only for {@code COMPILE_ERROR}: shaderc was actually reached
	 * and rejected this specific GLSL source, as opposed to the bridge
	 * itself being unavailable ({@link #status} one of the other
	 * non-{@code READY} values).
	 */
	public boolean isCompileError() {
		return this.status == DxIrCompilationStatus.COMPILE_ERROR;
	}

	public static DxIrCompilationResult ready(byte[] spirv) {
		return new DxIrCompilationResult(DxIrCompilationStatus.READY, spirv, null, 0, 0);
	}

	public static DxIrCompilationResult compileError(String detail, int numErrors, int numWarnings) {
		return new DxIrCompilationResult(DxIrCompilationStatus.COMPILE_ERROR, null, detail, numErrors, numWarnings);
	}

	public static DxIrCompilationResult bridgeUnavailable(DxIrCompilationStatus status, String detail) {
		if (status == DxIrCompilationStatus.READY || status == DxIrCompilationStatus.COMPILE_ERROR) {
			throw new IllegalArgumentException("bridgeUnavailable() cannot be used with READY or COMPILE_ERROR; use ready()/compileError() instead.");
		}
		return new DxIrCompilationResult(status, null, detail, 0, 0);
	}
}
