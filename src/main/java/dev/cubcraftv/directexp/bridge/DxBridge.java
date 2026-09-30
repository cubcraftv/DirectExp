package dev.cubcraftv.directexp.bridge;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Java-side surface for calling into the native D3D11/DXGI bridge.
 *
 * Scope note: as of Milestone 2's start, only {@link #probe()} has a real
 * native implementation. Every other method below documents the intended
 * contract (mirroring MetalExp's MetalBridge shape) but throws by default
 * until the corresponding native entrypoint and Java override exist. Do not
 * remove a default method just because it's unimplemented -- it's there so
 * the eventual real implementation has an agreed-upon signature to target,
 * and so callers written against this interface compile today.
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

	default void blitSurfaceRgba8(long nativeSurfaceHandle, ByteBuffer rgbaPixels, int width, int height) {
		throw new UnsupportedOperationException("DirectX surface blit is not implemented by this bridge yet.");
	}

	default long createTexture2D(int width, int height, int mipLevels) {
		throw new UnsupportedOperationException("DirectX texture creation is not implemented by this bridge yet.");
	}

	default void uploadTextureRgba8(long nativeTextureHandle, int mipLevel, ByteBuffer rgbaPixels, int width, int height) {
		throw new UnsupportedOperationException("DirectX texture upload is not implemented by this bridge yet.");
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
	int BUFFER_BIND_VERTEX = 1;
	int BUFFER_BIND_INDEX = 2;
	int BUFFER_BIND_CONSTANT = 4;

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
	 * {@link #createTexture2D}. The view keeps its own reference to the
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
	default long createTexture(int format, int width, int height, int mipLevels, int bindMask) {
		throw new UnsupportedOperationException("DirectX render/depth texture creation is not implemented by this bridge yet.");
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

	default void releasePipeline(long nativePipelineHandle) {
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
}
