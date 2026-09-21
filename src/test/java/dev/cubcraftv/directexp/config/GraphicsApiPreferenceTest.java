package dev.cubcraftv.directexp.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GraphicsApiPreferenceTest {

	@Test
	void fromBackendKindMapsAllSupportedBackends() {
		assertEquals(GraphicsApiPreference.DIRECTX, GraphicsApiPreference.fromBackendKind(BackendKind.DIRECTX));
		assertEquals(GraphicsApiPreference.VULKAN, GraphicsApiPreference.fromBackendKind(BackendKind.VULKAN));
		assertEquals(GraphicsApiPreference.OPENGL, GraphicsApiPreference.fromBackendKind(BackendKind.OPENGL));
	}

	@Test
	void toBackendKindPreservesSelection() {
		assertEquals(BackendKind.DIRECTX, GraphicsApiPreference.DIRECTX.toBackendKind());
		assertEquals(BackendKind.VULKAN, GraphicsApiPreference.VULKAN.toBackendKind());
		assertEquals(BackendKind.OPENGL, GraphicsApiPreference.OPENGL.toBackendKind());
	}

	@Test
	void fromIdFallsBackToDirectXWhenInvalid() {
		assertEquals(GraphicsApiPreference.DIRECTX, GraphicsApiPreference.fromId("invalid"));
		assertEquals(GraphicsApiPreference.DIRECTX, GraphicsApiPreference.fromId(null));
	}
}
