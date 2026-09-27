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
