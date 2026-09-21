package dev.cubcraftv.directexp.bridge;

final class NativeDxBridgeLoader {
	static final String LIBRARY_NAME = "directexp_native";
	private static final String LIBRARY_PATH_PROPERTY = "directexp.nativeLibraryPath";
	private static volatile DxBridgeLoadResult cachedResult;

	private NativeDxBridgeLoader() {
	}

	static DxBridgeLoadResult ensureLoaded() {
		DxBridgeLoadResult cached = cachedResult;
		if (cached != null) {
			return cached;
		}

		synchronized (NativeDxBridgeLoader.class) {
			if (cachedResult != null) {
				return cachedResult;
			}

			cachedResult = loadLibrary();
			return cachedResult;
		}
	}

	private static DxBridgeLoadResult loadLibrary() {
		if (!isWindows()) {
			return new DxBridgeLoadResult(
				DxBridgeLoadStatus.UNSUPPORTED_OS,
				"DirectX bridge loading is only supported on Windows.",
				System.getProperty("os.name", "unknown")
			);
		}

		String explicitPath = System.getProperty(LIBRARY_PATH_PROPERTY, "").trim();
		if (!explicitPath.isEmpty()) {
			try {
				System.load(explicitPath);
				return new DxBridgeLoadResult(DxBridgeLoadStatus.LOADED, "Loaded DirectX bridge from explicit path.", explicitPath);
			} catch (UnsatisfiedLinkError error) {
				return new DxBridgeLoadResult(DxBridgeLoadStatus.LOAD_FAILED, error.getMessage(), explicitPath);
			}
		}

		try {
			System.loadLibrary(LIBRARY_NAME);
			return new DxBridgeLoadResult(DxBridgeLoadStatus.LOADED, "Loaded DirectX bridge from java.library.path.", LIBRARY_NAME);
		} catch (UnsatisfiedLinkError error) {
			String message = error.getMessage() == null ? "DirectX bridge native library was not found." : error.getMessage();
			return new DxBridgeLoadResult(DxBridgeLoadStatus.NOT_FOUND, message, LIBRARY_NAME);
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	static void resetForTests() {
		cachedResult = null;
	}
}
