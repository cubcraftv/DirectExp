package dev.cubcraftv.directexp.bootstrap;

import dev.cubcraftv.directexp.config.BackendKind;
import dev.cubcraftv.directexp.config.FailureMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackendNegotiationResolverTest {

	@Test
	void strictModeWithDirectXOnlyAttemptsDirectX() {
		BackendPlan plan = new BackendPlan(
			List.of(BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.OPENGL),
			FailureMode.STRICT
		);

		assertEquals(List.of(BackendKind.DIRECTX), BackendNegotiationResolver.resolveBackendTryOrder(plan));
	}

	@Test
	void fallbackModeKeepsDirectXFirstThenRemainingOrder() {
		BackendPlan plan = new BackendPlan(
			List.of(BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.OPENGL),
			FailureMode.FALLBACK
		);

		List<BackendKind> resolved = BackendNegotiationResolver.resolveBackendTryOrder(plan);

		assertEquals(List.of(BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.OPENGL), resolved);
	}

	@Test
	void nullPlanFallsBackToDefaultOrder() {
		assertEquals(
			List.of(BackendKind.VULKAN, BackendKind.OPENGL),
			BackendNegotiationResolver.resolveBackendTryOrder(null)
		);
	}

	@Test
	void duplicateCandidatesCollapseToSingleEntries() {
		BackendPlan plan = new BackendPlan(
			List.of(BackendKind.DIRECTX, BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.VULKAN, BackendKind.OPENGL),
			FailureMode.FALLBACK
		);

		assertEquals(
			List.of(BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.OPENGL),
			BackendNegotiationResolver.resolveBackendTryOrder(plan)
		);
	}
}
