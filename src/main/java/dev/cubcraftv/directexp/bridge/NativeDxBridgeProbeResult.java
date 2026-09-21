package dev.cubcraftv.directexp.bridge;

/**
 * Mirrors the object constructed by {@code directexp_bridge_probe.cpp}'s
 * {@code BuildProbeResult}. Keep the constructor signature
 * ({@code (ILjava/lang/String;[Ljava/lang/String;ILjava/lang/String;IJ)V})
 * in sync with the native side if this record's fields change.
 */
public record NativeDxBridgeProbeResult(
	int outcomeCode,
	String detail,
	String[] missingCapabilities,
	int featureLevel,
	String adapterDescription,
	int vendorId,
	long dedicatedVideoMemoryBytes
) {
	public static final int OUTCOME_READY = 0;
	public static final int OUTCOME_STUB_UNIMPLEMENTED = 1;
	public static final int OUTCOME_ERROR = 2;
}
