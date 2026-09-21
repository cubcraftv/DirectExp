package dev.cubcraftv.directexp.bootstrap;

import dev.cubcraftv.directexp.config.BackendKind;
import dev.cubcraftv.directexp.config.FailureMode;

import java.util.ArrayList;
import java.util.List;

public final class BackendNegotiationResolver {
	private BackendNegotiationResolver() {
	}

	public static List<BackendKind> resolveBackendTryOrder(BackendPlan plan) {
		if (plan == null || plan.backendsToTry() == null || plan.backendsToTry().isEmpty()) {
			return List.of(BackendKind.VULKAN, BackendKind.OPENGL);
		}

		ArrayList<BackendKind> orderedBackends = new ArrayList<>();
		for (BackendKind candidate : plan.backendsToTry()) {
			if (!orderedBackends.contains(candidate)) {
				orderedBackends.add(candidate);
			}
			if (plan.failureMode() == FailureMode.STRICT && candidate == BackendKind.DIRECTX) {
				break;
			}
		}

		if (orderedBackends.isEmpty()) {
			return List.of(BackendKind.VULKAN, BackendKind.OPENGL);
		}

		return List.copyOf(orderedBackends);
	}
}
