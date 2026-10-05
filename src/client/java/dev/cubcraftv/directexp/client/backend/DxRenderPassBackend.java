package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.cubcraftv.directexp.bridge.DxBridge;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;

/**
 * One Blaze3D render pass on the D3D11 immediate context (Milestone 4.6,
 * commit 4).
 *
 * <p>Blaze3D pushes state in (pipeline, textures, uniforms, buffers, scissor);
 * this class records it and turns it into bridge calls lazily, at draw time,
 * because D3D11 binding state is sticky and only what changed needs re-sending:
 * <ul>
 *   <li><b>pass start</b>: render/depth target binding, viewport
 *       {@code (0, 0, w, h)}, and the optional clears;</li>
 *   <li><b>first draw with a pipeline</b>: {@code bindPipeline}, then vertex
 *       buffers (the stride comes from the pipeline), then the pipeline's
 *       textures/samplers;</li>
 *   <li><b>every draw</b>: scissor when changed (it must be set at least once,
 *       D3D11's default scissor rectangle is empty and every pipeline enables
 *       scissor), the index buffer when changed, and all constant buffers
 *       (their contents can change without {@code setUniform} being called
 *       again; dirty tracking by buffer version is a later optimisation).</li>
 * </ul>
 * CPU-side buffer mirrors stay authoritative (see {@link DxBuffer}); uniform
 * slices are read from them and copied into per-slot scratch constant buffers
 * ({@link DxDrawScratch}), so uniform buffers over 64 KiB never need a native
 * mirror.
 *
 * <p>A draw that cannot be issued (pipeline invalid or never compiled, a
 * buffer without a native handle, a native failure) is skipped and reported
 * once per cause through {@link DxDrawDiagnostics}; it never throws, because a
 * single bad pipeline must not take the frame down.
 *
 * <p>{@link #drawMultipleIndexed} follows the decompiled
 * {@code VulkanRenderPass} line for line (see the method comment).
 */
final class DxRenderPassBackend implements RenderPassBackend {
	private final DxTextureView colorView;
	private final DxTextureView depthView;
	private final DxTexture colorTarget;
	private final int colorTargetMipLevel;
	private final int targetWidth;
	private final int targetHeight;
	private final boolean fullRenderArea;
	private final DxDrawContext drawContext;
	private final DxBridge dxBridge;
	private final DxResourceBinder resourceBinder;
	private final DxResourceBinder.Source bindingSource = new PassBindingSource();
	private final Map<String, BoundTexture> boundTextures = new HashMap<>();
	private final Map<String, GpuBuffer> uniformBuffers = new HashMap<>();
	private final Map<String, GpuBufferSlice> uniformSlices = new HashMap<>();
	private final Map<Integer, GpuBufferSlice> vertexBuffers = new HashMap<>();
	private final DxTriangleFanExpander fanExpander = new DxTriangleFanExpander();
	private final boolean nativeTargetsBound;

	private RenderPipeline pipeline;
	private DxBuffer indexBuffer;
	private IndexType indexType;
	private boolean scissorEnabled;
	private int scissorX;
	private int scissorY;
	private int scissorWidth;
	private int scissorHeight;

	// Lazy-binding state. A fresh pass starts with everything dirty because D3D11
	// state left over from a previous pass says nothing about this one.
	private DxRenderPipeline boundPipeline;
	private boolean scissorDirty = true;
	private boolean vertexBuffersDirty = true;
	private boolean indexBufferDirty = true;
	private boolean texturesDirty = true;

	DxRenderPassBackend(DxDrawContext drawContext, RenderPassDescriptor descriptor) {
		this.drawContext = drawContext;
		this.dxBridge = drawContext == null ? null : drawContext.dxBridge();
		this.resourceBinder = drawContext == null ? null : new DxResourceBinder(drawContext.dxBridge(), drawContext.scratch(), drawContext.diagnostics());

		if (descriptor.colorAttachments().size() != 1 || descriptor.colorAttachments().get(0) == null) {
			throw new UnsupportedOperationException("DirectX render passes currently require exactly one color attachment.");
		}
		RenderPassDescriptor.Attachment<Optional<Vector4fc>> colorAttachment = descriptor.colorAttachments().get(0);
		GpuTextureView colorTextureView = colorAttachment.textureView();
		RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
		GpuTextureView depthTextureView = depthAttachment == null ? null : depthAttachment.textureView();
		Optional<Vector4fc> clearColor = colorAttachment.clearValue();
		OptionalDouble clearDepth = depthAttachment == null ? OptionalDouble.empty() : depthAttachment.clearValue();
		RenderPass.RenderArea renderArea = descriptor.renderArea;
		if (renderArea == null) {
			throw new IllegalArgumentException("DirectX render pass requires an explicit render area.");
		}

		if (colorTextureView == null || colorTextureView.isClosed()) {
			throw new IllegalArgumentException("DirectX render pass requires a live color texture view.");
		}

		if (!(colorTextureView.texture() instanceof DxTexture dxTexture)) {
			throw new IllegalArgumentException("DirectX render pass requires a DirectX texture view.");
		}

		if (dxTexture.getFormat() != GpuFormat.RGBA8_UNORM) {
			throw new IllegalArgumentException("DirectX render pass currently only supports RGBA8_UNORM color targets.");
		}

		this.colorTarget = dxTexture;
		this.colorTargetMipLevel = colorTextureView.baseMipLevel();
		this.colorView = colorTextureView instanceof DxTextureView dxView ? dxView : null;
		this.depthView = depthTextureView instanceof DxTextureView dxDepthView && !dxDepthView.isClosed() ? dxDepthView : null;
		this.targetWidth = colorTextureView.getWidth(0);
		this.targetHeight = colorTextureView.getHeight(0);
		this.fullRenderArea = renderArea.x() == 0 && renderArea.y() == 0
			&& renderArea.width() == this.targetWidth && renderArea.height() == this.targetHeight;
		this.nativeTargetsBound = beginNativePass(clearColor, clearDepth, renderArea);
		if (!this.fullRenderArea) {
			this.scissorEnabled = true;
			this.scissorX = renderArea.x();
			this.scissorY = renderArea.y();
			this.scissorWidth = renderArea.width();
			this.scissorHeight = renderArea.height();
		}
	}

	/**
	 * Binds the targets, sets the viewport and performs the pass's clears.
	 *
	 * @return false when the pass has no native targets (CPU-only texture, no bridge, or a native failure); its draws are then skipped
	 */
	private boolean beginNativePass(Optional<Vector4fc> clearColor, OptionalDouble clearDepth, RenderPass.RenderArea renderArea) {
		if (this.dxBridge == null) {
			return false;
		}

		if (this.colorView == null || !this.colorView.hasNativeRenderTargetView()) {
			report("pass-no-native-color", "a render pass color target has no native render-target view (texture created without a native handle or without render-attachment usage); its draws are skipped");
			return false;
		}

		boolean hasDepthTarget = this.depthView != null && this.depthView.hasNativeDepthStencilView();
		if (this.depthView != null && !hasDepthTarget) {
			report("pass-no-native-depth", "a render pass depth target has no native depth-stencil view; the pass runs without depth");
		}

		try {
			// A rectangle depth clear draws through the output merger, so it has to run
			// before this pass binds its own targets.
			if (hasDepthTarget && clearDepth.isPresent() && !this.fullRenderArea && this.depthView.nativeViewHandle() != 0L
				&& this.depthView.texture() instanceof DxTexture depthTexture) {
				this.dxBridge.clearDepthRegion(depthTexture.nativeTextureHandle(), renderArea.x(), renderArea.y(), renderArea.width(), renderArea.height(), (float) clearDepth.getAsDouble());
			}
			this.dxBridge.setRenderTargets(this.colorView.nativeViewHandle(), hasDepthTarget ? this.depthView.nativeViewHandle() : 0L, this.targetWidth, this.targetHeight);
			if (clearColor.isPresent() && this.fullRenderArea) {
				Vector4fc rgba = clearColor.get();
				this.dxBridge.clearRenderTarget(this.colorView.nativeViewHandle(), rgba.x(), rgba.y(), rgba.z(), rgba.w());
			} else if (clearColor.isPresent()) {
				report("partial-color-clear", "a partial-rectangle color clear was requested; it is skipped (no D3D11.0 equivalent)");
			}
			if (hasDepthTarget && clearDepth.isPresent() && this.fullRenderArea) {
				this.dxBridge.clearDepthStencil(this.depthView.nativeViewHandle(), (float) clearDepth.getAsDouble());
			}
			return true;
		} catch (UnsupportedOperationException | IllegalStateException error) {
			report("pass-begin", "could not begin a native render pass: " + error.getMessage());
			return false;
		}
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void setPipeline(RenderPipeline renderPipeline) {
		this.pipeline = renderPipeline;
	}

	@Override
	public void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler) {
		if (name == null) {
			throw new IllegalArgumentException("DirectX render pass texture binding requires a name.");
		}
		// Same contract as the Vulkan backend: both null clears the binding, exactly one null is a bug.
		if (textureView == null && sampler == null) {
			this.boundTextures.remove(name);
		} else if (textureView != null && sampler != null) {
			this.boundTextures.put(name, new BoundTexture(textureView, sampler));
		} else {
			throw new IllegalArgumentException("DirectX render pass texture binding needs both a texture view and a sampler, or neither.");
		}
		this.texturesDirty = true;
	}

	@Override
	public void setUniform(String name, GpuBuffer gpuBuffer) {
		this.uniformBuffers.put(name, gpuBuffer);
		this.uniformSlices.remove(name);
	}

	@Override
	public void setUniform(String name, GpuBufferSlice gpuBufferSlice) {
		this.uniformSlices.put(name, gpuBufferSlice);
		this.uniformBuffers.remove(name);
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
		this.scissorEnabled = true;
		this.scissorX = x;
		this.scissorY = y;
		this.scissorWidth = width;
		this.scissorHeight = height;
		this.scissorDirty = true;
	}

	@Override
	public void disableScissor() {
		this.scissorEnabled = false;
		this.scissorDirty = true;
	}

	@Override
	public void setVertexBuffer(int slot, GpuBufferSlice gpuBufferSlice) {
		if (gpuBufferSlice == null) {
			this.vertexBuffers.remove(slot);
		} else {
			this.vertexBuffers.put(slot, gpuBufferSlice);
		}
		this.vertexBuffersDirty = true;
	}

	@Override
	public void setIndexBuffer(GpuBuffer gpuBuffer, IndexType indexType) {
		this.indexBuffer = (DxBuffer) gpuBuffer;
		this.indexType = indexType;
		this.indexBufferDirty = true;
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
		if (firstInstance != 0) {
			report("draw-indexed-first-instance", "a non-zero first instance is not supported; draw skipped");
			return;
		}
		boolean fan = isTriangleFanPipeline();
		if (this.indexBuffer == null || this.indexType == null) {
			report("draw-no-index-buffer", "drawIndexed without an index buffer; draw skipped");
			return;
		}

		if (fan) {
			// The pipeline topology is TRIANGLELIST; the fan's indices become
			// triangle-list indices here, still relative to the draw's base vertex.
			expandIndexedFan(firstIndex, indexCount);
		}

		if (!prepareDraw()) {
			return;
		}

		try {
			if (fan) {
				issueExpandedFan(instanceCount, baseVertex);
				return;
			}

			if (!bindIndexBufferIfDirty()) {
				return;
			}
			this.dxBridge.drawIndexed(indexCount, instanceCount, firstIndex, baseVertex);
		} catch (UnsupportedOperationException | IllegalStateException error) {
			report("draw-indexed", "DrawIndexedInstanced failed: " + error.getMessage());
		}
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		IntBuffer parameters = drawParameters.duplicate();
		for (int drawIndex = 0; drawIndex < drawCount; drawIndex++) {
			int firstIndex = parameters.get();
			int indexCount = parameters.get();
			int baseVertex = parameters.get();
			drawIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
		}
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
		throw new UnsupportedOperationException("separate-parameter multi-draw is not supported by the DirectX backend");
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
		throw new UnsupportedOperationException("indirect indexed draws are not supported by the DirectX backend");
	}

	@Override
	public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer defaultIndexBuffer, IndexType defaultIndexType, Collection<String> dynamicUniforms, T userContext) {
		// Mirrors VulkanRenderPass.drawMultipleIndexed: per draw, let the draw's
		// uploader replace uniforms (chunk-section offsets etc.), pick the draw's
		// index buffer or the default, bind vertex buffer 0, draw one instance.
		// dynamicUniforms is unused there too: uniforms are re-staged every draw anyway.
		for (RenderPass.Draw<T> draw : draws) {
			BiConsumer<T, RenderPass.UniformUploader> uploader = draw.uniformUploaderConsumer();
			if (uploader != null) {
				uploader.accept(userContext, (name, slice) -> setUniform(name, slice));
			}

			GpuBuffer indexBuffer = draw.indexBuffer() == null ? defaultIndexBuffer : draw.indexBuffer();
			IndexType indexType = draw.indexType() == null ? defaultIndexType : draw.indexType();
			if (indexBuffer == null || indexType == null) {
				report("draw-multiple-no-index", "a batched draw has neither its own nor a default index buffer/type; draw skipped");
				continue;
			}

			setIndexBuffer(indexBuffer, indexType);
			setVertexBuffer(draw.slot(), draw.vertexBuffer().slice());
			drawIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
		}
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
		if (instanceCount != 1 || firstInstance != 0) {
			report("draw-instance-parameters", "instanced non-indexed draws are not supported; draw skipped");
			return;
		}
		boolean fan = isTriangleFanPipeline();
		if (fan) {
			// Expanded into absolute vertex numbers; drawn indexed with base vertex 0.
			this.fanExpander.expandNonIndexed(firstVertex, vertexCount);
		}

		if (!prepareDraw()) {
			return;
		}

		try {
			if (fan) {
				issueExpandedFan(1, 0);
				return;
			}
			boolean probesEnabled = DxDebugProbes.enabled();
			DxDebugProbes.SpriteSource sprite = probesEnabled
				? DxDebugProbes.spriteSource(boundTextureView("Sprite"))
				: null;
			int[] atlasBefore = probesEnabled
				? DxDebugProbes.captureAtlasTexelsBeforeDraw(
					this.colorTarget, this.colorTargetMipLevel, this.pipeline, sprite, this.dxBridge, this.bindingSource
				)
				: null;
			this.dxBridge.draw(vertexCount, firstVertex);
			if (probesEnabled) {
				DxDebugProbes.logGuiAtlasCompositionDraw(
					this.colorTarget, this.colorView, this.colorTargetMipLevel, this.pipeline, this.boundPipeline,
					sprite, this.bindingSource, this.dxBridge, vertexCount, firstVertex, atlasBefore
				);
			}
		} catch (UnsupportedOperationException | IllegalStateException error) {
			report("draw", "Draw failed: " + error.getMessage());
		}
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
		IntBuffer parameters = drawParameters.duplicate();
		for (int drawIndex = 0; drawIndex < drawCount; drawIndex++) {
			int firstVertex = parameters.get();
			int vertexCount = parameters.get();
			draw(vertexCount, instanceCount, firstVertex, firstInstance);
		}
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
		IntBuffer vertices = firstVertices.duplicate();
		IntBuffer counts = vertexCounts.duplicate();
		for (int drawIndex = 0; drawIndex < drawCount; drawIndex++) {
			draw(counts.get(), 1, vertices.get(), 0);
		}
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
		throw new UnsupportedOperationException("indirect draws are not supported by the DirectX backend");
	}

	@Override
	public void writeTimestamp(GpuQueryPool gpuQueryPool, int index) {
	}

	/** D3D11 has no fan topology: fan pipelines are TRIANGLELIST and every draw expands its indices. */
	boolean isTriangleFanPipeline() {
		return this.pipeline != null && this.pipeline.getPrimitiveTopology() == com.mojang.blaze3d.PrimitiveTopology.TRIANGLE_FAN;
	}

	/** Expands the bound index buffer's fan range into {@link #fanExpander()}; indices stay relative to the draw's base vertex. */
	void expandIndexedFan(int firstIndex, int indexCount) {
		int indexBytes = this.indexType.bytes;
		this.fanExpander.expandIndexed(this.indexBuffer.logicalStorage(), indexBytes, firstIndex, indexCount);
	}

	DxTriangleFanExpander fanExpander() {
		return this.fanExpander;
	}

	DxTexture colorTarget() {
		return this.colorTarget;
	}

	int colorTargetMipLevel() {
		return this.colorTargetMipLevel;
	}

	private GpuTextureView boundTextureView(String name) {
		BoundTexture bound = this.boundTextures.get(name);
		return bound == null ? null : bound.textureView();
	}

	/**
	 * Brings the D3D11 pipeline state up to date for one draw.
	 *
	 * @return false when the draw must be skipped (reported once)
	 */
	private boolean prepareDraw() {
		if (!this.nativeTargetsBound) {
			return false;
		}

		if (this.pipeline == null) {
			report("draw-no-pipeline", "draw without a pipeline; draw skipped");
			return false;
		}

		DxRenderPipeline compiled = this.drawContext.pipelineLookup().apply(this.pipeline);
		if (compiled == null) {
			report("pipeline-not-compiled:" + this.pipeline.getLocation(), "pipeline " + this.pipeline.getLocation() + " was never compiled by the device backend; draws skipped");
			return false;
		}
		if (!compiled.isValid()) {
			// Already reported when it failed to compile; don't spam per draw.
			return false;
		}

		try {
			if (compiled != this.boundPipeline) {
				this.dxBridge.bindPipeline(compiled.nativePipelineHandle());
				this.boundPipeline = compiled;
				// Stride (vertex buffers) and register layout (textures) belong to the pipeline.
				this.vertexBuffersDirty = true;
				this.texturesDirty = true;
			}

			if (this.scissorDirty) {
				int[] rect = this.scissorEnabled
					? DxScissorMapping.toTopLeftRect(this.scissorX, this.scissorY, this.scissorWidth, this.scissorHeight, this.targetWidth, this.targetHeight)
					: DxScissorMapping.fullRect(this.targetWidth, this.targetHeight);
				this.dxBridge.setScissor(rect[0], rect[1], rect[2], rect[3]);
				this.scissorDirty = false;
			}

			if (this.vertexBuffersDirty) {
				for (Map.Entry<Integer, GpuBufferSlice> entry : this.vertexBuffers.entrySet()) {
					DxBuffer buffer = (DxBuffer) entry.getValue().buffer();
					if (!buffer.hasNativeBufferHandle()) {
						report("vertex-buffer-no-native", "a vertex buffer has no native handle; draws using it are skipped");
						return false;
					}
					this.dxBridge.bindVertexBuffer(entry.getKey(), buffer.nativeBufferHandle(), Math.toIntExact(entry.getValue().offset()));
				}
				this.vertexBuffersDirty = false;
			}

			boolean bindTextureState = this.texturesDirty;
			this.resourceBinder.bindTextures(compiled.layout(), this.bindingSource, bindTextureState);
			if (bindTextureState) {
				this.texturesDirty = false;
			}

			return this.resourceBinder.bindConstants(compiled.layout(), this.bindingSource);
		} catch (UnsupportedOperationException | IllegalStateException error) {
			report("prepare-draw", "binding pipeline state failed: " + error.getMessage());
			return false;
		}
	}

	private boolean bindIndexBufferIfDirty() {
		if (!this.indexBufferDirty) {
			return true;
		}
		if (!this.indexBuffer.hasNativeBufferHandle()) {
			report("index-buffer-no-native", "an index buffer has no native handle; draws using it are skipped");
			return false;
		}
		this.dxBridge.bindIndexBuffer(this.indexBuffer.nativeBufferHandle(), this.indexType == IndexType.INT, 0);
		this.indexBufferDirty = false;
		return true;
	}

	/** Uploads the expanded fan to the scratch index buffer and draws it as a triangle list. */
	private void issueExpandedFan(int instanceCount, int baseVertex) {
		int count = this.fanExpander.count();
		if (count == 0) {
			return;
		}

		long handle = this.drawContext.scratch().stageIndices(this.fanExpander.indices(), count);
		if (handle == 0L) {
			return; // reported by the scratch
		}

		this.dxBridge.bindIndexBuffer(handle, true, 0);
		// The scratch buffer displaced the pass's real index buffer.
		this.indexBufferDirty = true;
		this.dxBridge.drawIndexed(count, instanceCount, 0, baseVertex);
	}

	private void report(String key, String message) {
		if (this.drawContext != null) {
			this.drawContext.diagnostics().reportOnce(key, message);
		}
	}

	private record BoundTexture(GpuTextureView textureView, GpuSampler sampler) {
	}

	/** Answers the binder's by-name lookups from the state Blaze3D pushed into this pass. */
	private final class PassBindingSource implements DxResourceBinder.Source {
		@Override
		public ByteBuffer uniformBytes(String name) {
			GpuBufferSlice slice = DxRenderPassBackend.this.uniformSlices.get(name);
			if (slice != null) {
				return ((DxBuffer) slice.buffer()).sliceStorage(slice.offset(), slice.length());
			}

			GpuBuffer whole = DxRenderPassBackend.this.uniformBuffers.get(name);
			return whole == null ? null : ((DxBuffer) whole).logicalStorage();
		}

		@Override
		public long textureViewHandle(String name) {
			BoundTexture bound = DxRenderPassBackend.this.boundTextures.get(name);
			if (bound != null && bound.textureView() instanceof DxTextureView view && !view.isClosed() && view.hasNativeViewHandle()) {
				return view.nativeViewHandle();
			}
			return 0L;
		}

		@Override
		public String textureViewFailureReason(String name) {
			BoundTexture bound = DxRenderPassBackend.this.boundTextures.get(name);
			if (bound == null) {
				return "the render pass has no texture binding for that name";
			}
			if (!(bound.textureView() instanceof DxTextureView view)) {
				return "the bound texture view is not a DirectExp texture view";
			}
			if (view.isClosed()) {
				return "the bound texture view is closed";
			}
			if (view.texture() instanceof DxTexture texture && !texture.hasNativeTextureHandle()) {
				return "the bound view's texture has no native texture handle";
			}
			return "the bound texture view has no native SRV";
		}

		@Override
		public String textureViewDescription(String name) {
			BoundTexture bound = DxRenderPassBackend.this.boundTextures.get(name);
			if (bound == null) {
				return "no bound texture";
			}
			if (!(bound.textureView() instanceof DxTextureView view)) {
				return "non-DirectExp texture view (" + bound.textureView().texture().getClass().getSimpleName() + ")";
			}
			if (!(view.texture() instanceof DxTexture texture)) {
				return "non-DirectExp texture (" + view.texture().getClass().getSimpleName() + ")";
			}
			return texture.debugDescription();
		}

		@Override
		public long samplerHandle(String name) {
			BoundTexture bound = DxRenderPassBackend.this.boundTextures.get(name);
			if (bound != null && bound.sampler() instanceof DxSampler sampler && sampler.hasNativeSamplerHandle()) {
				return sampler.nativeSamplerHandle();
			}
			return 0L;
		}
	}
}
