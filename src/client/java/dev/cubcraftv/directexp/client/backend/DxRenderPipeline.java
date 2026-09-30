package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.client.shader.DxResourceLayout;

import java.util.Objects;

/**
 * The compiled form of one {@link RenderPipeline}: the native D3D11 pipeline
 * objects plus the resource layout the draw path needs to bind uniforms,
 * textures and samplers into the registers the HLSL was compiled against.
 * An invalid instance (any stage failed) carries the reason and no native
 * handle -- like the Vulkan backend's INVALID pipeline it is cached so a
 * broken pipeline is reported once, not recompiled every frame.
 */
final class DxRenderPipeline implements CompiledRenderPipeline {
	private final RenderPipeline pipeline;
	private final DxBridge dxBridge;
	private final long nativePipelineHandle;
	private final DxResourceLayout layout;
	private final String failureReason;
	private boolean closed;

	private DxRenderPipeline(RenderPipeline pipeline, DxBridge dxBridge, long nativePipelineHandle, DxResourceLayout layout, String failureReason) {
		this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
		this.dxBridge = dxBridge;
		this.nativePipelineHandle = nativePipelineHandle;
		this.layout = layout;
		this.failureReason = failureReason;
	}

	static DxRenderPipeline valid(RenderPipeline pipeline, DxBridge dxBridge, long nativePipelineHandle, DxResourceLayout layout) {
		if (nativePipelineHandle == 0L) {
			throw new IllegalArgumentException("A valid pipeline needs a non-zero native handle.");
		}
		return new DxRenderPipeline(pipeline, dxBridge, nativePipelineHandle, Objects.requireNonNull(layout, "layout"), null);
	}

	static DxRenderPipeline invalid(RenderPipeline pipeline, String reason) {
		return new DxRenderPipeline(pipeline, null, 0L, null, Objects.requireNonNull(reason, "reason"));
	}

	RenderPipeline pipeline() {
		return this.pipeline;
	}

	long nativePipelineHandle() {
		return this.nativePipelineHandle;
	}

	DxResourceLayout layout() {
		return this.layout;
	}

	String failureReason() {
		return this.failureReason;
	}

	@Override
	public boolean isValid() {
		return this.nativePipelineHandle != 0L && !this.closed;
	}

	void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		if (this.nativePipelineHandle != 0L && this.dxBridge != null) {
			this.dxBridge.releasePipeline(this.nativePipelineHandle);
		}
	}
}
