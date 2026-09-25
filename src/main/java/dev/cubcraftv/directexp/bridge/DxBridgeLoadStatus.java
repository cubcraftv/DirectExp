package dev.cubcraftv.directexp.bridge;

public enum DxBridgeLoadStatus {
	LOADED,
	UNSUPPORTED_OS,
	NOT_FOUND,
	LOAD_FAILED,
	// Distinct from NOT_FOUND/LOAD_FAILED: a known runtime dependency of
	// directexp_native.dll itself (currently only d3dcompiler_47.dll,
	// linked starting with Milestone 4.1 -- see native/CMakeLists.txt and
	// docs/research/2026-09-25-directexp-milestone4-shader-toolchain-research.md's
	// "Runtime caveat" section) was not found where expected *before* the
	// real library load was even attempted. Without this, a missing
	// d3dcompiler_47.dll would surface as directexp_native.dll itself
	// failing to load, with a generic Windows loader message that doesn't
	// say which dependency was actually missing.
	DEPENDENCY_MISSING
}
