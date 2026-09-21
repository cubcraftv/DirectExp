package dev.cubcraftv.directexp.client;

import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bootstrap.BootstrapState;
import dev.cubcraftv.directexp.diag.DiagnosticsReporter;
import dev.cubcraftv.directexp.diag.DiagnosticsSnapshot;
import net.fabricmc.api.ClientModInitializer;

public final class DirectExpClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		BootstrapState state = DirectExpMod.bootstrapState();
		if (state == null) {
			DirectExpMod.LOGGER.warn("DirectExp bootstrap state not initialized; skipping startup diagnostics");
			return;
		}

		DiagnosticsReporter.logStartup(new DiagnosticsSnapshot(
			state.config(),
			state.backendPlan(),
			System.getProperty("os.name", "unknown"),
			System.getProperty("os.arch", "unknown")
		));
	}
}
