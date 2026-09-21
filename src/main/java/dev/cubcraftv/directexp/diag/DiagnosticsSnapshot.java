package dev.cubcraftv.directexp.diag;

import dev.cubcraftv.directexp.bootstrap.BackendPlan;
import dev.cubcraftv.directexp.config.DirectExpConfig;

public record DiagnosticsSnapshot(
	DirectExpConfig config,
	BackendPlan backendPlan,
	String osName,
	String osArch
) {
}
