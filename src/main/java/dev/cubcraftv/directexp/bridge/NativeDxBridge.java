package dev.cubcraftv.directexp.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class NativeDxBridge implements DxBridge {
	private static final NativeDxBridge INSTANCE = new NativeDxBridge();

	// D3D11's immediate-context model means there is no real object to
	// create here yet -- see directexp_bridge_surface.cpp's file header for
	// why createCommandContext/submitCommandContext/releaseCommandContext
	// stay Java-only no-ops for now. This counter just hands out distinct,
	// non-zero handles so callers can't mistake "no context" (0) for a live
	// one, without needing a native round-trip.
	private static final AtomicLong NEXT_COMMAND_CONTEXT_HANDLE = new AtomicLong(1);

	private NativeDxBridge() {
	}

	public static NativeDxBridge getInstance() {
		return INSTANCE;
	}

	@Override
	public DxBridgeProbe probe() {
		return runProbe(NativeDxBridge::probe0);
	}

	@Override
	public DxBridgeProbe probeSurface(long win32WindowHandle) {
		return runProbe(() -> probeSurface0(win32WindowHandle));
	}

	@Override
	public DxHostSurfaceBootstrap bootstrapSurface(long win32WindowHandle) {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			DxBridgeProbe unloaded = fromLoadResult(loadResult);
			return new DxHostSurfaceBootstrap(0L, 0, 0, 1.0, unloaded.detail(), unloaded.missingCapabilities(), false, false);
		}

		try {
			NativeDxBridgeSurfaceBootstrapResult nativeResult = bootstrapSurface0(win32WindowHandle);
			return fromNativeBootstrapResult(nativeResult);
		} catch (UnsatisfiedLinkError error) {
			return new DxHostSurfaceBootstrap(
				0L, 0, 0, 1.0,
				error.getMessage() == null ? "DirectX bridge native surface bootstrap entrypoint is missing." : error.getMessage(),
				List.of("native_bootstrap_surface_entrypoint"),
				true,
				false
			);
		} catch (RuntimeException error) {
			return new DxHostSurfaceBootstrap(
				0L, 0, 0, 1.0,
				error.getMessage() == null ? "DirectX bridge native surface bootstrap failed." : error.getMessage(),
				List.of("native_bootstrap_surface_runtime"),
				true,
				true
			);
		}
	}

	@Override
	public void configureSurface(long nativeSurfaceHandle, int width, int height, boolean vsync) {
		configureSurface0(nativeSurfaceHandle, width, height, vsync);
	}

	@Override
	public void acquireSurface(long nativeSurfaceHandle) {
		acquireSurface0(nativeSurfaceHandle);
	}

	@Override
	public long createCommandContext() {
		return NEXT_COMMAND_CONTEXT_HANDLE.getAndIncrement();
	}

	@Override
	public void submitCommandContext(long nativeCommandContextHandle) {
		// No native work to flush yet -- see the class-level comment on
		// NEXT_COMMAND_CONTEXT_HANDLE.
	}

	@Override
	public void releaseCommandContext(long nativeCommandContextHandle) {
	}

	@Override
	public void blitSurfaceRgba8(long nativeSurfaceHandle, ByteBuffer rgbaPixels, int width, int height) {
		blitSurfaceRgba80(nativeSurfaceHandle, rgbaPixels, width, height);
	}

	@Override
	public void uploadTextureRgba8(long nativeTextureHandle, int mipLevel, int layer, ByteBuffer rgbaPixels, int width, int height) {
		uploadTextureRgba8Native(nativeTextureHandle, mipLevel, layer, rgbaPixels, width, height);
	}

	@Override
	public void uploadTextureRegionRgba8(long nativeTextureHandle, int mipLevel, int layer, ByteBuffer rgbaPixels, int mipWidth, int mipHeight, int x, int y, int width, int height) {
		uploadTextureRegionRgba8Native(nativeTextureHandle, mipLevel, layer, rgbaPixels, mipWidth, mipHeight, x, y, width, height);
	}

	@Override
	public void releaseTexture(long nativeTextureHandle) {
		if (nativeTextureHandle == 0L) {
			return;
		}
		releaseTexture0(nativeTextureHandle);
	}

	@Override
	public long createBuffer(int bindMask, int byteWidth) {
		return createBufferNative(bindMask, byteWidth);
	}

	@Override
	public void updateBuffer(long nativeBufferHandle, ByteBuffer mirror, int offset, int length) {
		updateBufferNative(nativeBufferHandle, mirror, offset, length);
	}

	@Override
	public void releaseBuffer(long nativeBufferHandle) {
		if (nativeBufferHandle == 0L) {
			return;
		}
		releaseBuffer0(nativeBufferHandle);
	}

	@Override
	public void uploadBufferRange(long nativeBufferHandle, int destinationOffset, ByteBuffer source, int sourceOffset, int length) {
		uploadBufferRangeNative(nativeBufferHandle, destinationOffset, source, sourceOffset, length);
	}

	@Override
	public void readBuffer(long nativeBufferHandle, int sourceOffset, ByteBuffer destination, int destinationOffset, int length) {
		readBufferNative(nativeBufferHandle, sourceOffset, destination, destinationOffset, length);
	}

	@Override
	public void copyBuffer(long sourceBufferHandle, int sourceOffset, long destinationBufferHandle, int destinationOffset, int length) {
		copyBufferNative(sourceBufferHandle, sourceOffset, destinationBufferHandle, destinationOffset, length);
	}

	@Override
	public long createBufferShaderResourceView(long nativeBufferHandle, int texelFormatCode, int firstElement, int elementCount) {
		return createBufferShaderResourceViewNative(nativeBufferHandle, texelFormatCode, firstElement, elementCount);
	}

	@Override
	public void releaseBufferShaderResourceView(long nativeBufferViewHandle) {
		if (nativeBufferViewHandle == 0L) {
			return;
		}
		// A buffer SRV is carried in the same native wrapper as a texture view
		// (only its shader-resource slot is filled), so it releases the same way.
		releaseTextureView0(nativeBufferViewHandle);
	}

	@Override
	public long createSampler(
		int addressU, int addressV, boolean minLinear, boolean magLinear, boolean mipLinear, int maxAnisotropy, float maxLod
	) {
		return createSamplerNative(addressU, addressV, minLinear, magLinear, mipLinear, maxAnisotropy, maxLod);
	}

	@Override
	public void releaseSampler(long nativeSamplerHandle) {
		if (nativeSamplerHandle == 0L) {
			return;
		}
		releaseSampler0(nativeSamplerHandle);
	}

	@Override
	public long createTextureView(long nativeTextureHandle, int baseMipLevel, int mipLevels) {
		return createTextureViewNative(nativeTextureHandle, baseMipLevel, mipLevels);
	}

	@Override
	public void releaseTextureView(long nativeTextureViewHandle) {
		if (nativeTextureViewHandle == 0L) {
			return;
		}
		releaseTextureView0(nativeTextureViewHandle);
	}

	@Override
	public long createTexture(int format, int width, int height, int arrayLayers, int mipLevels, int bindMask, boolean cubemap) {
		return createTexture(format, width, height, arrayLayers, mipLevels, bindMask, cubemap, "DirectExp texture");
	}

	@Override
	public long createTexture(int format, int width, int height, int arrayLayers, int mipLevels, int bindMask, boolean cubemap, String debugLabel) {
		return createTextureNative(format, width, height, arrayLayers, mipLevels, bindMask, cubemap, debugLabel);
	}

	@Override
	public long createPipeline(byte[] vertexBytecode, byte[] pixelBytecode, DxPipelineStateDescriptor state) {
		return createPipeline(vertexBytecode, pixelBytecode, null, state);
	}

	@Override
	public long createPipeline(byte[] vertexBytecode, byte[] pixelBytecode, byte[] geometryBytecode, DxPipelineStateDescriptor state) {
		return createPipelineNative(
			vertexBytecode, pixelBytecode, geometryBytecode,
			state.attributeFormats(), state.attributeOffsets(), state.vertexStride(),
			state.stateArray(), state.floatStateArray()
		);
	}

	@Override
	public void setPipelineViewport(long nativePipelineHandle, int width, int height) {
		if (nativePipelineHandle == 0L) {
			return;
		}
		setPipelineViewport0(nativePipelineHandle, width, height);
	}

	@Override
	public void releasePipeline(long nativePipelineHandle) {
		if (nativePipelineHandle == 0L) {
			return;
		}
		releasePipeline0(nativePipelineHandle);
	}

	@Override
	public void setRenderTargets(long colorViewHandle, long depthViewHandle, int width, int height) {
		setRenderTargets0(colorViewHandle, depthViewHandle, width, height);
	}

	@Override
	public void clearRenderTarget(long colorViewHandle, float red, float green, float blue, float alpha) {
		clearRenderTarget0(colorViewHandle, red, green, blue, alpha);
	}

	@Override
	public void clearDepthStencil(long depthViewHandle, float depth) {
		clearDepthStencil0(depthViewHandle, depth);
	}

	@Override
	public void clearDepthTexture(long nativeTextureHandle, float depth) {
		clearDepthTexture0(nativeTextureHandle, depth);
	}

	@Override
	public void clearDepthRegion(long nativeTextureHandle, int x, int y, int width, int height, float depth) {
		clearDepthRegion0(nativeTextureHandle, x, y, width, height, depth);
	}

	@Override
	public void setScissor(int x, int y, int width, int height) {
		setScissor0(x, y, width, height);
	}

	@Override
	public void bindPipeline(long nativePipelineHandle) {
		bindPipeline0(nativePipelineHandle);
	}

	@Override
	public void bindVertexBuffer(int slot, long nativeBufferHandle, int byteOffset) {
		bindVertexBuffer0(slot, nativeBufferHandle, byteOffset);
	}

	@Override
	public void bindIndexBuffer(long nativeBufferHandle, boolean wideIndices, int byteOffset) {
		bindIndexBuffer0(nativeBufferHandle, wideIndices, byteOffset);
	}

	@Override
	public void bindConstantBuffer(int slot, long nativeBufferHandle, int stageMask) {
		bindConstantBuffer0(slot, nativeBufferHandle, stageMask);
	}

	@Override
	public void bindShaderResource(int slot, long nativeTextureViewHandle, int stageMask) {
		bindShaderResource0(slot, nativeTextureViewHandle, stageMask);
	}

	@Override
	public void bindSampler(int slot, long nativeSamplerHandle, int stageMask) {
		bindSampler0(slot, nativeSamplerHandle, stageMask);
	}

	@Override
	public void draw(int vertexCount, int firstVertex) {
		draw0(vertexCount, firstVertex);
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex) {
		drawIndexed0(indexCount, instanceCount, firstIndex, baseVertex);
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int drawCount) {
		if (drawCount < 0 || instanceCount < 0) {
			throw new IllegalArgumentException("multiDrawIndexed requires non-negative counts");
		}
		if (drawCount == 0) {
			return;
		}
		if (drawCount > Integer.MAX_VALUE / 3 || drawParameters.remaining() < drawCount * 3) {
			throw new IllegalArgumentException("multiDrawIndexed requires three integer parameters per draw");
		}

		IntBuffer parameters = drawParameters.duplicate();
		parameters.limit(parameters.position() + drawCount * 3);
		parameters = parameters.slice();
		if (!parameters.isDirect() || parameters.order() != ByteOrder.nativeOrder()) {
			if (drawCount > Integer.MAX_VALUE / (3 * Integer.BYTES)) {
				throw new IllegalArgumentException("multiDrawIndexed batch is too large for a direct buffer");
			}
			ByteBuffer packed = ByteBuffer.allocateDirect(drawCount * 3 * Integer.BYTES)
				.order(ByteOrder.nativeOrder());
			packed.asIntBuffer().put(parameters);
			parameters = packed.asIntBuffer();
		}
		multiDrawIndexed0(parameters, instanceCount, drawCount);
	}

	@Override
	public void blitSurfaceTexture(long nativeCommandContextHandle, long nativeSurfaceHandle, long nativeTextureHandle) {
		blitSurfaceTexture0(nativeCommandContextHandle, nativeSurfaceHandle, nativeTextureHandle);
	}

	@Override
	public void presentSurface(long nativeSurfaceHandle) {
		presentSurface0(nativeSurfaceHandle);
	}

	@Override
	public void releaseSurface(long nativeSurfaceHandle) {
		if (nativeSurfaceHandle == 0L) {
			return;
		}
		releaseSurface0(nativeSurfaceHandle);
	}

	@Override
	public DxIrCompilationResult compileGlslToSpirv(String shaderName, String glslSource, DxShaderStageKind stageKind) {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			return DxIrCompilationResult.bridgeUnavailable(irStatusFromLoadStatus(loadResult), loadResult.detail());
		}

		try {
			NativeDxIrCompilationResult nativeResult = compileGlslToSpirv0(shaderName, glslSource, stageKind.nativeShaderKind());
			return fromNativeIrResult(nativeResult);
		} catch (UnsatisfiedLinkError error) {
			return DxIrCompilationResult.bridgeUnavailable(
				DxIrCompilationStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native GLSL -> SPIR-V compile entrypoint is missing." : error.getMessage()
			);
		} catch (RuntimeException error) {
			return DxIrCompilationResult.bridgeUnavailable(
				DxIrCompilationStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native GLSL -> SPIR-V compile failed." : error.getMessage()
			);
		}
	}

	@Override
	public DxSpirvReflectionResult reflectSpirv(byte[] spirv) {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			return DxSpirvReflectionResult.bridgeUnavailable(reflectionStatusFromLoadStatus(loadResult), loadResult.detail());
		}

		try {
			NativeDxSpirvReflectionResult nativeResult = reflectSpirv0(spirv);
			return fromNativeReflectionResult(nativeResult);
		} catch (UnsatisfiedLinkError error) {
			return DxSpirvReflectionResult.bridgeUnavailable(
				DxSpirvReflectionStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native SPIR-V reflection entrypoint is missing." : error.getMessage()
			);
		} catch (RuntimeException error) {
			return DxSpirvReflectionResult.bridgeUnavailable(
				DxSpirvReflectionStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native SPIR-V reflection failed." : error.getMessage()
			);
		}
	}

	private DxSpirvReflectionStatus reflectionStatusFromLoadStatus(DxBridgeLoadResult loadResult) {
		return switch (loadResult.status()) {
			case UNSUPPORTED_OS -> DxSpirvReflectionStatus.UNSUPPORTED_OS;
			case NOT_FOUND -> DxSpirvReflectionStatus.LIBRARY_MISSING;
			case LOAD_FAILED -> DxSpirvReflectionStatus.LIBRARY_LOAD_FAILED;
			case DEPENDENCY_MISSING -> DxSpirvReflectionStatus.RUNTIME_DEPENDENCY_MISSING;
			case LOADED -> DxSpirvReflectionStatus.NATIVE_ERROR;
		};
	}

	private DxSpirvReflectionResult fromNativeReflectionResult(NativeDxSpirvReflectionResult nativeResult) {
		if (nativeResult == null) {
			return DxSpirvReflectionResult.bridgeUnavailable(
				DxSpirvReflectionStatus.NATIVE_ERROR,
				"DirectX bridge native SPIR-V reflection returned no result."
			);
		}

		return switch (nativeResult.outcomeCode()) {
			case NativeDxSpirvReflectionResult.OUTCOME_READY -> DxSpirvReflectionResult.ready(resourcesFromNativeArrays(nativeResult));
			case NativeDxSpirvReflectionResult.OUTCOME_REFLECTION_ERROR -> DxSpirvReflectionResult.reflectionError(nativeResult.detail());
			default -> DxSpirvReflectionResult.bridgeUnavailable(
				DxSpirvReflectionStatus.NATIVE_ERROR,
				nativeResult.detail() == null ? "DirectX bridge native SPIR-V reflection failed." : nativeResult.detail()
			);
		};
	}

	private List<DxReflectedResource> resourcesFromNativeArrays(NativeDxSpirvReflectionResult nativeResult) {
		String[] names = nativeResult.resourceNames();
		int[] kinds = nativeResult.resourceKinds();
		int[] sizes = nativeResult.resourceSizeBytes();

		if (names == null || kinds == null || sizes == null) {
			return List.of();
		}

		List<DxReflectedResource> resources = new ArrayList<>(names.length);
		for (int i = 0; i < names.length; i++) {
			resources.add(new DxReflectedResource(names[i], DxResourceKind.fromNativeCode(kinds[i]), sizes[i]));
		}
		return resources;
	}

	@Override
	public DxHlslCompilationResult emitAndCompileHlsl(
		String shaderName,
		byte[] spirv,
		DxShaderStageKind stageKind,
		List<DxResourceSlotAssignment> resourceSlots
	) {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			return DxHlslCompilationResult.bridgeUnavailable(hlslStatusFromLoadStatus(loadResult), loadResult.detail());
		}

		String[] names = new String[resourceSlots.size()];
		int[] kinds = new int[resourceSlots.size()];
		int[] slots = new int[resourceSlots.size()];
		for (int i = 0; i < resourceSlots.size(); i++) {
			DxResourceSlotAssignment assignment = resourceSlots.get(i);
			names[i] = assignment.name();
			kinds[i] = assignment.kind().ordinal();
			slots[i] = assignment.slot();
		}

		try {
			NativeDxHlslCompilationResult nativeResult = emitAndCompileHlsl0(shaderName, spirv, stageKind.nativeShaderKind(), names, kinds, slots);
			return fromNativeHlslCompilationResult(nativeResult);
		} catch (UnsatisfiedLinkError error) {
			return DxHlslCompilationResult.bridgeUnavailable(
				DxHlslCompilationStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native HLSL emission/compilation entrypoint is missing." : error.getMessage()
			);
		} catch (RuntimeException error) {
			return DxHlslCompilationResult.bridgeUnavailable(
				DxHlslCompilationStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native HLSL emission/compilation failed." : error.getMessage()
			);
		}
	}

	@Override
	public DxHlslCompilationResult compileHlsl(String shaderName, String hlsl, DxShaderStageKind stageKind) {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			return DxHlslCompilationResult.bridgeUnavailable(hlslStatusFromLoadStatus(loadResult), loadResult.detail());
		}

		try {
			return fromNativeHlslCompilationResult(compileHlsl0(shaderName, hlsl, stageKind.nativeShaderKind()));
		} catch (UnsatisfiedLinkError error) {
			return DxHlslCompilationResult.bridgeUnavailable(
				DxHlslCompilationStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native HLSL compilation entrypoint is missing." : error.getMessage()
			);
		} catch (RuntimeException error) {
			return DxHlslCompilationResult.bridgeUnavailable(
				DxHlslCompilationStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native HLSL compilation failed." : error.getMessage()
			);
		}
	}

	private DxHlslCompilationStatus hlslStatusFromLoadStatus(DxBridgeLoadResult loadResult) {
		return switch (loadResult.status()) {
			case UNSUPPORTED_OS -> DxHlslCompilationStatus.UNSUPPORTED_OS;
			case NOT_FOUND -> DxHlslCompilationStatus.LIBRARY_MISSING;
			case LOAD_FAILED -> DxHlslCompilationStatus.LIBRARY_LOAD_FAILED;
			case DEPENDENCY_MISSING -> DxHlslCompilationStatus.RUNTIME_DEPENDENCY_MISSING;
			case LOADED -> DxHlslCompilationStatus.NATIVE_ERROR;
		};
	}

	private DxHlslCompilationResult fromNativeHlslCompilationResult(NativeDxHlslCompilationResult nativeResult) {
		if (nativeResult == null) {
			return DxHlslCompilationResult.bridgeUnavailable(
				DxHlslCompilationStatus.NATIVE_ERROR,
				"DirectX bridge native HLSL emission/compilation returned no result."
			);
		}

		return switch (nativeResult.outcomeCode()) {
			case NativeDxHlslCompilationResult.OUTCOME_READY -> DxHlslCompilationResult.ready(nativeResult.bytecode(), nativeResult.hlsl());
			case NativeDxHlslCompilationResult.OUTCOME_EMISSION_ERROR -> DxHlslCompilationResult.emissionError(nativeResult.detail());
			case NativeDxHlslCompilationResult.OUTCOME_COMPILE_ERROR -> DxHlslCompilationResult.compileError(nativeResult.hlsl(), nativeResult.detail());
			default -> DxHlslCompilationResult.bridgeUnavailable(
				DxHlslCompilationStatus.NATIVE_ERROR,
				nativeResult.detail() == null ? "DirectX bridge native HLSL emission/compilation failed." : nativeResult.detail()
			);
		};
	}

	private DxIrCompilationStatus irStatusFromLoadStatus(DxBridgeLoadResult loadResult) {
		return switch (loadResult.status()) {
			case UNSUPPORTED_OS -> DxIrCompilationStatus.UNSUPPORTED_OS;
			case NOT_FOUND -> DxIrCompilationStatus.LIBRARY_MISSING;
			case LOAD_FAILED -> DxIrCompilationStatus.LIBRARY_LOAD_FAILED;
			case DEPENDENCY_MISSING -> DxIrCompilationStatus.RUNTIME_DEPENDENCY_MISSING;
			case LOADED -> DxIrCompilationStatus.NATIVE_ERROR;
		};
	}

	private DxIrCompilationResult fromNativeIrResult(NativeDxIrCompilationResult nativeResult) {
		if (nativeResult == null) {
			return DxIrCompilationResult.bridgeUnavailable(
				DxIrCompilationStatus.NATIVE_ERROR,
				"DirectX bridge native GLSL -> SPIR-V compile returned no result."
			);
		}

		return switch (nativeResult.outcomeCode()) {
			case NativeDxIrCompilationResult.OUTCOME_READY -> DxIrCompilationResult.ready(nativeResult.spirv());
			case NativeDxIrCompilationResult.OUTCOME_COMPILE_ERROR -> DxIrCompilationResult.compileError(
				nativeResult.detail(), nativeResult.numErrors(), nativeResult.numWarnings()
			);
			default -> DxIrCompilationResult.bridgeUnavailable(
				DxIrCompilationStatus.NATIVE_ERROR,
				nativeResult.detail() == null ? "DirectX bridge native GLSL -> SPIR-V compile failed." : nativeResult.detail()
			);
		};
	}

	private DxBridgeProbe runProbe(Supplier<NativeDxBridgeProbeResult> nativeCall) {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			return fromLoadResult(loadResult);
		}

		try {
			return fromNativeResult(nativeCall.get());
		} catch (UnsatisfiedLinkError error) {
			return new DxBridgeProbe(
				DxBridgeProbeStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native probe entrypoint is missing." : error.getMessage(),
				List.of("native_probe_entrypoint"),
				true,
				false,
				0,
				null,
				0,
				0L
			);
		} catch (RuntimeException error) {
			return new DxBridgeProbe(
				DxBridgeProbeStatus.NATIVE_ERROR,
				error.getMessage() == null ? "DirectX bridge native probe failed." : error.getMessage(),
				List.of("native_probe_runtime"),
				true,
				true,
				0,
				null,
				0,
				0L
			);
		}
	}

	private DxBridgeProbe fromLoadResult(DxBridgeLoadResult loadResult) {
		DxBridgeProbeStatus status = switch (loadResult.status()) {
			case UNSUPPORTED_OS -> DxBridgeProbeStatus.UNSUPPORTED_OS;
			case NOT_FOUND -> DxBridgeProbeStatus.LIBRARY_MISSING;
			case LOAD_FAILED -> DxBridgeProbeStatus.LIBRARY_LOAD_FAILED;
			case DEPENDENCY_MISSING -> DxBridgeProbeStatus.RUNTIME_DEPENDENCY_MISSING;
			case LOADED -> DxBridgeProbeStatus.NATIVE_ERROR;
		};

		List<String> missingCapabilities = switch (loadResult.status()) {
			case UNSUPPORTED_OS -> List.of("windows_host");
			case NOT_FOUND, LOAD_FAILED -> List.of("native_directx_bridge_library");
			case DEPENDENCY_MISSING -> List.of(loadResult.source());
			case LOADED -> List.of("native_directx_bridge");
		};

		return new DxBridgeProbe(
			status,
			loadResult.detail(),
			missingCapabilities,
			false,
			false,
			0,
			null,
			0,
			0L
		);
	}

	private DxBridgeProbe fromNativeResult(NativeDxBridgeProbeResult nativeResult) {
		if (nativeResult == null) {
			return new DxBridgeProbe(
				DxBridgeProbeStatus.NATIVE_ERROR,
				"DirectX bridge native probe returned no result.",
				List.of("native_probe_result"),
				true,
				true,
				0,
				null,
				0,
				0L
			);
		}

		List<String> missingCapabilities = nativeResult.missingCapabilities() == null
			? List.of()
			: Arrays.asList(nativeResult.missingCapabilities());

		DxBridgeProbeStatus status = switch (nativeResult.outcomeCode()) {
			case NativeDxBridgeProbeResult.OUTCOME_READY -> DxBridgeProbeStatus.READY;
			case NativeDxBridgeProbeResult.OUTCOME_STUB_UNIMPLEMENTED -> DxBridgeProbeStatus.STUB_UNIMPLEMENTED;
			default -> missingCapabilities.contains("feature_level_10_1")
				? DxBridgeProbeStatus.FEATURE_LEVEL_UNSUPPORTED
				: DxBridgeProbeStatus.NATIVE_ERROR;
		};

		return new DxBridgeProbe(
			status,
			nativeResult.detail(),
			missingCapabilities,
			true,
			true,
			nativeResult.featureLevel(),
			nativeResult.adapterDescription(),
			nativeResult.vendorId(),
			nativeResult.dedicatedVideoMemoryBytes()
		);
	}

	private DxHostSurfaceBootstrap fromNativeBootstrapResult(NativeDxBridgeSurfaceBootstrapResult nativeResult) {
		if (nativeResult == null) {
			return new DxHostSurfaceBootstrap(
				0L, 0, 0, 1.0,
				"DirectX bridge native surface bootstrap returned no result.",
				List.of("native_bootstrap_surface_result"),
				true,
				true
			);
		}

		List<String> missingCapabilities = nativeResult.missingCapabilities() == null
			? List.of()
			: Arrays.asList(nativeResult.missingCapabilities());

		long nativeSurfaceHandle = nativeResult.outcomeCode() == NativeDxBridgeSurfaceBootstrapResult.OUTCOME_READY
			? nativeResult.nativeSurfaceHandle()
			: 0L;

		return new DxHostSurfaceBootstrap(
			nativeSurfaceHandle,
			nativeResult.drawableWidth(),
			nativeResult.drawableHeight(),
			nativeResult.dpiScale(),
			nativeResult.detail(),
			missingCapabilities,
			true,
			true
		);
	}

	private static native NativeDxBridgeProbeResult probe0();

	private static native NativeDxBridgeProbeResult probeSurface0(long win32WindowHandle);

	private static native NativeDxBridgeSurfaceBootstrapResult bootstrapSurface0(long win32WindowHandle);

	private static native void configureSurface0(long nativeSurfaceHandle, int width, int height, boolean vsync);

	private static native void acquireSurface0(long nativeSurfaceHandle);

	// Named with a trailing "0" (matching this file's other native method
	// naming) rather than "blitSurfaceRgba8" to avoid a same-signature
	// clash with the public override above that forwards to it.
	private static native void blitSurfaceRgba80(long nativeSurfaceHandle, ByteBuffer rgbaPixels, int width, int height);

	private static native void presentSurface0(long nativeSurfaceHandle);

	private static native void releaseSurface0(long nativeSurfaceHandle);

	// Named "...Native"/"...0" because a static native method and its public
	// forwarding override cannot share a name when their parameter lists match.
	private static native void uploadTextureRgba8Native(long nativeTextureHandle, int mipLevel, int layer, ByteBuffer rgbaPixels, int width, int height);
	private static native void uploadTextureRegionRgba8Native(long nativeTextureHandle, int mipLevel, int layer, ByteBuffer rgbaPixels, int mipWidth, int mipHeight, int x, int y, int width, int height);

	private static native void releaseTexture0(long nativeTextureHandle);

	// Milestone 4.6 prerequisite scope (directexp_bridge_resource.cpp). Same
	// "...Native"/"...0" naming rationale as the texture natives above: a
	// static native and the public override forwarding to it can't share a
	// name when their parameter lists are identical.
	private static native long createBufferNative(int bindMask, int byteWidth);

	private static native void updateBufferNative(long nativeBufferHandle, ByteBuffer mirror, int offset, int length);

	private static native void releaseBuffer0(long nativeBufferHandle);

	// Sodium-scale buffers (directexp_bridge_resource.cpp).
	private static native void uploadBufferRangeNative(long nativeBufferHandle, int destinationOffset, ByteBuffer source, int sourceOffset, int length);

	private static native void readBufferNative(long nativeBufferHandle, int sourceOffset, ByteBuffer destination, int destinationOffset, int length);

	private static native void copyBufferNative(long sourceBufferHandle, int sourceOffset, long destinationBufferHandle, int destinationOffset, int length);

	private static native long createBufferShaderResourceViewNative(long nativeBufferHandle, int texelFormatCode, int firstElement, int elementCount);

	// Milestone 4.6 commit 2 (directexp_bridge_resource.cpp / directexp_bridge_pipeline.cpp).
	private static native long createTextureNative(int format, int width, int height, int arrayLayers, int mipLevels, int bindMask, boolean cubemap, String debugLabel);

	private static native long createPipelineNative(
		byte[] vertexBytecode, byte[] pixelBytecode, byte[] geometryBytecode,
		int[] attributeFormats, int[] attributeOffsets, int vertexStride,
		int[] state, float[] floatState
	);

	private static native void releasePipeline0(long nativePipelineHandle);

	private static native void setPipelineViewport0(long nativePipelineHandle, int width, int height);

	private static native long createSamplerNative(
		int addressU, int addressV, boolean minLinear, boolean magLinear, boolean mipLinear, int maxAnisotropy, float maxLod
	);

	private static native void releaseSampler0(long nativeSamplerHandle);

	private static native long createTextureViewNative(long nativeTextureHandle, int baseMipLevel, int mipLevels);

	private static native void releaseTextureView0(long nativeTextureViewHandle);

	private static native void blitSurfaceTexture0(long nativeCommandContextHandle, long nativeSurfaceHandle, long nativeTextureHandle);

	// Milestone 4.6 commit 4: draw path. Suffix "0" because the public
	// overrides above share names and parameter lists with these.
	private static native void setRenderTargets0(long colorViewHandle, long depthViewHandle, int width, int height);

	private static native void clearRenderTarget0(long colorViewHandle, float red, float green, float blue, float alpha);

	private static native void clearDepthStencil0(long depthViewHandle, float depth);

	private static native void clearDepthTexture0(long nativeTextureHandle, float depth);

	private static native void clearDepthRegion0(long nativeTextureHandle, int x, int y, int width, int height, float depth);

	private static native void setScissor0(int x, int y, int width, int height);

	private static native void bindPipeline0(long nativePipelineHandle);

	private static native void bindVertexBuffer0(int slot, long nativeBufferHandle, int byteOffset);

	private static native void bindIndexBuffer0(long nativeBufferHandle, boolean wideIndices, int byteOffset);

	private static native void bindConstantBuffer0(int slot, long nativeBufferHandle, int stageMask);

	private static native void bindShaderResource0(int slot, long nativeTextureViewHandle, int stageMask);

	private static native void bindSampler0(int slot, long nativeSamplerHandle, int stageMask);

	private static native void draw0(int vertexCount, int firstVertex);

	private static native void drawIndexed0(int indexCount, int instanceCount, int firstIndex, int baseVertex);

	private static native void multiDrawIndexed0(IntBuffer drawParameters, int instanceCount, int drawCount);

	// Named "...0" per this file's convention for a native method whose
	// public-facing override (compileGlslToSpirv) has a different
	// signature (DxShaderStageKind vs. this method's raw int) rather than
	// a same-signature clash like blitSurfaceRgba80/uploadTextureRgba8Native.
	private static native NativeDxIrCompilationResult compileGlslToSpirv0(String shaderName, String glslSource, int shaderStageKind);

	// Milestone 4.4: reflects one shader stage's SPIR-V. Named "...0" per
	// this file's convention for a native method whose public-facing
	// override (reflectSpirv) has the same parameter list here (no
	// same-signature clash actually applies, since the interface method
	// itself is only ever called through this class), kept for
	// consistency with compileGlslToSpirv0's naming.
	private static native NativeDxSpirvReflectionResult reflectSpirv0(byte[] spirv);

	// Milestone 4.5: emits HLSL for one shader stage's SPIR-V and
	// compiles it via D3DCompile. resourceNames/resourceKinds/resourceSlots
	// are three parallel arrays -- resourceKinds holding DxResourceKind
	// ordinals -- describing this stage's engine-owned resource layout;
	// see directexp_bridge_shader.cpp's ToResourceSlotAssignments for the
	// native-side unmarshalling.
	private static native NativeDxHlslCompilationResult emitAndCompileHlsl0(
		String shaderName, byte[] spirv, int shaderStageKind, String[] resourceNames, int[] resourceKinds, int[] resourceSlots
	);

	// HLSL text -> bytecode only (no SPIRV-Cross); stage encoding as emitAndCompileHlsl0 plus 2 = geometry.
	private static native NativeDxHlslCompilationResult compileHlsl0(String shaderName, String hlsl, int shaderStageKind);
}
