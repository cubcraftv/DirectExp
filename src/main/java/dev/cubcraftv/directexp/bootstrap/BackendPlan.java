package dev.cubcraftv.directexp.bootstrap;

import dev.cubcraftv.directexp.config.BackendKind;
import dev.cubcraftv.directexp.config.FailureMode;

import java.util.List;

public record BackendPlan(
	List<BackendKind> backendsToTry,
	FailureMode failureMode
) {
}
