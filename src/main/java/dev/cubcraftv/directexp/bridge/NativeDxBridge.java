package dev.cubcraftv.directexp.bridge;

import java.util.Arrays;
import java.util.List;

public final class NativeDxBridge implements DxBridge {
	private static final NativeDxBridge INSTANCE = new NativeDxBridge();

	private NativeDxBridge() {
	}

	public static NativeDxBridge getInstance() {
		return INSTANCE;
	}

	@Override
	public DxBridgeProbe probe() {
		DxBridgeLoadResult loadResult = NativeDxBridgeLoader.ensureLoaded();
		if (!loadResult.isLoaded()) {
			return fromLoadResult(loadResult);
		}

		try {
			NativeDxBridgeProbeResult nativeResult = probe0();
			return fromNativeResult(nativeResult);
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
			case LOADED -> DxBridgeProbeStatus.NATIVE_ERROR;
		};

		List<String> missingCapabilities = switch (loadResult.status()) {
			case UNSUPPORTED_OS -> List.of("windows_host");
			case NOT_FOUND, LOAD_FAILED -> List.of("native_directx_bridge_library");
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

	private static native NativeDxBridgeProbeResult probe0();
}
