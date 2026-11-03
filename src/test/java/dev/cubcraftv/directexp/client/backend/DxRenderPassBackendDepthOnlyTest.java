package dev.cubcraftv.directexp.client.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Optional;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

/** Render passes with no color attachment (vanilla's "Integrate always on top depth" pass), no GPU involved. */
final class DxRenderPassBackendDepthOnlyTest {
	private static final int TEXTURE_BINDING = 4;
	private static final int RENDER_ATTACHMENT = 8;

	private final RecordingDxBridge bridge = new RecordingDxBridge();
	private final List<String> reports = new ArrayList<>();
	private final DxDrawContext context = new DxDrawContext(this.bridge, new DxDrawScratch(this.bridge, (key, message) -> this.reports.add(key)), (key, message) -> this.reports.add(key));

	private DxTextureView depthView(int usage, int width, int height) {
		DxTexture texture = new DxTexture(this.bridge, usage, "depth", GpuFormat.D32_FLOAT, width, height, 1, 1);
		return new DxTextureView(this.bridge, texture, 0, 1);
	}

	private DxTextureView colorView(int width, int height) {
		DxTexture texture = new DxTexture(this.bridge, TEXTURE_BINDING | RENDER_ATTACHMENT, "color", GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		return new DxTextureView(this.bridge, texture, 0, 1);
	}

	private static RenderPassDescriptor.Builder pass() {
		return RenderPassDescriptor.builder(() -> "test");
	}


	@Test
	void depthOnlyPassBindsAnEmptyColorArrayAndTheDepthView() {
		DxTextureView depth = depthView(TEXTURE_BINDING | RENDER_ATTACHMENT, 8, 8);
		this.bridge.calls.clear();

		new DxRenderPassBackend(this.context, pass().withDepthAttachment(depth).build());

		assertEquals(
			List.of("setRenderTargets(colors=[],depth=" + depth.nativeViewHandle() + ",size=8x8)"),
			this.bridge.calls
		);
		assertTrue(this.reports.isEmpty());
	}

	@Test
	void depthOnlyPassTakesItsSizeFromTheDepthView() {
		DxTextureView depth = depthView(TEXTURE_BINDING | RENDER_ATTACHMENT, 16, 8);
		this.bridge.calls.clear();

		new DxRenderPassBackend(this.context, pass().withDepthAttachment(depth).build());

		assertEquals("setRenderTargets(colors=[],depth=" + depth.nativeViewHandle() + ",size=16x8)", this.bridge.calls.get(0));
	}

	@Test
	void depthOnlyPassAppliesItsFullAreaDepthClear() {
		DxTextureView depth = depthView(TEXTURE_BINDING | RENDER_ATTACHMENT, 8, 8);
		this.bridge.calls.clear();

		new DxRenderPassBackend(this.context, pass().withDepthAttachment(depth, OptionalDouble.of(1.0)).build());

		assertEquals(
			List.of(
				"setRenderTargets(colors=[],depth=" + depth.nativeViewHandle() + ",size=8x8)",
				"clearDepthStencil(" + depth.nativeViewHandle() + ",1.0)"
			),
			this.bridge.calls
		);
	}

	@Test
	void depthOnlyPassWithoutANativeDepthViewIsSkippedNotCrashed() {
		// Sampled-only depth texture: no render-attachment usage, so no depth-stencil view.
		DxTextureView depth = depthView(TEXTURE_BINDING, 8, 8);
		this.bridge.calls.clear();

		new DxRenderPassBackend(this.context, pass().withDepthAttachment(depth).build());

		assertFalse(this.bridge.calls.stream().anyMatch(call -> call.startsWith("setRenderTargets")));
		assertTrue(this.reports.contains("pass-no-native-depth-only"));
	}

	@Test
	void passWithNeitherColorNorDepthIsRejected() {
		RenderPassDescriptor descriptor = pass().withRenderArea(new RenderPass.RenderArea(0, 0, 8, 8)).build();

		assertThrows(IllegalArgumentException.class, () -> new DxRenderPassBackend(this.context, descriptor));
	}

	@Test
	void passWithMoreThanEightColorAttachmentsIsRejected() {
		RenderPassDescriptor.Builder builder = pass();
		for (int index = 0; index < 9; index++) {
			builder.withColorAttachment(colorView(8, 8));
		}
		RenderPassDescriptor descriptor = builder.build();

		assertThrows(IllegalArgumentException.class, () -> new DxRenderPassBackend(this.context, descriptor));
	}

	@Test
	void colorPassesStillBindTheirColorViewsAndSizeFromColor() {
		DxTextureView color = colorView(8, 4);
		DxTextureView depth = depthView(TEXTURE_BINDING | RENDER_ATTACHMENT, 8, 4);
		this.bridge.calls.clear();

		new DxRenderPassBackend(this.context, pass()
			.withColorAttachment(color, Optional.of(new Vector4f(0, 0, 0, 1)))
			.withDepthAttachment(depth)
			.build());

		assertEquals(
			List.of(
				"setRenderTargets(colors=[" + color.nativeViewHandle() + "],depth=" + depth.nativeViewHandle() + ",size=8x4)",
				"clearRenderTarget(" + color.nativeViewHandle() + ")"
			),
			this.bridge.calls
		);
	}

	@Test
	void eightColorAttachmentsAreStillAccepted() {
		RenderPassDescriptor.Builder builder = pass();
		for (int index = 0; index < 8; index++) {
			builder.withColorAttachment(colorView(8, 8));
		}
		this.bridge.calls.clear();

		new DxRenderPassBackend(this.context, builder.build());

		assertTrue(this.bridge.calls.get(0).startsWith("setRenderTargets(colors=["));
	}
}
