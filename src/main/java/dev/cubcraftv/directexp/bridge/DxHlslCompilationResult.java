package dev.cubcraftv.directexp.bridge;

/**
 * Result of one {@link DxBridge#emitAndCompileHlsl} call. {@code hlsl} is
 * populated whenever SPIRV-Cross's emission step succeeded -- including
 * when the subsequent {@code D3DCompile} step then failed
 * ({@code status == COMPILE_ERROR}) -- since seeing the exact HLSL text
 * {@code D3DCompile} rejected is far more useful for a human debugging a
 * failure than only its own error string. {@code bytecode} is populated
 * only when {@code status == READY}.
 *
 * <p>Follows the same record+status pair shape as
 * {@link DxSpirvReflectionResult}.
 */
public record DxHlslCompilationResult(
	DxHlslCompilationStatus status,
	byte[] bytecode,
	String hlsl,
	String detail
) {
	public boolean isReady() {
		return this.status == DxHlslCompilationStatus.READY && this.bytecode != null;
	}

	public boolean isEmissionError() {
		return this.status == DxHlslCompilationStatus.EMISSION_ERROR;
	}

	public boolean isCompileError() {
		return this.status == DxHlslCompilationStatus.COMPILE_ERROR;
	}

	public static DxHlslCompilationResult ready(byte[] bytecode, String hlsl) {
		return new DxHlslCompilationResult(DxHlslCompilationStatus.READY, bytecode, hlsl, null);
	}

	public static DxHlslCompilationResult emissionError(String detail) {
		return new DxHlslCompilationResult(DxHlslCompilationStatus.EMISSION_ERROR, null, null, detail);
	}

	public static DxHlslCompilationResult compileError(String hlsl, String detail) {
		return new DxHlslCompilationResult(DxHlslCompilationStatus.COMPILE_ERROR, null, hlsl, detail);
	}

	public static DxHlslCompilationResult bridgeUnavailable(DxHlslCompilationStatus status, String detail) {
		if (status == DxHlslCompilationStatus.READY || status == DxHlslCompilationStatus.EMISSION_ERROR || status == DxHlslCompilationStatus.COMPILE_ERROR) {
			throw new IllegalArgumentException("bridgeUnavailable() cannot be used with READY/EMISSION_ERROR/COMPILE_ERROR; use ready()/emissionError()/compileError() instead.");
		}
		return new DxHlslCompilationResult(status, null, null, detail);
	}
}
