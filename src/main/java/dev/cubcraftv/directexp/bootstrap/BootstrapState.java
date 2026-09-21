package dev.cubcraftv.directexp.bootstrap;

import dev.cubcraftv.directexp.config.DirectExpConfig;

public record BootstrapState(
	DirectExpConfig config,
	BackendPlan backendPlan
) {
}
