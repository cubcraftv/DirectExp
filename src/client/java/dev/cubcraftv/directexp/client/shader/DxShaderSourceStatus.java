package dev.cubcraftv.directexp.client.shader;

/**
 * Mirrors the {@code DxBridgeLoadStatus} / {@code DxBridgeProbeStatus}
 * result-status pattern already used in {@code dev.cubcraftv.directexp.bridge}:
 * a distinct status per known failure reason instead of a single boolean,
 * so a missing vertex shader and a missing fragment shader are
 * distinguishable in logs the same way a missing DLL and a missing DLL
 * dependency already are.
 */
public enum DxShaderSourceStatus {
	READY,
	// ShaderSource.get(id, ShaderType.VERTEX) returned null for this
	// pipeline's vertex shader -- mirrors the Vulkan path's own
	// "Couldn't find source for {} shader ({})" log case.
	VERTEX_SOURCE_MISSING,
	// Same as above, for the fragment shader.
	FRAGMENT_SOURCE_MISSING
}
