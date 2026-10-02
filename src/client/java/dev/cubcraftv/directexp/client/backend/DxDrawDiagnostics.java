package dev.cubcraftv.directexp.client.backend;

/**
 * Where the draw path reports problems that must not crash rendering (a
 * missing native handle, a failed bind). Implementations log once per key:
 * a broken resource is hit on every draw of every frame. Kept as an
 * interface so the draw helpers stay free of logging/Minecraft dependencies
 * and testable with a recording implementation.
 */
interface DxDrawDiagnostics {
	void reportOnce(String key, String message);
}
