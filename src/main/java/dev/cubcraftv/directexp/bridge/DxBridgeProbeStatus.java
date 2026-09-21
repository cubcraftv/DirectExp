package dev.cubcraftv.directexp.bridge;

public enum DxBridgeProbeStatus {
	READY,
	STUB_UNIMPLEMENTED,
	UNSUPPORTED_OS,
	LIBRARY_MISSING,
	LIBRARY_LOAD_FAILED,
	// Distinct from a generic NATIVE_ERROR so diagnostics can say plainly
	// "your GPU/driver does not reach the DirectX 11 feature level 10.1
	// floor this project requires" instead of a generic failure.
	FEATURE_LEVEL_UNSUPPORTED,
	NATIVE_ERROR
}
