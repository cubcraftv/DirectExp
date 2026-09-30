package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GpuFormat;
import dev.cubcraftv.directexp.bridge.DxBridge;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxTextureTargetsTest {
	private static final int TEXTURE_BINDING = 4;
	private static final int RENDER_ATTACHMENT = 8;

	@Test
	void colorTexturesAreAlwaysSampleableAndRenderableOnlyWhenAttached() {
		assertEquals(DxBridge.TEXTURE_BIND_SHADER_RESOURCE, DxTextureBinding.bindMask(0, false));
		assertEquals(DxBridge.TEXTURE_BIND_SHADER_RESOURCE, DxTextureBinding.bindMask(TEXTURE_BINDING, false));
		assertEquals(
			DxBridge.TEXTURE_BIND_SHADER_RESOURCE | DxBridge.TEXTURE_BIND_RENDER_TARGET,
			DxTextureBinding.bindMask(TEXTURE_BINDING | RENDER_ATTACHMENT, false)
		);
	}

	@Test
	void depthTexturesGetExactlyWhatTheirUsageAsks() {
		assertEquals(0, DxTextureBinding.bindMask(0, true));
		assertEquals(DxBridge.TEXTURE_BIND_DEPTH_STENCIL, DxTextureBinding.bindMask(RENDER_ATTACHMENT, true));
		assertEquals(
			DxBridge.TEXTURE_BIND_DEPTH_STENCIL | DxBridge.TEXTURE_BIND_SHADER_RESOURCE,
			DxTextureBinding.bindMask(RENDER_ATTACHMENT | TEXTURE_BINDING, true)
		);
	}

	@Test
	void nativeFormatCodesCoverRgba8AndDepthOnly() {
		assertEquals(DxBridge.TEXTURE_FORMAT_RGBA8_UNORM, DxTexture.nativeFormatCode(GpuFormat.RGBA8_UNORM));
		assertEquals(DxBridge.TEXTURE_FORMAT_D32_FLOAT, DxTexture.nativeFormatCode(GpuFormat.D32_FLOAT));
		assertEquals(0, DxTexture.nativeFormatCode(GpuFormat.R8_UNORM));
	}

	@Test
	void colorRenderAttachmentGetsRenderTargetBindAndView() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture texture = new DxTexture(bridge, TEXTURE_BINDING | RENDER_ATTACHMENT, "rt", GpuFormat.RGBA8_UNORM, 8, 8, 1, 1);

		assertEquals("createTexture(fmt=1,8x8,mips=1,bind=3)", bridge.calls.get(0));
		assertTrue(texture.hasNativeRenderTarget());
		assertFalse(texture.hasNativeDepthTarget());
		assertTrue(new DxTextureView(bridge, texture, 0, 1).hasNativeRenderTargetView());
	}

	@Test
	void depthAttachmentGetsDepthStencilBindAndView() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture texture = new DxTexture(bridge, TEXTURE_BINDING | RENDER_ATTACHMENT, "depth", GpuFormat.D32_FLOAT, 8, 8, 1, 1);

		assertEquals("createTexture(fmt=2,8x8,mips=1,bind=5)", bridge.calls.get(0));
		assertTrue(texture.hasNativeDepthTarget());
		assertFalse(texture.hasNativeRenderTarget());
		DxTextureView view = new DxTextureView(bridge, texture, 0, 1);
		assertTrue(view.hasNativeDepthStencilView());
		assertFalse(view.hasNativeRenderTargetView());
	}

	@Test
	void depthTextureIsNeverUploadedFromTheCpuMirror() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture texture = new DxTexture(bridge, RENDER_ATTACHMENT, "depth", GpuFormat.D32_FLOAT, 4, 4, 1, 1);
		bridge.calls.clear();

		texture.fillRegion(0, 0, 0, 4, 4, 0);

		assertTrue(bridge.calls.isEmpty());
	}

	@Test
	void unsupportedFormatStaysCpuOnly() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture texture = new DxTexture(bridge, TEXTURE_BINDING, "r8", GpuFormat.R8_UNORM, 4, 4, 1, 1);

		assertFalse(texture.hasNativeTextureHandle());
		assertTrue(bridge.calls.isEmpty());
	}
}
