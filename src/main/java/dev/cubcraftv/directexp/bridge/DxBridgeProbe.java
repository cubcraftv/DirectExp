package dev.cubcraftv.directexp.bridge;

import java.util.List;

public record DxBridgeProbe(
	DxBridgeProbeStatus status,
	String detail,
	List<String> missingCapabilities,
	boolean libraryLoaded,
	boolean nativeEntryPointReached,
	// D3D_FEATURE_LEVEL encoded as its native integer value (e.g. 0xa100 for
	// 10.1); 0 when unknown/unavailable. See NativeDxBridgeProbeResult.
	int featureLevel,
	String adapterDescription,
	int vendorId,
	long dedicatedVideoMemoryBytes
) {
	public DxBridgeProbe {
		missingCapabilities = missingCapabilities == null ? List.of() : List.copyOf(missingCapabilities);
	}

	public boolean isReady() {
		return this.status == DxBridgeProbeStatus.READY;
	}

	/** Feature level 10.1, encoded the same way the native probe encodes it. */
	public static final int FEATURE_LEVEL_10_1 = 0xa100;
	public static final int FEATURE_LEVEL_11_0 = 0xb000;
	public static final int FEATURE_LEVEL_11_1 = 0xb100;

	public boolean meetsFeatureLevelFloor() {
		return this.featureLevel >= FEATURE_LEVEL_10_1;
	}
}
