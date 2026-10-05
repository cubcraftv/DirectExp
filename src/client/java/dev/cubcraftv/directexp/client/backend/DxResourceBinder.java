package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxResourceKind;
import dev.cubcraftv.directexp.client.shader.DxResourceLayout;
import dev.cubcraftv.directexp.client.shader.DxResourceLayoutEntry;
import dev.cubcraftv.directexp.DirectExpMod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a pipeline's Milestone 4.4 {@link DxResourceLayout} plus what the
 * render pass currently has set into bridge binding calls: each layout entry
 * carries the engine resource name Blaze3D used and the register the HLSL was
 * compiled against, so this class only looks the name up in a {@link Source}
 * and binds to {@code entry.slot()} for the stages that use it.
 *
 * <p>Constant buffers and textures/samplers are separate calls because they
 * have different freshness rules: a uniform buffer's contents can change
 * between draws without {@code setUniform} being called again, so the pass
 * re-stages constants every draw, whereas an SRV/sampler binding only changes
 * when {@code bindTexture} does.
 */
final class DxResourceBinder {
	/** What the render pass has set, by engine resource name. */
	interface Source {
		/** The uniform's bytes (position..limit), or null if {@code setUniform} was never called for this name. */
		ByteBuffer uniformBytes(String name);

		/** Native texture-view handle bound under this name, or 0 when none / CPU-only. */
		long textureViewHandle(String name);

		/** Explains why {@link #textureViewHandle(String)} returned 0. */
		default String textureViewFailureReason(String name) {
			return "no native texture view is available";
		}

		/** Describes the texture behind a bound view for temporary draw diagnostics. */
		default String textureViewDescription(String name) {
			return "unavailable";
		}

		/** Native sampler handle bound under this name, or 0 when none / CPU-only. */
		long samplerHandle(String name);
	}

	private final DxBridge dxBridge;
	private final DxDrawScratch scratch;
	private final DxDrawDiagnostics diagnostics;
	private final Set<String> probedUniforms = new HashSet<>();

	DxResourceBinder(DxBridge dxBridge, DxDrawScratch scratch, DxDrawDiagnostics diagnostics) {
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.scratch = Objects.requireNonNull(scratch, "scratch");
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
	}

	static int stageMask(DxResourceLayoutEntry entry) {
		return (entry.usedInVertexStage() ? DxBridge.STAGE_VERTEX : 0) | (entry.usedInFragmentStage() ? DxBridge.STAGE_FRAGMENT : 0);
	}

	/**
	 * Stages and binds every constant buffer of the layout.
	 *
	 * @return false if a constant buffer could not be bound (the draw should be skipped)
	 */
	boolean bindConstants(DxResourceLayout layout, Source source) {
		boolean complete = true;
		for (DxResourceLayoutEntry entry : layout.entries()) {
			if (entry.kind() != DxResourceKind.CONSTANT_BUFFER) {
				continue;
			}

			ByteBuffer bytes = source.uniformBytes(entry.name());
			if (bytes == null) {
				this.diagnostics.reportOnce("uniform-unset:" + entry.name(), "pipeline " + layout.pipelineDebugLabel() + " uses uniform '" + entry.name() + "' but the render pass never set it");
				complete = false;
				continue;
			}

			probeUniformOnce(layout, entry, bytes);
			long handle = this.scratch.stageConstants(entry.slot(), bytes);
			if (handle == 0L) {
				complete = false;
				continue;
			}
			this.dxBridge.bindConstantBuffer(entry.slot(), handle, stageMask(entry));
		}
		return complete;
	}

	/** One-shot diagnostic: what the terrain-related uniform blocks actually contain when bound (std140 offsets). */
	private void probeUniformOnce(DxResourceLayout layout, DxResourceLayoutEntry entry, ByteBuffer bytes) {
		String name = entry.name();
		if (!(name.equals("ChunkSection") || name.equals("Globals")) || !this.probedUniforms.add(layout.pipelineDebugLabel() + ":" + name)) {
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

	/** Binds every sampled texture and sampler of the layout; a missing native object unbinds its slot. */
	void bindTextures(DxResourceLayout layout, Source source, boolean bindNativeState) {
		for (DxResourceLayoutEntry entry : layout.entries()) {
			switch (entry.kind()) {
				case SAMPLED_TEXTURE -> {
					long view = source.textureViewHandle(entry.name());
					if (entry.name().equals("Sampler0")) {
						String description = source.textureViewDescription(entry.name());
						if (layout.pipelineDebugLabel().contains("pipeline/gui") && description.contains("textures/atlas/gui.png")) {
							DirectExpMod.LOGGER.info(
								"DirectExp texture draw: pipeline={}, Sampler0 viewHandle={}, texture={}",
								layout.pipelineDebugLabel(), view, description
							);
						}
					}
					if (view == 0L) {
						this.diagnostics.reportOnce("texture-missing:" + layout.pipelineDebugLabel() + ":" + entry.name(), "pipeline " + layout.pipelineDebugLabel() + " samples '" + entry.name() + "' but " + source.textureViewFailureReason(entry.name()));
					}
					if (bindNativeState) {
						this.dxBridge.bindShaderResource(entry.slot(), view, stageMask(entry));
					}
				}
				case SAMPLER -> {
					long sampler = source.samplerHandle(entry.name());
					if (sampler == 0L) {
						this.diagnostics.reportOnce("sampler-missing:" + entry.name(), "pipeline " + layout.pipelineDebugLabel() + " uses sampler '" + entry.name() + "' but no native sampler is bound under that name");
					}
					if (bindNativeState) {
						this.dxBridge.bindSampler(entry.slot(), sampler, stageMask(entry));
					}
				}
				case STRUCTURED_BUFFER -> this.diagnostics.reportOnce("structured-buffer:" + entry.name(), "structured buffer '" + entry.name() + "' is not supported by the draw path yet");
				case CONSTANT_BUFFER -> {
				}
			}
		}
	}
}
