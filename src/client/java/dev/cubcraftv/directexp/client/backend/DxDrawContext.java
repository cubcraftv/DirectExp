package dev.cubcraftv.directexp.client.backend;

import dev.cubcraftv.directexp.bridge.DxBridge;

import java.util.Objects;

/**
 * What a command encoder hands every render pass it creates: the bridge, the
 * shared native scratch buffers, a lookup from Blaze3D's {@link RenderPipeline}
 * to the pipeline the device backend compiled for it (null when it was never
 * compiled), and the once-per-key diagnostics sink.
 */
record DxDrawContext(
	DxBridge dxBridge,
	DxDrawScratch scratch,
	DxDrawDiagnostics diagnostics
) {
	DxDrawContext {
		Objects.requireNonNull(dxBridge, "dxBridge");
		Objects.requireNonNull(scratch, "scratch");
		Objects.requireNonNull(diagnostics, "diagnostics");
	}
}
