package dev.cubcraftv.directexp.bridge;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * Java-side surface for calling into the native D3D11/DXGI bridge.
 *
 * Methods with native implementations are overridden by
 * {@link NativeDxBridge}; default implementations fail explicitly when a
 * bridge does not support that operation.
 */
public interface DxBridge {
	DxBridgeProbe probe();

	default DxBridgeProbe probeSurface(long win32WindowHandle) {
		throw new UnsupportedOperationException("DirectX surface probe is not implemented by this bridge yet.");
	}

	default DxHostSurfaceBootstrap bootstrapSurface(long win32WindowHandle) {
		throw new UnsupportedOperationException("DirectX surface bootstrap is not implemented by this bridge yet.");
	}

	default void configureSurface(long nativeSurfaceHandle, int width, int height, boolean vsync) {
		throw new UnsupportedOperationException("DirectX surface configure is not implemented by this bridge yet.");
	}

	default void acquireSurface(long nativeSurfaceHandle) {
		throw new UnsupportedOperationException("DirectX surface acquire is not implemented by this bridge yet.");
	}

	default long createCommandContext() {
		throw new UnsupportedOperationException("DirectX command context creation is not implemented by this bridge yet.");
	}

	default void submitCommandContext(long nativeCommandContextHandle) {
		throw new UnsupportedOperationException("DirectX command context submit is not implemented by this bridge yet.");
	}

	default void releaseCommandContext(long nativeCommandContextHandle) {
	}

	/** CPU-upload fallback for presenting an RGBA8 texture without a native GPU handle. */
	default void blitSurfaceRgba8(long nativeSurfaceHandle, ByteBuffer rgbaPixels, int width, int height) {
		throw new UnsupportedOperationException("DirectX surface blit is not implemented by this bridge yet.");
	}

	default void uploadTextureRgba8(long nativeTextureHandle, int mipLevel, int layer, ByteBuffer rgbaPixels, int width, int height) {
		throw new UnsupportedOperationException("DirectX texture upload is not implemented by this bridge yet.");
	}

	/**
	 * Uploads only the {@code (x, y, width, height)} rectangle of one mip level/layer.
	 * {@code rgbaPixels} is the whole CPU mirror of that mip level (row pitch {@code mipWidth * 4}).
	 * Unlike {@link #uploadTextureRgba8} this leaves every texel outside the rectangle untouched on the GPU,
	 * which matters for textures that are also render targets (their CPU mirror is stale).
	 */
	default void uploadTextureRegionRgba8(long nativeTextureHandle, int mipLevel, int layer, ByteBuffer rgbaPixels, int mipWidth, int mipHeight, int x, int y, int width, int height) {
		throw new UnsupportedOperationException("DirectX texture region upload is not implemented by this bridge yet.");
	}

	default void releaseTexture(long nativeTextureHandle) {
	}

	default void blitSurfaceTexture(long nativeCommandContextHandle, long nativeSurfaceHandle, long nativeTextureHandle) {
		throw new UnsupportedOperationException("DirectX surface texture blit is not implemented by this bridge yet.");
	}

	// ---- Milestone 4.6 prerequisite scope: GPU buffers, samplers, texture views ----
	//
	// Bind-purpose bits for createBuffer. Deliberately D3D-agnostic and
	// Blaze3D-agnostic: the client side (DxBufferBinding) translates
	// GpuBuffer.Usage into this mask, and the native side translates this
	// mask into D3D11_BIND_* -- neither side needs the other's constants.
	// BUFFER_BIND_SHADER_RESOURCE covers texel-buffer SRVs and other
	// buffer-backed shader resources; they are a real D3D11 bind purpose even
	// though the Java-side layout model has not yet introduced a dedicated
	// buffer-view wrapper.
	int BUFFER_BIND_VERTEX = 1;
	int BUFFER_BIND_INDEX = 2;
	int BUFFER_BIND_CONSTANT = 4;
	int BUFFER_BIND_SHADER_RESOURCE = 8;

	// Address-mode codes for createSampler (Blaze3D's AddressMode has exactly
	// REPEAT and CLAMP_TO_EDGE).
	int SAMPLER_ADDRESS_WRAP = 0;
	int SAMPLER_ADDRESS_CLAMP = 1;

	/**
	 * Creates a GPU buffer (an {@code ID3D11Buffer}, {@code D3D11_USAGE_DEFAULT})
	 * usable for the given bind purposes. Its contents are undefined until the
	 * first {@link #updateBuffer}.
	 *
	 * @param bindMask  non-empty combination of {@code BUFFER_BIND_*} bits
	 * @param byteWidth logical size in bytes; the native side rounds a
	 *                  constant buffer up to a multiple of 16 itself
	 * @return an opaque non-zero handle; release with {@link #releaseBuffer}
	 */
	default long createBuffer(int bindMask, int byteWidth) {
		throw new UnsupportedOperationException("DirectX buffer creation is not implemented by this bridge yet.");
	}

	/**
	 * Copies {@code [offset, offset + length)} of {@code mirror} into the GPU
	 * buffer at the same offsets. {@code mirror} must be a direct
	 * {@link ByteBuffer} holding the buffer's full CPU-side copy (same layout
	 * as the GPU buffer, at least as large as the buffer's byte width); its
	 * position/limit are ignored -- offsets are absolute. For a constant
	 * buffer the whole buffer is re-sent regardless of {@code offset}/
	 * {@code length}, because D3D11 cannot partially update one.
	 */
	default void updateBuffer(long nativeBufferHandle, ByteBuffer mirror, int offset, int length) {
		throw new UnsupportedOperationException("DirectX buffer upload is not implemented by this bridge yet.");
	}

	default void releaseBuffer(long nativeBufferHandle) {
	}

	/**
	 * Creates an {@code ID3D11SamplerState}.
	 *
	 * @param addressU      {@code SAMPLER_ADDRESS_*}
	 * @param addressV      {@code SAMPLER_ADDRESS_*}
	 * @param minLinear     linear (true) or point (false) minification
	 * @param magLinear     linear (true) or point (false) magnification
	 * @param mipLinear     linear (true) or point (false) mip selection
	 * @param maxAnisotropy values above 1 select anisotropic filtering
	 *                      (which ignores the three linear flags); clamped to 1..16
	 * @param maxLod        upper LOD clamp
	 */
	default long createSampler(
		int addressU, int addressV, boolean minLinear, boolean magLinear, boolean mipLinear, int maxAnisotropy, float maxLod
	) {
		throw new UnsupportedOperationException("DirectX sampler creation is not implemented by this bridge yet.");
	}

	default void releaseSampler(long nativeSamplerHandle) {
	}

	/**
	 * Creates an {@code ID3D11ShaderResourceView} over
	 * {@code [baseMipLevel, baseMipLevel + mipLevels)} of a texture created by
	 * {@link #createTexture}. The view keeps its own reference to the
	 * texture, so it may outlive {@link #releaseTexture}.
	 */
	default long createTextureView(long nativeTextureHandle, int baseMipLevel, int mipLevels) {
		throw new UnsupportedOperationException("DirectX texture view creation is not implemented by this bridge yet.");
	}

	default void releaseTextureView(long nativeTextureViewHandle) {
	}

	// ---- Milestone 4.6 commit 2: render/depth-capable textures, pipeline objects ----

	// Texture formats createTexture supports natively. Codes, not DXGI numbers:
	// depth formats need a typeless resource format and different view formats,
	// which is the native side's business.
	int TEXTURE_FORMAT_RGBA8_UNORM = 1;
	int TEXTURE_FORMAT_D32_FLOAT = 2;
	int TEXTURE_FORMAT_D24_UNORM_S8_UINT = 3;
	int TEXTURE_FORMAT_D32_FLOAT_S8_UINT = 4;
	int TEXTURE_FORMAT_D16_UNORM = 5;

	// What a texture will be used for; decides the D3D11 bind flags and which
	// views createTextureView can produce (SRV / RTV / DSV).
	int TEXTURE_BIND_SHADER_RESOURCE = 1;
	int TEXTURE_BIND_RENDER_TARGET = 2;
	int TEXTURE_BIND_DEPTH_STENCIL = 4;

	/**
	 * Creates a 2D texture of a {@code TEXTURE_FORMAT_*} for the given
	 * {@code TEXTURE_BIND_*} purposes. {@code RENDER_TARGET} requires a color
	 * format and {@code DEPTH_STENCIL} a depth format. Contents are undefined
	 * until written or cleared. Release with {@link #releaseTexture}.
	 * {@link #uploadTextureRgba8} still works on any RGBA8 texture.
	 */
	default long createTexture(int format, int width, int height, int arrayLayers, int mipLevels, int bindMask, boolean cubemap) {
		throw new UnsupportedOperationException("DirectX render/depth texture creation is not implemented by this bridge yet.");
	}

	default long createTexture(int format, int width, int height, int arrayLayers, int mipLevels, int bindMask, boolean cubemap, String debugLabel) {
		return createTexture(format, width, height, arrayLayers, mipLevels, bindMask, cubemap);
	}

	/**
	 * Builds the immutable D3D11 objects for one render pipeline (vertex and
	 * pixel shader, input layout, blend / depth-stencil / rasterizer state) from
	 * the two compiled bytecode blobs and the state description. Throws
	 * {@link IllegalStateException} on any failure (bad bytecode, or a vertex
	 * format the vertex shader's input signature doesn't match). Nothing is
	 * bound; release with {@link #releasePipeline}.
	 */
	default long createPipeline(byte[] vertexBytecode, byte[] pixelBytecode, DxPipelineStateDescriptor state) {
		throw new UnsupportedOperationException("DirectX pipeline creation is not implemented by this bridge yet.");
	}

	/**
	 * Slot (b13, the last of D3D11's 14 constant buffer slots) the generated
	 * point-sprite geometry shader reads its viewport size from. Engine
	 * resource slots are assigned from 0 upward, so this stays out of their
	 * way. The draw path binds the buffer at this slot with
	 * {@code GSSetConstantBuffers} whenever the pipeline has a geometry stage.
	 */
	int GEOMETRY_VIEWPORT_CBUFFER_SLOT = 13;

	/**
	 * Same as {@link #createPipeline(byte[], byte[], DxPipelineStateDescriptor)}
	 * plus an optional geometry shader ({@code null} for none). A pipeline
	 * with a geometry shader also owns the small constant buffer that feeds
	 * it the viewport size, see {@link #setPipelineViewport}.
	 */
	default long createPipeline(byte[] vertexBytecode, byte[] pixelBytecode, byte[] geometryBytecode, DxPipelineStateDescriptor state) {
		if (geometryBytecode != null) {
			throw new UnsupportedOperationException("DirectX geometry-shader pipelines are not implemented by this bridge yet.");
		}
		return createPipeline(vertexBytecode, pixelBytecode, state);
	}

	/**
	 * Updates the viewport size (in pixels) a pipeline's geometry shader uses
	 * to turn a point size in pixels into a clip-space offset. Only meaningful
	 * for pipelines created with a geometry shader; a no-op for the rest.
	 */
	default void setPipelineViewport(long nativePipelineHandle, int width, int height) {
		throw new UnsupportedOperationException("DirectX pipeline viewport update is not implemented by this bridge yet.");
	}

	default void releasePipeline(long nativePipelineHandle) {
	}

	// ---- Milestone 4.6 commit 4: draw path (binding, clears, draws) ----
	//
	// All of these act on the single active immediate context, exactly like the
	// resource methods above. Binding state is sticky (D3D11 semantics): it stays
	// until replaced, so the client only re-sends what changed. A handle of 0
	// where a view/buffer/sampler is expected means "unbind that slot".

	/** Shader-stage bits for the {@code bind*} calls below. */
	int STAGE_VERTEX = 1;
	int STAGE_FRAGMENT = 2;

	/**
	 * Binds the render target and depth-stencil views carried by two
	 * texture-view handles (see {@link #createTextureView}) and sets the
	 * viewport to {@code (0, 0, width, height)}, depth 0..1. Either handle may
	 * be 0 (no color target / no depth target). Throws {@link IllegalStateException}
	 * if a non-zero handle carries no render-target / depth-stencil view.
	 */
	default void setRenderTargets(long colorViewHandle, long depthViewHandle, int width, int height) {
		throw new UnsupportedOperationException("DirectX render target binding is not implemented by this bridge yet.");
	}

	/** {@code ClearRenderTargetView} on the render-target view of a texture-view handle. Components are 0..1. */
	default void clearRenderTarget(long colorViewHandle, float red, float green, float blue, float alpha) {
		throw new UnsupportedOperationException("DirectX render target clear is not implemented by this bridge yet.");
	}

	/** {@code ClearDepthStencilView} (depth and stencil, stencil to 0) on the depth-stencil view of a texture-view handle. */
	default void clearDepthStencil(long depthViewHandle, float depth) {
		throw new UnsupportedOperationException("DirectX depth clear is not implemented by this bridge yet.");
	}

	/**
	 * Clears mip 0 of a depth texture that has no view at hand (Blaze3D's
	 * texture-level {@code clearDepthTexture}); the native side uses a
	 * transient depth-stencil view.
	 */
	default void clearDepthTexture(long nativeTextureHandle, float depth) {
		throw new UnsupportedOperationException("DirectX depth texture clear is not implemented by this bridge yet.");
	}

	/** Clears depth to {@code depth} inside {@code [x,x+width) x [y,y+height)} of a native depth texture (mip 0); the rest is untouched. */
	default void clearDepthRegion(long nativeTextureHandle, int x, int y, int width, int height, float depth) {
		throw new UnsupportedOperationException("DirectX rectangle depth clear is not implemented by this bridge yet.");
	}

	/**
	 * Sets the scissor rectangle in D3D pixel coordinates (origin top-left).
	 * Every pipeline's rasterizer state has scissor enabled, so "scissor off"
	 * is expressed by passing the full target rectangle.
	 */
	default void setScissor(int x, int y, int width, int height) {
		throw new UnsupportedOperationException("DirectX scissor is not implemented by this bridge yet.");
	}

	/**
	 * Binds every object of a pipeline: shaders (geometry stage cleared when the
	 * pipeline has none), input layout, topology, and blend / depth-stencil /
	 * rasterizer state. For a pipeline with a geometry stage it also binds the
	 * viewport constants at {@link #GEOMETRY_VIEWPORT_CBUFFER_SLOT} and refreshes
	 * them when the viewport from {@link #setRenderTargets} changed, so the client
	 * never calls {@link #setPipelineViewport} itself. The pipeline's vertex
	 * stride is what {@link #bindVertexBuffer} uses.
	 */
	default void bindPipeline(long nativePipelineHandle) {
		throw new UnsupportedOperationException("DirectX pipeline binding is not implemented by this bridge yet.");
	}

	/** {@code IASetVertexBuffers} for one slot, stride taken from the bound pipeline. Needs {@link #bindPipeline} first. */
	default void bindVertexBuffer(int slot, long nativeBufferHandle, int byteOffset) {
		throw new UnsupportedOperationException("DirectX vertex buffer binding is not implemented by this bridge yet.");
	}

	/** {@code IASetIndexBuffer}; {@code wideIndices} selects R32_UINT over R16_UINT. */
	default void bindIndexBuffer(long nativeBufferHandle, boolean wideIndices, int byteOffset) {
		throw new UnsupportedOperationException("DirectX index buffer binding is not implemented by this bridge yet.");
	}

	/** {@code VSSetConstantBuffers}/{@code PSSetConstantBuffers} for the stages in {@code stageMask}. */
	default void bindConstantBuffer(int slot, long nativeBufferHandle, int stageMask) {
		throw new UnsupportedOperationException("DirectX constant buffer binding is not implemented by this bridge yet.");
	}

	/** Binds the shader-resource view of a texture-view handle to {@code t<slot>} of the given stages. */
	default void bindShaderResource(int slot, long nativeTextureViewHandle, int stageMask) {
		throw new UnsupportedOperationException("DirectX shader resource binding is not implemented by this bridge yet.");
	}

	/** Binds a sampler state to {@code s<slot>} of the given stages. */
	default void bindSampler(int slot, long nativeSamplerHandle, int stageMask) {
		throw new UnsupportedOperationException("DirectX sampler binding is not implemented by this bridge yet.");
	}

	/** {@code Draw(vertexCount, firstVertex)}. */
	default void draw(int vertexCount, int firstVertex) {
		throw new UnsupportedOperationException("DirectX draw is not implemented by this bridge yet.");
	}

	/** {@code DrawIndexedInstanced(indexCount, instanceCount, firstIndex, baseVertex, 0)}. */
	default void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex) {
		throw new UnsupportedOperationException("DirectX indexed draw is not implemented by this bridge yet.");
	}

	/**
	 * Issues interleaved {@code firstIndex, indexCount, baseVertex} indexed draws.
	 * Native bridges should override this to keep the batch within one JNI call.
	 */
	default void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int drawCount) {
		IntBuffer parameters = drawParameters.duplicate();
		for (int draw = 0; draw < drawCount; draw++) {
			int firstIndex = parameters.get();
			int indexCount = parameters.get();
			int baseVertex = parameters.get();
			drawIndexed(indexCount, instanceCount, firstIndex, baseVertex);
		}
	}

	default void presentSurface(long nativeSurfaceHandle) {
		throw new UnsupportedOperationException("DirectX surface present is not implemented by this bridge yet.");
	}

	default void releaseSurface(long nativeSurfaceHandle) {
	}

	/**
	 * Milestone 4.3 (IR Stage): compiles preprocessed GLSL source (as
	 * produced by Milestone 4.2's {@code DxShaderSourceStage}) to SPIR-V
	 * via {@code shaderc}, real and JNI-exposed rather than the 4.1 smoke
	 * test's hardcoded string.
	 *
	 * <p>Unlike most of this interface's other native-backed methods, a
	 * {@code shaderc} compile failure is not an exception here -- it's a
	 * normal, expected result ({@link DxIrCompilationResult#isCompileError()}),
	 * since real shader source can legitimately fail to compile (a syntax
	 * error, an unsupported construct). Only a bridge-level problem
	 * (library not loaded, native entrypoint missing) is reported as a
	 * different, non-{@code READY} status; see {@link DxIrCompilationStatus}.
	 *
	 * @param shaderName debug name used only for {@code shaderc}'s own
	 *                    error messages -- does not need to be a real
	 *                    Mojang {@code Identifier}
	 * @param glslSource  preprocessed GLSL source text (defines already
	 *                    injected)
	 * @param stageKind   which shader stage this source is for
	 */
	default DxIrCompilationResult compileGlslToSpirv(String shaderName, String glslSource, DxShaderStageKind stageKind) {
		throw new UnsupportedOperationException("DirectX GLSL -> SPIR-V compilation is not implemented by this bridge yet.");
	}

	/**
	 * Milestone 4.4 (Reflection and Resource Layout Model): reflects one
	 * shader stage's compiled SPIR-V (as produced by
	 * {@link #compileGlslToSpirv}) via SPIRV-Cross, returning the
	 * constant buffers, sampled textures, samplers, and structured
	 * buffers it declares.
	 *
	 * <p>Deliberately stage-agnostic (unlike {@link #compileGlslToSpirv},
	 * there is no {@code DxShaderStageKind} parameter): resource
	 * classification depends only on the SPIR-V itself, not on which
	 * stage it came from. The caller ({@code DxReflectionStage} in
	 * {@code client.shader}) is what keeps vertex/fragment results
	 * distinct.
	 *
	 * <p>Like {@link #compileGlslToSpirv}, a SPIRV-Cross reflection
	 * failure is not an exception here -- it is a normal, expected
	 * result ({@link DxSpirvReflectionResult#isReflectionError()}). Only
	 * a bridge-level problem (library not loaded, native entrypoint
	 * missing) is reported as a different, non-{@code READY} status; see
	 * {@link DxSpirvReflectionStatus}.
	 *
	 * @param spirv raw SPIR-V words, as bytes, from a
	 *              {@link DxIrCompilationResult#spirv()} that was
	 *              {@link DxIrCompilationResult#isReady()}
	 */
	default DxSpirvReflectionResult reflectSpirv(byte[] spirv) {
		throw new UnsupportedOperationException("DirectX SPIR-V reflection is not implemented by this bridge yet.");
	}

	/**
	 * Milestone 4.5 (HLSL Emission and D3D Compilation): translates one
	 * shader stage's compiled SPIR-V to HLSL via SPIRV-Cross, targeting
	 * Shader Model 5.0, then compiles that HLSL to D3D11 shader bytecode
	 * via {@code D3DCompile}. Each resource in {@code resourceSlots} is
	 * forced to the exact register Milestone 4.4's
	 * {@code DxResourceLayoutBuilder} assigned it, rather than letting
	 * SPIRV-Cross choose registers on its own -- see
	 * {@code directexp_shader_hlsl.cpp}'s {@code ApplyResourceBindings}
	 * for exactly how.
	 *
	 * <p>Unlike {@link #reflectSpirv}, this call needs
	 * {@code stageKind}: the target shader profile ({@code vs_5_0} vs
	 * {@code ps_5_0}) and the resource binding's own SPIR-V execution
	 * model depend on which stage this is.
	 *
	 * <p>A SPIRV-Cross emission failure or a {@code D3DCompile} failure
	 * are each a normal, expected result here, not an exception -- see
	 * {@link DxHlslCompilationResult#isEmissionError()}/
	 * {@link DxHlslCompilationResult#isCompileError()}. Only a
	 * bridge-level problem (library not loaded, native entrypoint
	 * missing) is reported as a different, non-{@code READY} status; see
	 * {@link DxHlslCompilationStatus}.
	 *
	 * @param shaderName    used only for {@code D3DCompile}'s own
	 *                      {@code pSourceName} diagnostic parameter, the
	 *                      same role {@link #compileGlslToSpirv}'s
	 *                      {@code shaderName} plays for {@code shaderc}
	 * @param spirv         raw SPIR-V words, as bytes, from a
	 *                      {@link DxIrCompilationResult#spirv()} that was
	 *                      {@link DxIrCompilationResult#isReady()}
	 * @param stageKind     which shader stage this SPIR-V is for
	 * @param resourceSlots this stage's resources and the concrete
	 *                      register each should be forced to, from
	 *                      Milestone 4.4's resource layout model
	 */
	default DxHlslCompilationResult emitAndCompileHlsl(
		String shaderName,
		byte[] spirv,
		DxShaderStageKind stageKind,
		List<DxResourceSlotAssignment> resourceSlots
	) {
		throw new UnsupportedOperationException("DirectX HLSL emission/compilation is not implemented by this bridge yet.");
	}

	/**
	 * Compiles HLSL text (not SPIR-V) to D3D11 bytecode via {@code D3DCompile},
	 * for shaders DirectExp generates or rewrites itself, including the
	 * point-sprite geometry shader and signature-aligned fragment shaders.
	 * {@code stageKind} picks the profile
	 * ({@code vs_/ps_/gs_} 4_1 or 5_0 by negotiated feature level). Result
	 * shape and failure reporting match {@link #emitAndCompileHlsl}; there is
	 * no emission step, so an emission error is never returned.
	 */
	default DxHlslCompilationResult compileHlsl(String shaderName, String hlsl, DxShaderStageKind stageKind) {
		throw new UnsupportedOperationException("DirectX HLSL compilation is not implemented by this bridge yet.");
	}
}
