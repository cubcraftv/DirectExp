package dev.cubcraftv.directexp.config;

public record DirectExpConfig(
	BackendKind backend,
	FailureMode failureMode,
	boolean diagnosticsEnabled
) {
	public static DirectExpConfig defaults() {
		return new DirectExpConfig(BackendKind.DIRECTX, FailureMode.STRICT, true);
	}
}
