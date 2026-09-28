package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import dev.cubcraftv.directexp.bridge.DxBridge;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxSamplerAndViewTest {
	@Test
	void addressModesMap() {
		assertEquals(DxBridge.SAMPLER_ADDRESS_WRAP, DxSampler.nativeAddressMode(AddressMode.REPEAT));
		assertEquals(DxBridge.SAMPLER_ADDRESS_CLAMP, DxSampler.nativeAddressMode(AddressMode.CLAMP_TO_EDGE));
	}

	@Test
	void lodRulesMatchTheVulkanBackend() {
		assertTrue(DxSampler.mipLinear(OptionalDouble.empty()));
		assertTrue(DxSampler.mipLinear(OptionalDouble.of(4.0)));
		assertFalse(DxSampler.mipLinear(OptionalDouble.of(0.25)));
		assertFalse(DxSampler.mipLinear(OptionalDouble.of(0.0)));
		assertEquals(1000.0f, DxSampler.nativeMaxLod(OptionalDouble.empty()));
		assertEquals(0.25f, DxSampler.nativeMaxLod(OptionalDouble.of(0.0)));
		assertEquals(3.0f, DxSampler.nativeMaxLod(OptionalDouble.of(3.0)));
	}

	@Test
	void anisotropyOnlyHonouredWithLinearMinAndMag() {
		assertEquals(8, DxSampler.nativeAnisotropy(FilterMode.LINEAR, FilterMode.LINEAR, 8));
		assertEquals(1, DxSampler.nativeAnisotropy(FilterMode.NEAREST, FilterMode.LINEAR, 8));
		assertEquals(1, DxSampler.nativeAnisotropy(FilterMode.LINEAR, FilterMode.NEAREST, 8));
		assertEquals(1, DxSampler.nativeAnisotropy(FilterMode.LINEAR, FilterMode.LINEAR, 0));
	}

	@Test
	void samplerCreatesAndReleasesNativeHandleOnce() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxSampler sampler = new DxSampler(bridge, AddressMode.REPEAT, AddressMode.CLAMP_TO_EDGE, FilterMode.NEAREST, FilterMode.LINEAR, 4, OptionalDouble.of(0.0));

		assertTrue(sampler.hasNativeSamplerHandle());
		assertEquals("createSampler(u=0,v=1,min=false,mag=true)", bridge.calls.get(0));
		assertEquals(1, bridge.lastSamplerAnisotropy);
		assertFalse(bridge.lastSamplerMipLinear);
		assertEquals(0.25f, bridge.lastSamplerMaxLod);

		sampler.close();
		sampler.close();
		assertEquals(List.of(bridge.calls.get(0), "releaseSampler(100)"), bridge.calls);
	}

	@Test
	void samplerFallsBackWhenNativeCreationFails() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		bridge.failCreates = true;
		DxSampler sampler = new DxSampler(bridge, AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, 1, OptionalDouble.empty());

		assertFalse(sampler.hasNativeSamplerHandle());
		assertEquals(FilterMode.LINEAR, sampler.getMinFilter());
		sampler.close();
	}

	@Test
	void textureViewOfNativeTextureGetsSrvAndReleasesIt() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture texture = new DxTexture(bridge, 0, "t", GpuFormat.RGBA8_UNORM, 4, 4, 1, 3);
		DxTextureView view = new DxTextureView(bridge, texture, 1, 2);

		assertTrue(view.hasNativeViewHandle());
		assertEquals(List.of("createTextureView(tex=77,base=1,mips=2)"), bridge.calls);

		view.close();
		view.close();
		assertEquals("releaseTextureView(100)", bridge.calls.get(1));
		assertEquals(2, bridge.calls.size());
	}

	@Test
	void textureViewOfCpuOnlyTextureHasNoNativeView() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		bridge.textureHandle = 0L;
		DxTexture texture = new DxTexture(bridge, 0, "t", GpuFormat.RGBA8_UNORM, 4, 4, 1, 1);
		DxTextureView view = new DxTextureView(bridge, texture, 0, 1);

		assertFalse(view.hasNativeViewHandle());
		assertTrue(bridge.calls.isEmpty());
	}
}
