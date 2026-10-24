package dev.cubcraftv.directexp.client.backend;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import dev.cubcraftv.directexp.bridge.DxBridge;
import dev.cubcraftv.directexp.bridge.DxTexelFormat;
import dev.cubcraftv.directexp.client.shader.DxResourceLayout;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The backend form of one Renderpearl pipeline: the native D3D11 pipeline
 * objects plus the resource layout the draw path needs to bind uniforms,
 * textures and samplers into the registers the HLSL was compiled against.
 *
 * <p>26.3 hands the backend a {@code CreateInfo} (already compiled to SPIR-V,
 * with uniform bindings assigned) and expects {@code null} from
 * {@code finishCompile} when it cannot be built, so unlike earlier versions
 * there is no "invalid pipeline" object: failures are logged by
 * {@link DxPipelineCompiler} and never reach the draw path.
 */
final class DxRenderPipeline implements BackendRenderPipeline {
	private final String name;
	private final DxBridge dxBridge;
	private final long nativePipelineHandle;
	private final DxResourceLayout layout;
	private final PrimitiveTopology topology;
	private final List<BindGroupLayout.UniformDescription> uniforms;
	private final Map<String, DxTexelFormat> texelBufferFormats;
	private boolean closed;

	DxRenderPipeline(
		String name,
		DxBridge dxBridge,
		long nativePipelineHandle,
		DxResourceLayout layout,
		PrimitiveTopology topology,
		List<BindGroupLayout.UniformDescription> uniforms
	) {
		if (nativePipelineHandle == 0L) {
			throw new IllegalArgumentException("A pipeline needs a non-zero native handle.");
		}
		this.name = Objects.requireNonNull(name, "name");
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.nativePipelineHandle = nativePipelineHandle;
		this.layout = Objects.requireNonNull(layout, "layout");
		this.topology = Objects.requireNonNull(topology, "topology");
		this.uniforms = List.copyOf(uniforms);
		this.texelBufferFormats = texelBufferFormatsOf(this.uniforms);
	}

	private static Map<String, DxTexelFormat> texelBufferFormatsOf(List<BindGroupLayout.UniformDescription> uniforms) {
		Map<String, DxTexelFormat> formats = new HashMap<>();
		for (BindGroupLayout.UniformDescription uniform : uniforms) {
			if (uniform.type() != UniformType.TEXEL_BUFFER || uniform.gpuFormat() == null) {
				continue;
			}
			DxTexelFormat format = DxTexelFormat.fromGpuFormatName(uniform.gpuFormat().name());
			if (format != null) {
				formats.put(uniform.name(), format);
			}
		}
		return Map.copyOf(formats);
	}

	String name() {
		return this.name;
	}

	long nativePipelineHandle() {
		return this.nativePipelineHandle;
	}

	DxResourceLayout layout() {
		return this.layout;
	}

	PrimitiveTopology topology() {
		return this.topology;
	}

	/** Uniform names in binding order: the index {@code RenderPassBackend.setUniform} is called with. */
	String uniformName(int index) {
		return index >= 0 && index < this.uniforms.size() ? this.uniforms.get(index).name() : null;
	}

	Map<String, DxTexelFormat> texelBufferFormats() {
		return this.texelBufferFormats;
	}

	@Override
	public boolean isClosed() {
		return this.closed;
	}

	@Override
	public void close() {
		if (this.closed) {
			return;
		}

		this.closed = true;
		this.dxBridge.releasePipeline(this.nativePipelineHandle);
	}
}
