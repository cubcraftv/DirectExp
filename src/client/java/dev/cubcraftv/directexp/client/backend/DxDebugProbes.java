package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cubcraftv.directexp.DirectExpMod;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxDebugProbeBridge;
import dev.cubcraftv.directexp.bridge.DxResourceKind;
import dev.cubcraftv.directexp.client.shader.DxResourceLayout;
import dev.cubcraftv.directexp.client.shader.DxResourceLayoutEntry;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

final class DxDebugProbes {
	private static final String ENABLED_PROPERTY = "directexp.debug.probes";
	private static final String GUI_SHEET_LABEL = "minecraft:gui";
	private static final String BUTTON_SPRITE_LABEL = "minecraft:widget/button";
	private static final AtomicBoolean LOGGED_GUI_ATLAS_COMPOSITION_DRAW = new AtomicBoolean(false);
	private static final AtomicBoolean LOGGED_BUTTON_SPRITE_UPLOAD = new AtomicBoolean(false);
	private static final AtomicInteger CPU_ACCESS_TRACE_BUDGET = new AtomicInteger(80);

	private DxDebugProbes() {
	}

	static boolean enabled() {
		return Boolean.getBoolean(ENABLED_PROPERTY);
	}

	static void traceCpuAccess(
		String debugLabel, boolean nativeRenderTarget, int textureWidth, int textureHeight,
		String operation, int mipLevel, int x, int y, int width, int height
	) {
		if (!enabled() || !nativeRenderTarget
			|| CPU_ACCESS_TRACE_BUDGET.get() <= 0
			|| CPU_ACCESS_TRACE_BUDGET.getAndDecrement() <= 0) {
			return;
		}
		DirectExpMod.LOGGER.info(
			"DirectExp TRACE CPU access to render-target texture: {} on {} {}x{} mip={} rect=({},{}) {}x{}",
			operation, debugLabel, textureWidth, textureHeight, mipLevel, x, y, width, height
		);
	}

	static SpriteSource spriteSource(GpuTextureView textureView) {
		if (textureView instanceof DxTextureView view && view.texture() instanceof DxTexture texture) {
			return new SpriteSource(view, texture);
		}
		return null;
	}

	static int[] captureAtlasTexelsBeforeDraw(
		DxTexture colorTarget,
		int colorTargetMipLevel,
		RenderPipeline pipeline,
		SpriteSource sprite,
		DxBridge dxBridge,
		DxResourceBinder.Source bindingSource
	) {
		DxDebugProbeBridge probeBridge = debugBridge(dxBridge);
		if (!enabled() || probeBridge == null || !isButtonAtlasDraw(colorTarget, pipeline) || sprite == null
			|| !sprite.texture().debugLabel().equals(BUTTON_SPRITE_LABEL)
			|| LOGGED_GUI_ATLAS_COMPOSITION_DRAW.get()
			|| !colorTarget.hasNativeTextureHandle()) {
			return null;
		}
		try {
			ByteBuffer info = bindingSource.uniformBytes("SpriteAnimationInfo");
			if (info == null || info.remaining() < 128) {
				return null;
			}
			return readAtlasProbeTexels(probeBridge, colorTarget, colorTargetMipLevel, info);
		} catch (RuntimeException error) {
			return null;
		}
	}

	static void logGuiAtlasCompositionDraw(
		DxTexture colorTarget,
		DxTextureView colorView,
		int colorTargetMipLevel,
		RenderPipeline pipeline,
		DxRenderPipeline boundPipeline,
		SpriteSource sprite,
		DxResourceBinder.Source bindingSource,
		DxBridge dxBridge,
		int vertexCount,
		int firstVertex,
		int[] atlasBefore
	) {
		DxDebugProbeBridge probeBridge = debugBridge(dxBridge);
		if (!enabled() || probeBridge == null || !isButtonAtlasDraw(colorTarget, pipeline) || sprite == null
			|| !sprite.texture().debugLabel().equals(BUTTON_SPRITE_LABEL)
			|| !LOGGED_GUI_ATLAS_COMPOSITION_DRAW.compareAndSet(false, true)) {
			return;
		}

		DxTexture texture = sprite.texture();
		DxTextureView view = sprite.view();
		String sourceDescription = "view=" + view.nativeViewHandle()
			+ ", closed=" + view.isClosed()
			+ ", texture=" + texture.debugDescription();
		String srvBinding = boundPipeline == null ? "layout unavailable" : boundPipeline.layout()
			.find("Sprite", DxResourceKind.SAMPLED_TEXTURE)
			.map(entry -> "slot=" + entry.slot() + ", stageMask=" + DxResourceBinder.stageMask(entry))
			.orElse("Sprite SRV absent from pipeline layout");
		String samplerBinding = boundPipeline == null ? "layout unavailable" : boundPipeline.layout()
			.find("Sprite", DxResourceKind.SAMPLER)
			.map(entry -> "slot=" + entry.slot() + ", stageMask=" + DxResourceBinder.stageMask(entry))
			.orElse("Sprite sampler absent from pipeline layout");
		ByteBuffer spriteInfo = bindingSource.uniformBytes("SpriteAnimationInfo");
		String spriteInfoValues = "unavailable";
		String spriteInfoBytes = "unavailable";
		if (spriteInfo != null && spriteInfo.remaining() >= 128) {
			ByteBuffer values = spriteInfo.duplicate().order(ByteOrder.nativeOrder());
			float[] matrixValues = new float[32];
			for (int i = 0; i < matrixValues.length; i++) {
				matrixValues[i] = values.getFloat(values.position() + i * Float.BYTES);
			}
			spriteInfoValues = Arrays.toString(matrixValues);
			spriteInfoBytes = String.format(
				"%02x %02x %02x %02x; order=%s",
				spriteInfo.get(0) & 0xff, spriteInfo.get(1) & 0xff,
				spriteInfo.get(2) & 0xff, spriteInfo.get(3) & 0xff,
				spriteInfo.order()
			);
		}
		DirectExpMod.LOGGER.info(
			"DirectExp GUI atlas composition draw: pipeline={}, target={} targetView={}, Sprite source={}, SRV binding={} handle={}, sampler binding={} handle={}, firstVertex={}, vertexCount={}, SpriteAnimationInfoMatrices={}, leadingBytes={}",
			pipeline.getLocation(), colorTarget.debugDescription(),
			colorView == null ? 0L : colorView.nativeViewHandle(),
			sourceDescription, srvBinding, bindingSource.textureViewHandle("Sprite"),
			samplerBinding, bindingSource.samplerHandle("Sprite"),
			firstVertex, vertexCount, spriteInfoValues, spriteInfoBytes
		);
		logAtlasPixelProbe(
			probeBridge, colorTarget, colorTargetMipLevel, texture, spriteInfo, atlasBefore,
			bindingSource, vertexCount, firstVertex
		);
	}

	static void logButtonSpriteUpload(
		String debugLabel,
		String format,
		ByteBuffer source,
		ByteBuffer mirror,
		int sourceBytesPerPixel,
		int sourceStride,
		int destinationWidth,
		int destinationBytesPerPixel,
		int mipLevel,
		int dstX,
		int dstY,
		int width,
		int height,
		int srcX,
		int srcY
	) {
		if (!enabled() || !debugLabel.equals(BUTTON_SPRITE_LABEL) || !LOGGED_BUTTON_SPRITE_UPLOAD.compareAndSet(false, true)) {
			return;
		}

		int nonZeroPixels = 0;
		int nonZeroAlphaPixels = 0;
		int mirroredNonZeroPixels = 0;
		int mirroredNonZeroAlphaPixels = 0;
		for (int row = 0; row < height; row++) {
			for (int column = 0; column < width; column++) {
				int offset = source.position() + ((srcY + row) * sourceStride + (srcX + column)) * sourceBytesPerPixel;
				int destinationOffset = ((dstY + row) * destinationWidth + dstX + column) * destinationBytesPerPixel;
				boolean sourcePixelNonZero = false;
				boolean mirrorPixelNonZero = false;
				for (int channel = 0; channel < sourceBytesPerPixel; channel++) {
					if (source.get(offset + channel) != 0) {
						sourcePixelNonZero = true;
					}
				}
				for (int channel = 0; channel < destinationBytesPerPixel; channel++) {
					if (mirror.get(destinationOffset + channel) != 0) {
						mirrorPixelNonZero = true;
					}
				}
				if (sourcePixelNonZero) {
					nonZeroPixels++;
				}
				if (sourceBytesPerPixel >= 4 && source.get(offset + 3) != 0) {
					nonZeroAlphaPixels++;
				}
				if (mirrorPixelNonZero) {
					mirroredNonZeroPixels++;
				}
				if (destinationBytesPerPixel >= 4 && mirror.get(destinationOffset + 3) != 0) {
					mirroredNonZeroAlphaPixels++;
				}
			}
		}

		DirectExpMod.LOGGER.info(
			"DirectExp button sprite source write: texture={} format={} mip={} layer=0 dst=({}, {}) size={}x{} srcPos={} srcLimit={} sourceNonZero={}/{} sourceAlphaNonZero={}/{} mirrorNonZero={}/{} mirrorAlphaNonZero={}/{}",
			debugLabel, format, mipLevel, dstX, dstY, width, height, source.position(), source.limit(),
			nonZeroPixels, width * height, nonZeroAlphaPixels, width * height,
			mirroredNonZeroPixels, width * height, mirroredNonZeroAlphaPixels, width * height
		);
	}

	static void logGuiTextureBinding(DxResourceLayout layout, long view, String description) {
		if (enabled() && layout.pipelineDebugLabel().contains("pipeline/gui") && description.contains("textures/atlas/gui.png")) {
			DirectExpMod.LOGGER.info(
				"DirectExp texture draw: pipeline={}, Sampler0 viewHandle={}, texture={}",
				layout.pipelineDebugLabel(), view, description
			);
		}
	}

	private static boolean isButtonAtlasDraw(DxTexture colorTarget, RenderPipeline pipeline) {
		return colorTarget.debugLabel().equals(GUI_SHEET_LABEL)
			&& pipeline != null
			&& pipeline.getLocation().toString().contains("animate_sprite_blit");
	}

	private static DxDebugProbeBridge debugBridge(DxBridge bridge) {
		return bridge instanceof DxDebugProbeBridge probes ? probes : null;
	}

	private static int[] readAtlasProbeTexels(DxDebugProbeBridge probeBridge, DxTexture colorTarget, int mipLevel, ByteBuffer spriteInfo) {
		ByteBuffer v = spriteInfo.duplicate().order(ByteOrder.nativeOrder());
		int base = v.position();
		float scaleX = v.getFloat(base + 64), scaleY = v.getFloat(base + 64 + 20);
		float posX = v.getFloat(base + 64 + 48), posY = v.getFloat(base + 64 + 52);
		int[] xs = { Math.round(posX + 1.0F), Math.round(posX + 1.0F + 0.5F * (scaleX - 3.0F)), Math.round(posX + 1.0F + (scaleX - 3.0F)), Math.round(posX) - 4 };
		int[] ys = { Math.round(posY + 1.0F), Math.round(posY + 1.0F + 0.5F * (scaleY - 3.0F)), Math.round(posY + 1.0F + (scaleY - 3.0F)), Math.round(posY) - 4 };
		int[] result = new int[4];
		for (int i = 0; i < 4; i++) {
			result[i] = Integer.reverseBytes(probeBridge.readTexturePixelRgba8(colorTarget.nativeTextureHandle(), mipLevel, xs[i], ys[i]));
		}
		return result;
	}

	private static void logAtlasPixelProbe(
		DxDebugProbeBridge probeBridge,
		DxTexture colorTarget,
		int colorTargetMipLevel,
		DxTexture sprite,
		ByteBuffer spriteInfo,
		int[] atlasBefore,
		DxResourceBinder.Source bindingSource,
		int vertexCount,
		int firstVertex
	) {
		try {
			if (spriteInfo == null || spriteInfo.remaining() < 140 || !colorTarget.hasNativeTextureHandle()) {
				return;
			}
			ByteBuffer v = spriteInfo.duplicate().order(ByteOrder.nativeOrder());
			int base = v.position();
			float scaleX = v.getFloat(base + 64), scaleY = v.getFloat(base + 64 + 20);
			float posX = v.getFloat(base + 64 + 48), posY = v.getFloat(base + 64 + 52);
			float uPadding = v.getFloat(base + 128), vPadding = v.getFloat(base + 132);
			int mipLevel = v.getInt(base + 136);
			StringBuilder points = new StringBuilder();
			float[][] samples = { { 0.0F, 0.0F }, { 0.5F, 0.5F }, { 1.0F, 1.0F } };
			int spriteWidth = sprite.getWidth(0), spriteHeight = sprite.getHeight(0);
			for (float[] uv : samples) {
				int ax = Math.round(posX + 1.0F + uv[0] * (scaleX - 3.0F));
				int ay = Math.round(posY + 1.0F + uv[1] * (scaleY - 3.0F));
				int sx = Math.min(spriteWidth - 1, Math.round(uv[0] * (spriteWidth - 1)));
				int sy = Math.min(spriteHeight - 1, Math.round(uv[1] * (spriteHeight - 1)));
				int atlas = Integer.reverseBytes(probeBridge.readTexturePixelRgba8(colorTarget.nativeTextureHandle(), colorTargetMipLevel, ax, ay));
				ByteBuffer expected = sprite.readRegion(0, sx, sy, 1, 1);
				int gpuSprite = Integer.reverseBytes(probeBridge.readTexturePixelRgba8(sprite.nativeTextureHandle(), 0, sx, sy));
				points.append(String.format(" [uv=%.1f,%.1f atlas(%d,%d)=%08x spriteMirror(%d,%d)=%02x%02x%02x%02x spriteGPU(%d,%d)=%08x]", uv[0], uv[1], ax, ay, atlas, sx, sy,
					expected.get(0) & 0xff, expected.get(1) & 0xff, expected.get(2) & 0xff, expected.get(3) & 0xff, sx, sy, gpuSprite));
			}
			int[] atlasAfter = readAtlasProbeTexels(probeBridge, colorTarget, colorTargetMipLevel, spriteInfo);
			StringBuilder beforeAfter = new StringBuilder();
			String[] names = { "corner", "centre", "farCorner", "controlOutside" };
			for (int i = 0; i < 4; i++) {
				beforeAfter.append(String.format(" %s: before=%s after=%08x;", names[i], atlasBefore == null ? "n/a" : String.format("%08x", atlasBefore[i]), atlasAfter[i]));
			}

			DirectExpMod.LOGGER.info("DirectExp PROBE draw effect (atlas texels before vs after the button blit):{}", beforeAfter);
			int untouched = Integer.reverseBytes(probeBridge.readTexturePixelRgba8(colorTarget.nativeTextureHandle(), colorTargetMipLevel, 1, 1));
			DirectExpMod.LOGGER.info("DirectExp PROBE atlas clear color: texel (1,1) outside every sprite = {} (RGBA)", String.format("%08x", untouched));
			DirectExpMod.LOGGER.info("DirectExp PROBE draw state: {}", probeBridge.describeDrawState(0, sprite.nativeTextureHandle()));
			int centreX = Math.round(posX + 1.0F + 0.5F * (scaleX - 3.0F));
			int centreY = Math.round(posY + 1.0F + 0.5F * (scaleY - 3.0F));
			DirectExpMod.LOGGER.info("DirectExp PROBE pixel substitution at atlas({},{}) (expected 707070ff where a sample happens): {}",
				centreX, centreY, probeBridge.runPixelSubstitutionProbe(centreX, centreY, vertexCount, firstVertex));
			DirectExpMod.LOGGER.info("DirectExp PROBE sampling experiment (sprite centre texel, expected 707070ff): {}",
				probeBridge.runSamplingExperiment(bindingSource.textureViewHandle("Sprite")));
			DirectExpMod.LOGGER.info(
				"DirectExp PROBE button blit: UPadding={} VPadding={} MipMapLevel={} dest=({},{}) {}x{} atlasMip={} samples:{}",
				uPadding, vPadding, mipLevel, posX, posY, scaleX, scaleY, colorTargetMipLevel, points
			);
		} catch (RuntimeException error) {
			DirectExpMod.LOGGER.info("DirectExp PROBE button blit: readback failed: {}", error.toString());
		}
	}

	static final class UniformProbe {
		private final Set<String> probedUniforms = new HashSet<>();

		void logOnce(DxResourceLayout layout, DxResourceLayoutEntry entry, ByteBuffer bytes) {
			if (!enabled()) {
				return;
			}
			String name = entry.name();
			if (!(name.equals("ChunkSection") || name.equals("Globals"))
				|| !this.probedUniforms.add(layout.pipelineDebugLabel() + ":" + name)) {
				return;
			}
			ByteBuffer v = bytes.duplicate().order(ByteOrder.nativeOrder());
			int b = v.position();
			String values;
			if (name.equals("ChunkSection") && v.remaining() >= 92) {
				values = "ChunkVisibility=" + v.getFloat(b + 64) + " TextureSize=(" + v.getInt(b + 72) + "," + v.getInt(b + 76) + ") ChunkPosition=("
					+ v.getInt(b + 80) + "," + v.getInt(b + 84) + "," + v.getInt(b + 88) + ")";
			} else if (name.equals("Globals") && v.remaining() >= 56) {
				values = "CameraBlockPos=(" + v.getInt(b) + "," + v.getInt(b + 4) + "," + v.getInt(b + 8) + ") ScreenSize=(" + v.getFloat(b + 32) + "," + v.getFloat(b + 36)
					+ ") MenuBlurRadius=" + v.getInt(b + 48) + " UseRgss=" + v.getInt(b + 52);
			} else {
				values = "(too short to decode)";
			}
			DirectExpMod.LOGGER.info("DirectExp PROBE uniform: pipeline={} block={} slot={} reflectedSize={} boundBytes={} {}",
				layout.pipelineDebugLabel(), name, entry.slot(), entry.sizeBytes(), bytes.remaining(), values);
		}
	}

	record SpriteSource(DxTextureView view, DxTexture texture) {
	}
}
