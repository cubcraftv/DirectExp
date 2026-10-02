package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import dev.cubcraftv.directexp.bridge.DxBridge;

import java.util.Objects;
import java.util.function.Function;

/**
 * What a command encoder hands every render pass it creates: the bridge, the
 * shared native scratch buffers, a lookup from Blaze3D's {@link RenderPipeline}
 * to the pipeline the device backend compiled for it (null when it was never
 * compiled), and the once-per-key diagnostics sink.
 */
record DxDrawContext(
	DxBridge dxBridge,
	DxDrawScratch scratch,
	Function<RenderPipeline, DxRenderPipeline> pipelineLookup,
	DxDrawDiagnostics diagnostics
) {
	DxDrawContext {
		Objects.requireNonNull(dxBridge, "dxBridge");
		Objects.requireNonNull(scratch, "scratch");
		Objects.requireNonNull(pipelineLookup, "pipelineLookup");
		Objects.requireNonNull(diagnostics, "diagnostics");
	}
}
