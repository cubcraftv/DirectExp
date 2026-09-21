package dev.cubcraftv.directexp.bridge;

import java.util.List;

public record DxHostSurfaceBootstrap(
	long nativeSurfaceHandle,
	int drawableWidth,
	int drawableHeight,
	double dpiScale,
	String detail,
	List<String> missingCapabilities,
	boolean libraryLoaded,
	boolean nativeEntryPointReached
) {
	public DxHostSurfaceBootstrap {
		missingCapabilities = missingCapabilities == null ? List.of() : List.copyOf(missingCapabilities);
	}

	public boolean isReady() {
		return this.nativeSurfaceHandle > 0 && this.missingCapabilities.isEmpty();
	}
}
