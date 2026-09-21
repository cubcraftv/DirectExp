package dev.cubcraftv.directexp.bootstrap;

import dev.cubcraftv.directexp.config.BackendKind;
import dev.cubcraftv.directexp.config.FailureMode;
import dev.cubcraftv.directexp.config.DirectExpConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BackendPlannerTest {

	@Test
	void directXFallbackIncludesDirectXThenVulkanThenOpenGl() {
		DirectExpConfig config = new DirectExpConfig(BackendKind.DIRECTX, FailureMode.FALLBACK, true);

		BackendPlan plan = BackendPlanner.plan(config, true);

		assertNotNull(plan, "Planner.plan should produce a backend plan for the provided configuration");
		assertEquals(List.of(BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.OPENGL), plan.backendsToTry(),
				"Backends should be tried in the planned order");
		assertEquals(FailureMode.FALLBACK, plan.failureMode(), "The failure mode should follow the configuration");
	}

	@Test
	void directXStrictPreservesStrictModeOnWindows() {
		DirectExpConfig config = new DirectExpConfig(BackendKind.DIRECTX, FailureMode.STRICT, true);

		BackendPlan plan = BackendPlanner.plan(config, true);

		assertEquals(List.of(BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.OPENGL), plan.backendsToTry());
		assertEquals(FailureMode.STRICT, plan.failureMode());
	}

	@Test
	void directXOnNonWindowsFallsBackToOriginalBackends() {
		DirectExpConfig config = DirectExpConfig.defaults();

		BackendPlan plan = BackendPlanner.plan(config, false);

		assertEquals(List.of(BackendKind.VULKAN, BackendKind.OPENGL), plan.backendsToTry());
		assertEquals(FailureMode.FALLBACK, plan.failureMode());
	}

	@Test
	void defaultsUseStrictDirectXOnWindows() {
		BackendPlan plan = BackendPlanner.plan(DirectExpConfig.defaults(), true);

		assertEquals(List.of(BackendKind.DIRECTX, BackendKind.VULKAN, BackendKind.OPENGL), plan.backendsToTry());
		assertEquals(FailureMode.STRICT, plan.failureMode());
	}
}
