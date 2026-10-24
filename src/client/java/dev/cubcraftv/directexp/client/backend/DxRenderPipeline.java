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
 * with uniform bindings assigned). There are two valid outcomes a
 * {@code finishCompile} can report: a fully built pipeline (non-zero native
 * handle), and a <em>supported-object-but-unsupported-state</em> pipeline
 * (handle {@code 0}, {@link #unsupportedReason} set) for state D3D11 cannot
 * express yet. The second is required because 26.3 treats {@code null} from
 * {@code finishCompile} as fatal for its <em>required</em> pipelines (it
 * aborts the whole resource reload and drops shader packs), while an
 * unsupported-state pipeline can be reported and safely skipped at draw
 * time instead -- see {@link DxPipelineStateMapping#describe}'s contract.
 * Failures before the state step (reflection, HLSL, D3DCompile) remain hard
 * failures logged by {@link DxPipelineCompiler}, never reaching the draw path.
 */
final class DxRenderPipeline implements BackendRenderPipeline {
	private final String name;
	private final DxBridge dxBridge;
	private final long nativePipelineHandle;
	/** Non-null for a valid-but-unsupported pipeline; those have no native objects at all. */
	private final String unsupportedReason;
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
		this(name, dxBridge, nativePipelineHandle, null, layout, topology, uniforms);
	}

	private DxRenderPipeline(
		String name,
		DxBridge dxBridge,
		long nativePipelineHandle,
		String unsupportedReason,
		DxResourceLayout layout,
		PrimitiveTopology topology,
		List<BindGroupLayout.UniformDescription> uniforms
	) {
		if (unsupportedReason == null && nativePipelineHandle == 0L) {
			throw new IllegalArgumentException("A pipeline needs a non-zero native handle.");
		}
		if (unsupportedReason != null && nativePipelineHandle != 0L) {
			throw new IllegalArgumentException("An unsupported pipeline has no native objects.");
		}
		this.name = Objects.requireNonNull(name, "name");
		this.dxBridge = Objects.requireNonNull(dxBridge, "dxBridge");
		this.nativePipelineHandle = nativePipelineHandle;
		this.unsupportedReason = unsupportedReason;
		this.layout = Objects.requireNonNull(layout, "layout");
		this.topology = Objects.requireNonNull(topology, "topology");
		this.uniforms = List.copyOf(uniforms);
		this.texelBufferFormats = texelBufferFormatsOf(this.uniforms);
	}

	/**
	 * A pipeline whose state the backend cannot express yet (the reason names
	 * the offending feature of {@link DxPipelineStateMapping#describe}). Valid
	 * as a {@link BackendRenderPipeline} so required pipelines survive the
	 * frontend's compilation contract, but it owns no native objects and
	 * every draw that reaches it is skipped.
	 */
	static DxRenderPipeline unsupported(
		String name,
		DxBridge dxBridge,
		String reason,
		DxResourceLayout layout,
		PrimitiveTopology topology,
		List<BindGroupLayout.UniformDescription> uniforms
	) {
		return new DxRenderPipeline(name, dxBridge, 0L, Objects.requireNonNull(reason, "reason"), layout, topology, uniforms);
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

	/** Non-null exactly for the valid-but-unsupported pipelines ({@code unsupported(...)}). */
	String unsupportedReason() {
		return this.unsupportedReason;
	}

	/** False when this pipeline's state is known-unsupported and draws must be skipped. */
	boolean isSupported() {
		return this.unsupportedReason == null;
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
		// Unsupported pipelines own nothing native; skip the release call.
		if (this.nativePipelineHandle != 0L) {
			this.dxBridge.releasePipeline(this.nativePipelineHandle);
		}
	}
}
