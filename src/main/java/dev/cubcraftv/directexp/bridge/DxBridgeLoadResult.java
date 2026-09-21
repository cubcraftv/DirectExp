package dev.cubcraftv.directexp.bridge;

public record DxBridgeLoadResult(
	DxBridgeLoadStatus status,
	String detail,
	String source
) {
	public boolean isLoaded() {
		return this.status == DxBridgeLoadStatus.LOADED;
	}
}
