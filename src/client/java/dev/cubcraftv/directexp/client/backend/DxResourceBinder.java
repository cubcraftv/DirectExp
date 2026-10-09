package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxResourceKind;
import dev.cubcraftv.directexp.client.shader.DxResourceLayout;
import dev.cubcraftv.directexp.client.shader.DxResourceLayoutEntry;
import dev.cubcraftv.directexp.client.shader.DxShaderInterfaceLayout;

import java.nio.ByteBuffer;
import java.util.Objects;

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

		/** Native buffer SRV handle for texel-buffer / typed-buffer resources, or 0 when none / GPU-only. */
		default long bufferShaderResourceHandle(String name) {
			return 0L;
		}

		/** Explains why {@link #textureViewHandle(String)} returned 0. */
		default String textureViewFailureReason(String name) {
			return "no native texture view is available";
		}

		/** Describes the texture behind a bound view for opt-in debug probes. */
		default String textureViewDescription(String name) {
			return "unavailable";
		}

		/** Native sampler handle bound under this name, or 0 when none / CPU-only. */
		long samplerHandle(String name);
	}

	private final DxBridge dxBridge;
	private final DxDrawScratch scratch;
	private final DxDrawDiagnostics diagnostics;
	private final DxDebugProbes.UniformProbe uniformProbe = new DxDebugProbes.UniformProbe();

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
				String hint = entry.name().equals(DxShaderInterfaceLayout.PUSH_CONSTANT_BLOCK_NAME) ? " (its push constants; see DxPushConstants.setPushConstants)" : "";
				this.diagnostics.reportOnce("uniform-unset:" + entry.name(), "pipeline " + layout.pipelineDebugLabel() + " uses uniform '" + entry.name() + "'" + hint + " but the render pass never set it");
				complete = false;
				continue;
			}

			this.uniformProbe.logOnce(layout, entry, bytes);
			long handle = this.scratch.stageConstants(entry.slot(), bytes);
			if (handle == 0L) {
				complete = false;
				continue;
			}
			this.dxBridge.bindConstantBuffer(entry.slot(), handle, stageMask(entry));
		}
		return complete;
	}

	/** Binds every sampled texture and sampler of the layout; a missing native object unbinds its slot. */
	void bindTextures(DxResourceLayout layout, Source source, boolean bindNativeState) {
		for (DxResourceLayoutEntry entry : layout.entries()) {
			switch (entry.kind()) {
				case SAMPLED_TEXTURE -> {
					long view = source.bufferShaderResourceHandle(entry.name());
					if (view == 0L) {
						view = source.textureViewHandle(entry.name());
					}
					if (entry.name().equals("Sampler0")) {
						if (DxDebugProbes.enabled()) {
							DxDebugProbes.logGuiTextureBinding(layout, view, source.textureViewDescription(entry.name()));
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
				case STRUCTURED_BUFFER -> {
					long view = source.bufferShaderResourceHandle(entry.name());
					if (view == 0L) {
						this.diagnostics.reportOnce("structured-buffer-missing:" + layout.pipelineDebugLabel() + ":" + entry.name(), "pipeline " + layout.pipelineDebugLabel() + " uses structured/texel buffer '" + entry.name() + "' but " + "no native buffer SRV is available");
					}
					if (bindNativeState) {
						this.dxBridge.bindShaderResource(entry.slot(), view, stageMask(entry));
					}
				}
				case CONSTANT_BUFFER -> {
				}
			}
		}
	}
}
