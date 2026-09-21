package dev.cubcraftv.directexp.bootstrap;

import dev.cubcraftv.directexp.config.BackendKind;
import dev.cubcraftv.directexp.config.FailureMode;
import dev.cubcraftv.directexp.config.DirectExpConfig;

import java.util.List;

public final class BackendPlanner {
	private BackendPlanner() {
	}

	public static BackendPlan plan(DirectExpConfig config, boolean isWindows) {
		List<BackendKind> order = switch (config.backend()) {
			case DIRECTX -> isWindows
				? List.of(BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.OPENGL)
				: List.of(BackendKind.VULKAN, BackendKind.OPENGL);
			case VULKAN -> List.of(BackendKind.VULKAN, BackendKind.OPENGL);
			case OPENGL -> List.of(BackendKind.OPENGL, BackendKind.VULKAN);
		};

		FailureMode failureMode = config.backend() == BackendKind.DIRECTX && isWindows
			? config.failureMode()
			: FailureMode.FALLBACK;

		return new BackendPlan(order, failureMode);
	}
}
