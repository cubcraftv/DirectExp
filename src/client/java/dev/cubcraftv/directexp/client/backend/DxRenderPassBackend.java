package dev.cubcraftv.directexp.client.backend;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Milestone 4 ("Shader and Pipeline Core") owns the real implementation of
 * this class -- actually compiling pipeline state and issuing D3D11 draw
 * calls through the native bridge. Until then, this class only tracks the
 * render-pass state Blaze3D pushes into it (pipeline, bound
 * textures/samplers, uniform slices, vertex/index buffers, scissor) so the
 * class compiles against the real RenderPassBackend interface and so a
 * future implementation has a concrete place to plug native draw calls
 * into, without callers crashing on a missing method. draw()/drawIndexed()/
 * drawMultipleIndexed() intentionally do not throw: at this scaffold stage
 * DxBackendBootstrapContext already fails earlier (DxBridge.probeSurface is
 * still a default-throw stub), so this class is never reached in practice
 * yet, but it should not become a second point of failure once the surface
 * path is implemented before the draw path is.
 */
final class DxRenderPassBackend implements RenderPassBackend {
	private final DxTexture colorTarget;
	private final int colorTargetMipLevel;
	private final DxCommandEncoderBackend commandEncoderBackend;
	private final Map<String, BoundTexture> boundTextures = new HashMap<>();
	private final Map<String, GpuBuffer> uniformBuffers = new HashMap<>();
	private final Map<String, GpuBufferSlice> uniformSlices = new HashMap<>();
	private final Map<Integer, DxBuffer> vertexBuffers = new HashMap<>();

	private RenderPipeline pipeline;
	private DxBuffer indexBuffer;
	private VertexFormat.IndexType indexType;
	private boolean scissorEnabled;
	private int scissorX;
	private int scissorY;
	private int scissorWidth;
	private int scissorHeight;

	DxRenderPassBackend(GpuTextureView colorTextureView) {
		this(null, colorTextureView);
	}

	DxRenderPassBackend(DxCommandEncoderBackend commandEncoderBackend, GpuTextureView colorTextureView) {
		if (colorTextureView == null || colorTextureView.isClosed()) {
			throw new IllegalArgumentException("DirectX render pass requires a live color texture view.");
		}

		if (!(colorTextureView.texture() instanceof DxTexture dxTexture)) {
			throw new IllegalArgumentException("DirectX render pass requires a DirectX texture view.");
		}

		if (dxTexture.getFormat() != GpuFormat.RGBA8_UNORM) {
			throw new IllegalArgumentException("DirectX render pass currently only supports RGBA8_UNORM color targets.");
		}

		this.commandEncoderBackend = commandEncoderBackend;
		this.colorTarget = dxTexture;
		this.colorTargetMipLevel = colorTextureView.baseMipLevel();
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
		this.boundTextures.put(name, new BoundTexture(textureView, sampler));
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
	}

	@Override
	public void disableScissor() {
		this.scissorEnabled = false;
	}

	@Override
	public void setVertexBuffer(int slot, GpuBuffer gpuBuffer) {
		this.vertexBuffers.put(slot, (DxBuffer) gpuBuffer);
	}

	@Override
	public void setIndexBuffer(GpuBuffer gpuBuffer, VertexFormat.IndexType indexType) {
		this.indexBuffer = (DxBuffer) gpuBuffer;
		this.indexType = indexType;
	}

	@Override
	public void drawIndexed(int baseVertex, int firstIndex, int indexCount, int instanceCount) {
		// TODO(Milestone 4): compile `this.pipeline` to D3D11 shader/PSO
		// state and issue DrawIndexedInstanced through the native bridge.
		// Currently a no-op: state above is tracked but nothing is
		// submitted to the GPU yet.
	}

	@Override
	public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws, GpuBuffer gpuBuffer, VertexFormat.IndexType indexType, Collection<String> dynamicUniforms, T userContext) {
		// TODO(Milestone 4): same as drawIndexed, batched. No-op for now.
	}

	@Override
	public void draw(int firstVertex, int vertexCount) {
		// TODO(Milestone 4): issue Draw through the native bridge. No-op for now.
	}

	@Override
	public void writeTimestamp(GpuQueryPool gpuQueryPool, int index) {
	}

	DxTexture colorTarget() {
		return this.colorTarget;
	}

	int colorTargetMipLevel() {
		return this.colorTargetMipLevel;
	}

	private record BoundTexture(GpuTextureView textureView, GpuSampler sampler) {
	}
}
