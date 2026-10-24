package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.GpuFormat;
import dev.cubcraftv.directexp.bridge.DxBridge;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DxTextureTargetsTest {
	private static final int TEXTURE_BINDING = 4;
	private static final int RENDER_ATTACHMENT = 8;
	private static final int CUBEMAP = 16;

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

		assertEquals("createTexture(fmt=1,8x8,layers=1,mips=1,bind=3,cube=false)", bridge.calls.get(0));
		assertTrue(texture.hasNativeRenderTarget());
		assertFalse(texture.hasNativeDepthTarget());
		assertTrue(new DxTextureView(bridge, texture, 0, 1).hasNativeRenderTargetView());
	}

	@Test
	void depthAttachmentGetsDepthStencilBindAndView() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture texture = new DxTexture(bridge, TEXTURE_BINDING | RENDER_ATTACHMENT, "depth", GpuFormat.D32_FLOAT, 8, 8, 1, 1);

		assertEquals("createTexture(fmt=2,8x8,layers=1,mips=1,bind=5,cube=false)", bridge.calls.get(0));
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
	void rgbaTextureClearKeepsRedAndBlueChannelsInRgbaByteOrder() {
		DxTexture texture = new DxTexture(null, 0, "clear", GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);

		texture.fillRegion(0, 0, 0, 1, 1, 0xFFFF0000);
		assertEquals(0xFF, texture.readRegion(0, 0, 0, 1, 1).get(0) & 0xFF);
		assertEquals(0x00, texture.readRegion(0, 0, 0, 1, 1).get(2) & 0xFF);

		texture.fillRegion(0, 0, 0, 1, 1, 0xFF0000FF);
		assertEquals(0x00, texture.readRegion(0, 0, 0, 1, 1).get(0) & 0xFF);
		assertEquals(0xFF, texture.readRegion(0, 0, 0, 1, 1).get(2) & 0xFF);
	}

	@Test
	void cubemapCreationAndFaceUploadsPreserveTheLayerIndex() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture texture = new DxTexture(bridge, TEXTURE_BINDING | CUBEMAP, "cube", GpuFormat.RGBA8_UNORM, 2, 2, 6, 1);
		assertEquals("createTexture(fmt=1,2x2,layers=6,mips=1,bind=1,cube=true)", bridge.calls.get(0));

		ByteBuffer face = ByteBuffer.allocateDirect(4).put(new byte[] {1, 2, 3, 4});
		face.flip();
		texture.writeRegion(face, 4, 1, 0, 5, 0, 0, 1, 1, 0, 0);

		assertEquals("uploadTexture(77,mip=0,layer=5,size=2x2,cap=96)", bridge.calls.get(1));
		ByteBuffer uploadedFace = texture.readRegion(0, 5, 0, 0, 1, 1);
		assertEquals(1, uploadedFace.get(0));
		assertEquals(4, uploadedFace.get(3));
	}

	@Test
	void textureWriteReadsFromTheSourceBufferPosition() {
		ByteBuffer source = ByteBuffer.allocateDirect(8).put(new byte[] {99, 99, 1, 2, 3, 4, 99, 99});
		source.position(2);
		source.limit(6);
		DxTexture texture = new DxTexture(null, 0, "positioned-source", GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);

		texture.writeRegion(source, 4, 1, 0, 0, 0, 0, 1, 1, 0, 0);

		ByteBuffer written = texture.readRegion(0, 0, 0, 1, 1);
		assertEquals(1, written.get(0));
		assertEquals(2, written.get(1));
		assertEquals(3, written.get(2));
		assertEquals(4, written.get(3));
	}

	@Test
	void unsupportedCubemapShapesStayCpuOnly() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture nonsquare = new DxTexture(bridge, TEXTURE_BINDING | CUBEMAP, "cube", GpuFormat.RGBA8_UNORM, 4, 2, 6, 1);
		DxTexture wrongLayerCount = new DxTexture(bridge, TEXTURE_BINDING | CUBEMAP, "cube", GpuFormat.RGBA8_UNORM, 4, 4, 5, 1);

		assertFalse(nonsquare.hasNativeTextureHandle());
		assertFalse(wrongLayerCount.hasNativeTextureHandle());
	}

	@Test
	void unsupportedFormatStaysCpuOnly() {
		RecordingDxBridge bridge = new RecordingDxBridge();
		DxTexture texture = new DxTexture(bridge, TEXTURE_BINDING, "r8", GpuFormat.R8_UNORM, 4, 4, 1, 1);

		assertFalse(texture.hasNativeTextureHandle());
		assertTrue(bridge.calls.isEmpty());
	}
}
