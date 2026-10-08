package dev.cubcraftv.directexp.bridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class NativeDxBridgeLoader {
	static final String LIBRARY_NAME = "directexp_native";
	private static final String LIBRARY_PATH_PROPERTY = "directexp.nativeLibraryPath";
	private static final String BUNDLED_LIBRARY_RESOURCE = "/META-INF/native/windows-x86_64/directexp_native.dll";

	// Milestone 4.1 links d3dcompiler into directexp_native.dll (see
	// native/CMakeLists.txt). d3dcompiler_47.dll only ships as an OS
	// component on Windows 8.1+; see checkD3DCompilerAvailability's
	// javadoc for why this is worth a pre-check instead of letting the
	// real load attempt surface it.
	private static final String D3DCOMPILER_DLL_NAME = "d3dcompiler_47.dll";

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

		DxBridgeLoadResult missingRuntimeDependency = checkD3DCompilerAvailability(System.getenv("SystemRoot"));
		if (missingRuntimeDependency != null) {
			return missingRuntimeDependency;
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

		InputStream bundledLibrary = NativeDxBridgeLoader.class.getResourceAsStream(BUNDLED_LIBRARY_RESOURCE);
		if (bundledLibrary != null) {
			Path extractedLibrary;
			try {
				extractedLibrary = extractBundledLibrary(bundledLibrary);
			} catch (IOException error) {
				return new DxBridgeLoadResult(
					DxBridgeLoadStatus.LOAD_FAILED,
					"Failed to extract bundled DirectX bridge: " + error.getMessage(),
					BUNDLED_LIBRARY_RESOURCE
				);
			}

			try {
				System.load(extractedLibrary.toAbsolutePath().toString());
				return new DxBridgeLoadResult(
					DxBridgeLoadStatus.LOADED,
					"Loaded bundled DirectX bridge.",
					extractedLibrary.toAbsolutePath().toString()
				);
			} catch (UnsatisfiedLinkError error) {
				return new DxBridgeLoadResult(
					DxBridgeLoadStatus.LOAD_FAILED,
					error.getMessage(),
					extractedLibrary.toAbsolutePath().toString()
				);
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

	static Path extractBundledLibrary(InputStream libraryStream) throws IOException {
		try (libraryStream) {
			Path extractedLibrary = Files.createTempFile("directexp_native-", ".dll");
			try {
				Files.copy(libraryStream, extractedLibrary, StandardCopyOption.REPLACE_EXISTING);
				extractedLibrary.toFile().deleteOnExit();
				return extractedLibrary;
			} catch (IOException error) {
				try {
					Files.deleteIfExists(extractedLibrary);
				} catch (IOException cleanupError) {
					error.addSuppressed(cleanupError);
				}
				throw error;
			}
		}
	}

	/**
	 * Best-effort pre-check for {@code d3dcompiler_47.dll} at its
	 * well-known system location, run before {@code System.loadLibrary}
	 * is attempted at all.
	 *
	 * <p>{@code directexp_native.dll} now statically links against
	 * {@code d3dcompiler} (Milestone 4.1, see {@code native/CMakeLists.txt}).
	 * If {@code d3dcompiler_47.dll} is missing on the target machine, the
	 * Windows loader fails to resolve directexp_native.dll's own
	 * dependencies, and {@code System.loadLibrary} throws an
	 * {@link UnsatisfiedLinkError} whose message is a generic "module not
	 * found"-style string -- it does not say *which* dependency was
	 * actually missing, which is exactly the confusing failure mode the
	 * toolchain research notes flagged. Checking the known location
	 * ourselves first lets {@link DxBridgeLoadStatus#DEPENDENCY_MISSING}
	 * report the specific, actionable cause instead.
	 *
	 * <p>This is a heuristic, not a replacement for the real load
	 * attempt: it only checks the one location Windows 8.1+ guarantees
	 * for this specific DLL. If this check is wrong for some environment
	 * (a non-standard search path, a redistributable installed
	 * elsewhere), it returns {@code null} and the real
	 * {@code System.loadLibrary} call below remains the source of truth.
	 *
	 * @param systemRoot value of the {@code SystemRoot} environment
	 *                    variable, or {@code null}/blank if unavailable
	 *                    (in which case this check is skipped entirely)
	 * @return a {@code DEPENDENCY_MISSING} result if the DLL is
	 *         confirmed absent, else {@code null}
	 */
	static DxBridgeLoadResult checkD3DCompilerAvailability(String systemRoot) {
		if (systemRoot == null || systemRoot.isBlank()) {
			return null;
		}

		File d3dCompilerDll = new File(systemRoot, "System32" + File.separator + D3DCOMPILER_DLL_NAME);
		if (d3dCompilerDll.exists()) {
			return null;
		}

		return new DxBridgeLoadResult(
			DxBridgeLoadStatus.DEPENDENCY_MISSING,
			D3DCOMPILER_DLL_NAME + " was not found at " + d3dCompilerDll.getAbsolutePath()
				+ ". This DLL ships with Windows 8.1 and later as an OS component; DirectExp's "
				+ "shader toolchain (Milestone 4) links against it. If this is an older Windows "
				+ "version, install the D3D compiler redistributable; otherwise this may indicate "
				+ "a non-standard Windows installation. See native/README.md.",
			D3DCOMPILER_DLL_NAME
		);
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	static void resetForTests() {
		cachedResult = null;
	}
}
