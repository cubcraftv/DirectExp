package dev.cubcraftv.directexp.client.backend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DxDebugProbesTest {
	private static final String ENABLED_PROPERTY = "directexp.debug.probes";
	private String previousProperty;

	@BeforeEach
	void clearProperty() {
		this.previousProperty = System.getProperty(ENABLED_PROPERTY);
		System.clearProperty(ENABLED_PROPERTY);
	}

	@AfterEach
	void restoreProperty() {
		if (this.previousProperty == null) {
			System.clearProperty(ENABLED_PROPERTY);
		} else {
			System.setProperty(ENABLED_PROPERTY, this.previousProperty);
		}
	}

	@Test
	void probesAreDisabledByDefault() {
		assertFalse(DxDebugProbes.enabled());
		assertDoesNotThrow(() -> DxDebugProbes.logButtonSpriteUpload(
			"minecraft:widget/button", "RGBA8_UNORM", null, null, 4, 4, 1, 4, 0, 0, 0, 1, 1, 0, 0
		));
		assertNull(DxDebugProbes.captureAtlasTexelsBeforeDraw(null, 0, null, null, null, null));
		assertDoesNotThrow(() -> DxDebugProbes.logGuiAtlasCompositionDraw(
			null, null, 0, null, null, null, null, null, 0, 0, null
		));
		assertDoesNotThrow(() -> DxDebugProbes.logGuiTextureBinding(null, 0L, null));
	}

	@Test
	void probesCanBeEnabledWithSystemProperty() {
		System.setProperty(ENABLED_PROPERTY, "true");

		assertTrue(DxDebugProbes.enabled());
	}
}
