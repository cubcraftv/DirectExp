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
	// Mirrors DxBridgeLoadStatus.DEPENDENCY_MISSING -- a known runtime
	// dependency of directexp_native.dll (currently d3dcompiler_47.dll,
	// see that enum's javadoc) was not found before the library load was
	// even attempted.
	RUNTIME_DEPENDENCY_MISSING,
	NATIVE_ERROR
}
