package dev.cubcraftv.directexp.diag;

import dev.cubcraftv.directexp.DirectExpMod;

public final class DiagnosticsReporter {
	private DiagnosticsReporter() {
	}

	public static void logStartup(DiagnosticsSnapshot snapshot) {
		if (snapshot == null || snapshot.config() == null || snapshot.backendPlan() == null) {
			return;
		}
		if (!snapshot.config().diagnosticsEnabled()) {
			return;
		}

		DirectExpMod.LOGGER.info(
			"DirectExp startup: backend={}, failureMode={}, diagnosticsEnabled={}, plan={}, os={} {}",
			snapshot.config().backend(),
			snapshot.config().failureMode(),
			snapshot.config().diagnosticsEnabled(),
			snapshot.backendPlan().backendsToTry(),
			snapshot.osName(),
			snapshot.osArch()
		);
	}
}
