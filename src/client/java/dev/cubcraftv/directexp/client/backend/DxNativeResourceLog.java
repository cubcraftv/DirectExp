package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.DirectExpMod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Log once per resource kind" helper for the native buffer/sampler/view
 * paths, so per-resource creation (which happens constantly) reports which
 * path the bridge is taking overall without spamming the log -- the same
 * reasoning as DxTexture's two AtomicBoolean flags, factored out because
 * three more resource kinds now need the identical behaviour.
 */
final class DxNativeResourceLog {
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	private DxNativeResourceLog() {
	}

	static void nativeActive(String kind, String detail) {
		if (LOGGED.add(kind + ":active")) {
			DirectExpMod.LOGGER.info("DirectExp: native GPU {} path active (first native {} created: {}).", kind, kind, detail);
		}
	}

	static void fallback(String kind, String reason) {
		if (LOGGED.add(kind + ":fallback")) {
			DirectExpMod.LOGGER.warn("DirectExp: native GPU {} creation unavailable, using CPU-side fallback ({}).", kind, reason);
		}
	}

	/** A draw-path problem (skipped draw, failed bind). Once per key: these repeat every frame. */
	static void drawProblem(String key, String message) {
		if (LOGGED.add("draw:" + key)) {
			DirectExpMod.LOGGER.warn("DirectExp: draw path: {}", message);
		}
	}

	static void syncFailure(String kind, String reason) {
		if (LOGGED.add(kind + ":sync")) {
			DirectExpMod.LOGGER.warn("DirectExp: native GPU {} upload failed; GPU copy may be stale ({}).", kind, reason);
		}
	}
}
