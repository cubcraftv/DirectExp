package dev.cubcraftv.directexp.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers only {@link NativeDxBridgeLoader#checkD3DCompilerAvailability}, the
 * Milestone 4.1 pre-check added ahead of linking {@code d3dcompiler} into
 * {@code directexp_native.dll} (see that method's javadoc and
 * {@code native/CMakeLists.txt}). Deliberately does not exercise
 * {@code ensureLoaded()}/{@code loadLibrary()} as a whole: those ultimately
 * call {@code System.loadLibrary}, which this JVM-only test suite can't
 * satisfy on a non-Windows machine (or without the built DLL at all) -- see
 * AGENTS.md's note on {@code ./gradlew build} being Windows-only to verify.
 */
class NativeDxBridgeLoaderTest {

	@Test
	void returnsNullWhenSystemRootIsMissingOrBlank() {
		assertNull(NativeDxBridgeLoader.checkD3DCompilerAvailability(null));
		assertNull(NativeDxBridgeLoader.checkD3DCompilerAvailability(""));
		assertNull(NativeDxBridgeLoader.checkD3DCompilerAvailability("   "));
	}

	@Test
	void returnsNullWhenD3DCompilerDllIsPresent(@TempDir Path tempSystemRoot) throws IOException {
		Path system32 = tempSystemRoot.resolve("System32");
		Files.createDirectories(system32);
		Files.createFile(system32.resolve("d3dcompiler_47.dll"));

		assertNull(NativeDxBridgeLoader.checkD3DCompilerAvailability(tempSystemRoot.toString()));
	}

	@Test
	void reportsDependencyMissingWhenD3DCompilerDllIsAbsent(@TempDir Path tempSystemRoot) {
		// tempSystemRoot exists but its System32 subdirectory (and the DLL
		// in it) deliberately does not -- simulates a real "missing
		// dependency" environment without needing an actual Windows host.
		DxBridgeLoadResult result = NativeDxBridgeLoader.checkD3DCompilerAvailability(tempSystemRoot.toString());

		assertNotNull(result);
		assertEquals(DxBridgeLoadStatus.DEPENDENCY_MISSING, result.status());
		assertEquals("d3dcompiler_47.dll", result.source());
		assertTrue(result.detail().contains("d3dcompiler_47.dll"));
	}

	@Test
	void reportedPathUsesSystem32Subdirectory(@TempDir Path tempSystemRoot) {
		DxBridgeLoadResult result = NativeDxBridgeLoader.checkD3DCompilerAvailability(tempSystemRoot.toString());

		assertNotNull(result);
		String expectedPath = new File(tempSystemRoot.toFile(), "System32" + File.separator + "d3dcompiler_47.dll")
			.getAbsolutePath();
		assertTrue(result.detail().contains(expectedPath));
	}
}
