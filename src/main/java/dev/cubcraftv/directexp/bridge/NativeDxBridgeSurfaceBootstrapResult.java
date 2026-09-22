package dev.cubcraftv.directexp.bridge;

/**
 * Mirrors the object constructed by {@code directexp_bridge_surface.cpp}'s
 * {@code BuildSurfaceBootstrapResult}. Keep the constructor signature
 * ({@code (JIIDILjava/lang/String;[Ljava/lang/String;)V}) in sync with the
 * native side if this record's fields change.
 */
public record NativeDxBridgeSurfaceBootstrapResult(
	long nativeSurfaceHandle,
	int drawableWidth,
	int drawableHeight,
	double dpiScale,
	int outcomeCode,
	String detail,
	String[] missingCapabilities
) {
	public static final int OUTCOME_READY = 0;
	public static final int OUTCOME_STUB_UNIMPLEMENTED = 1;
	public static final int OUTCOME_ERROR = 2;
}
